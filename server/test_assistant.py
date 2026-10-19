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

    def test_compact_state_and_attention_policy(self):
        self.a.changed(state='completed',reply='结果'*10000)
        value=self.a.snapshot(True)
        self.assertNotIn('reply',value)
        self.assertNotIn('supplements',value)
        self.assertEqual(len(value['summary']),180)
        self.assertFalse(value['needs_attention'])
        self.a.changed(state='failed',message='需要检查电脑服务')
        self.assertTrue(self.a.snapshot(True)['needs_attention'])
        self.a.changed(state='interrupted',message='任务已停止')
        self.assertFalse(self.a.snapshot()['needs_attention'])

    def test_text_deltas_do_not_refresh_watch(self):
        self.a.changed(state='running',turn_id='t1')
        revision=self.a.state['revision']
        for _ in range(100):
            self.a.notify({'method':'item/agentMessage/delta','params':{'threadId':'test-thread','delta':'字'}})
        self.assertEqual(self.a.state['revision'],revision)
        self.a.notify({'method':'item/started','params':{'threadId':'test-thread','item':{'type':'commandExecution'}}})
        self.assertEqual(self.a.state['message'],'正在执行电脑操作')
        phase_revision=self.a.state['revision']
        self.a.notify({'method':'item/started','params':{'threadId':'test-thread','item':{'type':'commandExecution'}}})
        self.assertEqual(self.a.state['revision'],phase_revision)

    def test_steer_targets_exact_turn_and_deduplicates(self):
        self.a.changed(state='running',turn_id='turn1')
        with patch.object(self.a,'call',return_value={'turnId':'turn1'}) as call:
            value=self.a.steer('改成中文','s1','test-thread','turn1')
            self.assertTrue(value['supplement_accepted'])
            self.a.steer('改成中文','s1','test-thread','turn1')
            self.assertEqual(call.call_count,1)
            self.assertEqual(call.call_args.args[0],'turn/steer')
            self.assertEqual(call.call_args.args[1]['expectedTurnId'],'turn1')
            with self.assertRaises(ValueError):self.a.steer('different','s1','test-thread','turn1')
            with self.assertRaises(RuntimeError):self.a.steer('new','s2','test-thread','old-turn')
            self.assertEqual(call.call_count,1)

    def test_steer_waiting_and_unknown_delivery_are_not_replayed(self):
        self.a.changed(state='running',turn_id='turn1')
        with patch.object(self.a,'call',side_effect=RuntimeError('timeout')) as call:
            with self.assertRaises(RuntimeError):self.a.steer('hello','s1','test-thread','turn1')
            with self.assertRaises(RuntimeError):self.a.steer('hello','s1','test-thread','turn1')
            self.assertEqual(call.call_count,1)
        self.a.request({'id':20,'method':'item/commandExecution/requestApproval',
            'params':{'threadId':'test-thread','command':'echo test'}})
        with patch.object(self.a,'call') as call:
            with self.assertRaises(RuntimeError):self.a.steer('hello','s2','test-thread','turn1')
            call.assert_not_called()

    def test_retry_after_restart_does_not_send_duplicate_supplement(self):
        self.a.changed(state='running',turn_id='turn1')
        with patch.object(self.a,'call',return_value={'turnId':'turn1'}):
            self.a.steer('hello','s1','test-thread','turn1')
        restored=Assistant(self.a.file)
        with patch.object(restored,'call') as call:
            self.assertTrue(restored.steer('hello','s1','test-thread','turn1')['supplement_accepted'])
            call.assert_not_called()

    def test_excessive_approval_detail_is_declined_not_truncated(self):
        self.a.request({'id':21,'method':'item/commandExecution/requestApproval',
            'params':{'threadId':'test-thread','command':'x'*10001,'reason':None}})
        self.assertEqual(self.sent[-1]['result']['decision'],'decline')
        self.assertEqual(self.a.snapshot()['pending'],[])

    def test_supplement_limit_keeps_old_receipts(self):
        self.a.changed(state='running',turn_id='turn1')
        with patch.object(self.a,'call',return_value={'turnId':'turn1'}) as call:
            for i in range(32):self.a.steer('hello',str(i),'test-thread','turn1')
            with self.assertRaises(RuntimeError):self.a.steer('hello','33','test-thread','turn1')
            self.a.steer('hello','0','test-thread','turn1')
            self.assertEqual(call.call_count,32)

if __name__=='__main__':unittest.main()
