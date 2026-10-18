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

class BridgeTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.old = (bridge.STATE, bridge.JOB)
        bridge.STATE = Path(self.tmp.name) / 'state.json'
        bridge.JOB = {'state': 'idle'}
    def tearDown(self):
        bridge.STATE, bridge.JOB = self.old
        self.tmp.cleanup()
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

if __name__ == '__main__': unittest.main()
