import json
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch
from urllib.request import Request, urlopen
from urllib.error import HTTPError
from http.server import ThreadingHTTPServer
import ai_bridge as bridge
import pc_listener
from codex_session import Assistant

class BridgeTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.old = (bridge.STATE, bridge.JOB)
        bridge.STATE = Path(self.tmp.name) / 'state.json'
        bridge.JOB = {'state': 'idle'}
    def tearDown(self):
        bridge.STATE, bridge.JOB = self.old
        self.tmp.cleanup()

    def test_legacy_backend_accepts_a_shared_client_view_selector(self):
        assistant = Assistant(Path(self.tmp.name) / 'legacy.json')
        server = ThreadingHTTPServer(('127.0.0.1', 0), pc_listener.Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        base = 'http://127.0.0.1:' + str(server.server_port)
        try:
            with patch.object(pc_listener, 'assistant', assistant):
                for route in ('state?compact=1', 'events?after=-1&compact=1'):
                    try:
                        value = json.load(urlopen(base + '/assistant/' + route + '&thread_id=old-view&t=' + pc_listener.TOKEN))
                    except Exception as exc:
                        self.fail('Legacy backend must remain reachable with a saved view selector: ' + str(exc))
                    self.assertEqual(value['protocol_version'], 4)
        finally:
            server.shutdown(); server.server_close(); thread.join()
    def test_validation_dedup_and_busy(self):
        with self.assertRaises(ValueError): bridge.submit('', 'x')
        with patch.object(threading.Thread, 'start'):
            first = bridge.submit('测试中文', 'one')
            self.assertEqual(bridge.submit('测试中文', 'one')['id'], first['id'])
            with self.assertRaises(RuntimeError): bridge.submit('另一个任务', 'two')
        self.assertEqual(bridge.stop()['state'], 'stopping')
        self.assertEqual(json.loads(bridge.STATE.read_text(encoding='utf-8'))['state'], 'stopping')
    def test_http_auth_and_validation(self):
        server = ThreadingHTTPServer(('127.0.0.1', 0), pc_listener.Handler)
        t = threading.Thread(target=server.serve_forever, daemon=True); t.start()
        base = 'http://127.0.0.1:' + str(server.server_port)
        try:
            req = Request(base+'/ai/task', data=b'{}', method='POST')
            with self.assertRaises(HTTPError) as cm: urlopen(req)
            self.assertEqual(cm.exception.code, 403)
            req.add_header('Authorization', 'Bearer '+pc_listener.TOKEN)
            with self.assertRaises(HTTPError) as cm: urlopen(req)
            self.assertEqual(cm.exception.code, 400)
            req = Request(base+'/ai/status?t='+pc_listener.TOKEN)
            self.assertIn('未开始', urlopen(req).read().decode())
        finally:
            server.shutdown(); server.server_close(); t.join()

    def test_assistant_compact_and_steer_http_contract(self):
        assistant=Assistant(Path(self.tmp.name)/'assistant.json')
        assistant.changed(state='running',thread_id='thread1',turn_id='turn1',reply='large text'*1000)
        server=ThreadingHTTPServer(('127.0.0.1',0),pc_listener.Handler)
        t=threading.Thread(target=server.serve_forever,daemon=True);t.start()
        base='http://127.0.0.1:'+str(server.server_port)
        try:
            with patch.object(pc_listener,'assistant',assistant):
                value=json.loads(urlopen(base+'/assistant/state?compact=1&t='+pc_listener.TOKEN).read())
                self.assertNotIn('reply',value)
                req=Request(base+'/assistant/steer',data=b'{}',method='POST')
                with self.assertRaises(HTTPError) as cm:urlopen(req)
                self.assertEqual(cm.exception.code,403)
                req.add_header('Authorization','Bearer '+pc_listener.TOKEN)
                with self.assertRaises(HTTPError) as cm:urlopen(req)
                self.assertEqual(cm.exception.code,400)
                payload=dict(prompt='change',request_id='s1',thread_id='thread1',turn_id='old')
                req=Request(base+'/assistant/steer',data=json.dumps(payload).encode(),headers={'Authorization':'Bearer '+pc_listener.TOKEN})
                with self.assertRaises(HTTPError) as cm:urlopen(req)
                self.assertEqual(cm.exception.code,409)
                payload['turn_id']='turn1'
                with patch.object(assistant,'call',return_value={'turnId':'turn1'}) as call:
                    req=Request(base+'/assistant/steer',data=json.dumps(payload).encode(),headers={'Authorization':'Bearer '+pc_listener.TOKEN})
                    self.assertTrue(json.loads(urlopen(req).read())['supplement_accepted'])
                    self.assertTrue(json.loads(urlopen(req).read())['supplement_accepted'])
                    self.assertEqual(call.call_count,1)
        finally:
            server.shutdown();server.server_close();t.join()

    def test_conversation_http_routes_and_target_guards(self):
        assistant=Assistant(Path(self.tmp.name)/'conversation.json')
        assistant.store.register('watch1','手表对话')
        assistant.changed(thread_id='watch1',turn_id='turn1',state='running')
        server=ThreadingHTTPServer(('127.0.0.1',0),pc_listener.Handler)
        t=threading.Thread(target=server.serve_forever,daemon=True);t.start()
        base='http://127.0.0.1:'+str(server.server_port)
        def get(path,auth=True):
            return json.loads(urlopen(base+path+('&' if '?' in path else '?')+'t='+(pc_listener.TOKEN if auth else 'wrong')).read())
        def post(path,payload):
            return json.loads(urlopen(Request(base+path,data=json.dumps(payload).encode(),headers={'Authorization':'Bearer '+pc_listener.TOKEN})).read())
        def rpc(method,params):
            if method=='thread/list':return {'data':[{'id':'pc1','name':'电脑记录'}],'nextCursor':'next'}
            if method=='thread/read':return {'thread':{'id':params['threadId'],'name':'电脑记录'}}
            if method=='thread/items/list':return {'data':[{'item':{'type':'agentMessage','id':'a1','text':'x'*1200}}]}
            self.fail('unexpected RPC '+method)
        try:
            with patch.object(pc_listener,'assistant',assistant),patch.object(assistant.conversations,'rpc',side_effect=rpc),patch.object(assistant,'call') as call:
                with self.assertRaises(HTTPError) as cm:get('/assistant/conversations',False)
                self.assertEqual(cm.exception.code,403)
                page=get('/assistant/conversations')
                self.assertTrue(page['conversations'][0]['readonly'])
                self.assertEqual(page['next_cursor'],'next')
                history=get('/assistant/conversations/messages?thread_id=pc1')
                self.assertEqual(len(history['messages'][0]['text']),180)
                self.assertEqual(len(get('/assistant/conversations/message?thread_id=pc1&item_id=a1&page=1')['text']),200)
                for route,payload in [('/assistant/send',dict(prompt='bad',request_id='pc-write',thread_id='pc1')),
                    ('/assistant/stop',dict(thread_id='pc1',turn_id='turn1')),
                    ('/assistant/answer',dict(id='missing',decision='accept',thread_id='pc1',turn_id='turn1'))]:
                    with self.assertRaises(HTTPError) as cm:post(route,payload)
                    self.assertEqual(cm.exception.code,409)
                with self.assertRaises(HTTPError) as cm:get('/assistant/conversations/messages')
                self.assertEqual(cm.exception.code,400)
                call.assert_not_called()
                self.assertEqual(assistant.state['thread_id'],'watch1')
        finally:
            server.shutdown();server.server_close();t.join()

if __name__ == '__main__': unittest.main()
