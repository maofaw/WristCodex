import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from codex_session import Assistant

class AssistantTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.a=Assistant(Path(self.temp.name)/'state.json')
        self.sent=[];self.a.write=self.sent.append
        self.a.state['thread_id']='test-thread'
    def tearDown(self):self.temp.cleanup()
    def test_single_use_approval(self):
        self.a.request({'id':17,'method':'item/commandExecution/requestApproval',
                        'params':{'threadId':'test-thread','command':'echo test','reason':'test'}})
        pending=self.a.snapshot()['pending'][0]
        self.assertEqual(self.a.snapshot()['state'],'waiting')
        self.a.answer(pending['id'],'decline')
        self.assertEqual(self.sent[-1],{'id':17,'result':{'decision':'decline'}})
        with self.assertRaises(ValueError):self.a.answer(pending['id'],'accept')
    def test_question_answers(self):
        self.a.request({'id':18,'method':'item/tool/requestUserInput','params':{
            'threadId':'test-thread','questions':[{'id':'choice','question':'choose','options':[{'label':'A'}]}]}})
        pending=self.a.snapshot()['pending'][0]
        with self.assertRaises(ValueError):self.a.answer(pending['id'],answers={})
        self.a.answer(pending['id'],answers={'choice':'A'})
        self.assertEqual(self.sent[-1]['result']['answers']['choice']['answers'],['A'])
    def test_restart_preserves_session_not_pending(self):
        self.a.changed(state='running',thread_id='saved',turn_id='turn1')
        restored=Assistant(self.a.file)
        self.assertEqual(restored.state['thread_id'],'saved')
        self.assertEqual(restored.state['state'],'interrupted')
        self.assertEqual(restored.state['pending'],[])
    def test_duplicate_and_busy(self):
        with patch('threading.Thread.start'):
            first=self.a.send('hello','req1')
            self.assertEqual(self.a.send('hello','req1')['request_id'],first['request_id'])
            with self.assertRaises(RuntimeError):self.a.send('hello','req2')
    def test_unsupported_permissions_are_not_granted(self):
        self.a.request({'id':19,'method':'item/permissions/requestApproval','params':{'threadId':'test-thread'}})
        self.assertEqual(self.sent[-1]['result']['permissions'],{})

if __name__=='__main__':unittest.main()
