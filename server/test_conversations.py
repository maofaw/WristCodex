import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from codex_session import Assistant


class ConversationTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.a=Assistant(Path(self.temp.name)/'assistant-state.json')
        self.a.store.register('watch1','第一段')
        self.a.store.register('watch2','第二段')
        self.a.changed(thread_id='watch1',state='completed',reply='第一段结果',turn_id='t1')
        self.c=self.a.conversations

    def tearDown(self):self.temp.cleanup()

    def test_pc_metadata_and_history_do_not_resume_or_select(self):
        self.a.changed(state='running')
        before=self.a.snapshot()
        def rpc(method,params):
            if method=='thread/read':return {'thread':{'id':'pc1','name':'电脑原题目','originator':'codex_desktop'}}
            if method=='thread/items/list':return {'data':[
                {'item':{'id':'a','type':'agentMessage','text':'答复'*1000}},
                {'item':{'id':'tool','type':'commandExecution','command':'private'}},
                {'item':{'id':'comment','type':'agentMessage','phase':'commentary','text':'working'}},
                {'item':{'id':'u','type':'userMessage','content':[{'type':'text','text':'问题'}]}}], 'nextCursor':'older'}
            self.fail('Unexpected mutating RPC '+method)
        with patch.object(self.c,'rpc',side_effect=rpc) as call:
            page=self.c.messages('pc1')
        self.assertTrue(page['conversation']['readonly'])
        self.assertEqual(page['conversation']['title'],'电脑原题目')
        self.assertEqual([x['id'] for x in page['messages']],['u','a'])
        self.assertEqual(len(page['messages'][1]['text']),180)
        self.assertEqual(self.a.snapshot(),before)
        self.assertEqual([x.args[0] for x in call.call_args_list],['thread/read','thread/items/list'])
        self.assertEqual(self.c.message('pc1','a',0)['pages'],2)
        self.assertEqual(len(self.c.message('pc1','a',1)['text']),1000)
        with self.assertRaises(RuntimeError):self.c.message('watch1','a',0)

    def test_pc_is_readonly_even_if_client_claims_writable(self):
        with patch.object(self.c,'rpc',return_value={'thread':{'id':'pc1','originator':'codex_desktop'}}),patch('threading.Thread.start') as start:
            with self.assertRaises(RuntimeError):self.a.send('execute','req-pc',False,'pc1')
            start.assert_not_called()
        self.assertEqual(self.a.state['thread_id'],'watch1')

    def test_native_watch_origin_is_recognized_without_cwd_guessing(self):
        with patch.object(self.c,'rpc',return_value={'thread':{'id':'old-watch','originator':'wristcodex','preview':'原来的问题'}}):
            self.c.writable('old-watch')
        self.assertTrue(self.a.store.owns('old-watch'))

    def test_switch_retains_results_and_global_request_dedup(self):
        with patch('threading.Thread.start') as start:
            self.a.send('第二段消息','r2',False,'watch2')
            self.assertEqual(self.a.store.state('watch1')['reply'],'第一段结果')
            self.a.changed(state='completed',reply='第二段结果')
            self.a.send('返回第一段','r3',False,'watch1')
            current=self.a.snapshot()
            duplicate=self.a.send('第二段消息','r2',False,'watch2')
            self.assertEqual(duplicate['accepted_thread_id'],'watch2')
            self.assertEqual(self.a.snapshot(),current)
            self.assertEqual(start.call_count,2)
            self.assertEqual(self.a.store.state('watch2')['reply'],'第二段结果')

    def test_busy_blocks_new_or_other_thread(self):
        self.a.changed(state='running')
        with patch('threading.Thread.start') as start:
            with self.assertRaises(RuntimeError):self.a.send('new','r1',True,'')
            with self.assertRaises(RuntimeError):self.a.send('other','r2',False,'watch2')
            start.assert_not_called()

    def test_restart_preserves_receipts_and_catalog(self):
        with patch('threading.Thread.start'):self.a.send('hello','r1',False,'watch2')
        restored=Assistant(self.a.file)
        with patch('threading.Thread.start') as start:
            self.assertEqual(restored.send('hello','r1',False,'watch2')['accepted_thread_id'],'watch2')
            start.assert_not_called()
        self.assertTrue(restored.store.owns('watch1'))
        with self.assertRaises(ValueError):restored.send('changed','r1',False,'watch2')

    def test_stop_and_approval_validate_thread_and_turn(self):
        self.a.changed(state='running')
        sent=[];self.a.write=sent.append
        self.a.request({'id':1,'method':'item/commandExecution/requestApproval',
            'params':{'threadId':'watch1','command':'echo test'}})
        token=self.a.snapshot()['pending'][0]['id'];before=len(sent)
        with self.assertRaises(RuntimeError):self.a.answer(token,'accept',thread_id='watch2',turn_id='t1')
        with self.assertRaises(RuntimeError):self.a.stop('watch2','t1')
        with self.assertRaises(RuntimeError):self.a.answer(token,'accept',thread_id='watch1',turn_id='old')
        self.assertEqual(len(sent),before)
        self.a.answer(token,'decline',thread_id='watch1',turn_id='t1')
        self.assertEqual(sent[-1]['result']['decision'],'decline')

    def test_new_conversation_is_lazy_and_not_a_copy(self):
        with patch('threading.Thread.start') as start:
            self.a.send('新内容','new1',True,'')
        self.assertEqual(self.a.state['thread_id'],'')
        self.assertEqual(self.a.store.state('watch1')['reply'],'第一段结果')
        self.assertEqual(set(self.a.store.index),{'watch1','watch2'})
        self.assertEqual(start.call_count,1)

    def test_cache_and_native_page_sizes_are_bounded(self):
        for i in range(80):self.c.remember('pc1',str(i),'x'*21000)
        self.assertEqual(len(self.c.cache),64)
        self.assertEqual(len(self.c.cache[('pc1','79')]),20000)
        with self.assertRaises(ValueError):self.c.message('pc1','79',20)
        with patch.object(self.c,'rpc',return_value={'data':[{'id':'pc','name':'PC'},{'id':'child','parentThreadId':'pc'}],'nextCursor':'next'}) as rpc:
            value=self.c.list()
        self.assertEqual(len(value['conversations']),1)
        self.assertEqual(rpc.call_args.args[1]['limit'],20)
        self.assertEqual(value['next_cursor'],'next')

    def test_old_timer_cannot_interrupt_new_turn_or_conversation(self):
        self.a.changed(state='running',request_id='new-request',thread_id='watch2',turn_id='new-turn')
        with patch.object(self.a,'call') as call:
            self.a.expire('old-request','watch1','t1')
            self.a.expire('new-request','watch2','old-turn')
            call.assert_not_called()
            self.a.expire('new-request','watch2','new-turn')
            call.assert_called_once_with('turn/interrupt',dict(threadId='watch2',turnId='new-turn'))


if __name__=='__main__':unittest.main()
