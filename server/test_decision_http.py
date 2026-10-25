import json
import os
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch
from urllib.request import Request, urlopen
from urllib.error import HTTPError
from http.server import ThreadingHTTPServer

from codex_session import Assistant
from shared_assistant import SharedAssistant
from test_shared_assistant import FakeDesktop, projection
from test_decisions import request


class DecisionHttpTests(unittest.TestCase):
    def setUp(self):
        with patch.dict(os.environ,{'WATCHREMOTE_TOKEN':'test-only-http-contract-secret'}):
            import pc_listener
        self.temp = tempfile.TemporaryDirectory()
        self.desktop = FakeDesktop()
        self.a = SharedAssistant(Assistant(Path(self.temp.name)/'state.json'), desktop=self.desktop)
        self.a.verified_capabilities.add('permissions')
        self.desktop.sessions['pc1'] = projection(requests=[request('item/permissions/requestApproval', permissions=dict(network=dict(enabled=True)))])
        self.patch = patch.object(pc_listener, 'assistant', self.a)
        self.patch.start()
        self.server = ThreadingHTTPServer(('127.0.0.1', 0), pc_listener.Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base = 'http://127.0.0.1:'+str(self.server.server_port)
        self.token = pc_listener.TOKEN

    def tearDown(self):
        self.server.shutdown();self.server.server_close();self.thread.join();self.patch.stop();self.temp.cleanup()

    def get(self, protocol=None):
        headers={} if protocol is None else {'X-WristCodex-Protocol':str(protocol)}
        with urlopen(Request(self.base+'/assistant/state?compact=1&thread_id=pc1&t='+self.token,headers=headers)) as r:
            return json.load(r)

    def test_old_client_gets_manual_instead_of_unrenderable_new_controls(self):
        self.assertEqual(self.get()['pending'][0]['kind'], 'manual')
        self.assertEqual(self.get(6)['pending'][0]['kind'], 'permissions')

    def test_invalid_protocol_is_rejected_before_mutating_preferences(self):
        body=json.dumps(dict(desktop_attention_enabled=False)).encode()
        req=Request(self.base+'/assistant/preferences',data=body,headers={'Authorization':'Bearer '+self.token,
            'Content-Type':'application/json','X-WristCodex-Protocol':'not-a-number'})
        with self.assertRaises(HTTPError) as error:urlopen(req)
        self.assertEqual(error.exception.code,400)
        self.assertTrue(self.a.desktop_attention_enabled)

    def test_answer_accepts_versioned_typed_response_without_exposing_token(self):
        view=self.get(6)['pending'][0]
        body=json.dumps(dict(id=view['id'],thread_id='pc1',turn_id='turn1',expected_version=view['version'],response=dict(permissions={}))).encode()
        req=Request(self.base+'/assistant/answer',data=body,headers={'Authorization':'Bearer '+self.token,
            'Content-Type':'application/json','X-WristCodex-Protocol':'6'})
        with urlopen(req) as response:self.assertEqual(response.status,200)
        self.assertEqual(self.desktop.calls[-1][0],'thread-follower-permissions-request-approval-response')

    def test_management_failure_distinguishes_unsent_from_unknown(self):
        def submit(operation):
            body=json.dumps(dict(thread_id='pc1',changes=dict(effort='low'),expected_version='stale',operation_id=operation)).encode()
            req=Request(self.base+'/assistant/conversations/settings',data=body,headers={
                'Authorization':'Bearer '+self.token,'Content-Type':'application/json','X-WristCodex-Protocol':'6'})
            with self.assertRaises(HTTPError) as error:urlopen(req)
            return json.load(error.exception)
        self.assertEqual(submit('never-sent')['delivery_status'],'not_sent')
        from decisions import digest
        self.a.store.record('settings:'+digest(['pc1','unknown']),dict(status='unknown',fingerprint='any'))
        self.assertEqual(submit('unknown')['delivery_status'],'unknown')

    def test_bound_status_is_read_only_and_modern_failure_is_structured(self):
        from urllib.parse import urlencode
        view=self.get(6)['pending'][0]
        scope=self.get(6)['connection_scope']
        query=dict(t=self.token,id=view['id'],thread_id='pc1',turn_id='turn1',expected_version=view['version'],connection_scope=scope)
        with urlopen(self.base+'/assistant/answer/status?'+urlencode(query)) as r:
            status=json.load(r)
        self.assertTrue(status['decision_result']['can_submit'])
        body=json.dumps(dict(id=view['id'],thread_id='pc1',turn_id='turn1',expected_version='stale',connection_scope=scope,response={})).encode()
        req=Request(self.base+'/assistant/answer',data=body,headers={'Authorization':'Bearer '+self.token,
            'Content-Type':'application/json','X-WristCodex-Protocol':'6'})
        with self.assertRaises(HTTPError) as error:urlopen(req)
        value=json.load(error.exception)
        self.assertIn('error',value)
        self.assertEqual(value['decision_result']['request_status'],'stale')
        self.assertEqual(value['decision_result']['delivery_status'],'not_sent')
        self.assertEqual(value['connection_scope'],scope)
        self.assertEqual(self.desktop.calls,[])

    def test_duplicate_and_conflicting_answers_are_explicit_without_resending(self):
        view=self.get(6)['pending'][0]
        payload=dict(id=view['id'],thread_id='pc1',turn_id='turn1',expected_version=view['version'],response=dict(permissions={}))
        def submit():
            return urlopen(Request(self.base+'/assistant/answer',data=json.dumps(payload).encode(),headers={
                'Authorization':'Bearer '+self.token,'Content-Type':'application/json','X-WristCodex-Protocol':'6'}))
        with submit() as r:self.assertEqual(r.status,200)
        with self.assertRaises(HTTPError) as error:submit()
        self.assertEqual(json.load(error.exception)['decision_result']['submission_outcome'],'duplicate')
        payload['response']=dict(permissions=dict(network=dict(enabled=True)))
        with self.assertRaises(HTTPError) as error:submit()
        self.assertEqual(json.load(error.exception)['decision_result']['submission_outcome'],'conflict')
        self.assertEqual(len(self.desktop.calls),1)

    def test_serialization_preserves_captured_scope_after_reconnect(self):
        captured=self.a.snapshot(True,'pc1')
        scope=captured['connection_scope']
        self.desktop.connection_generation=99
        self.assertNotEqual(self.a.identity_envelope()['connection_scope'],scope)
        with patch.object(self.a,'snapshot',return_value=captured):
            response=self.get(6)
        self.assertEqual(response['connection_scope'],scope)
        self.assertEqual(response['attention_generation'],scope)


if __name__ == '__main__':unittest.main()
