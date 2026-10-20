import copy
import json
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch

from codex_session import Assistant
from desktop_ipc import DesktopIPC, DesktopUnavailable, DeliveryUnknown, Projection, patch_value
from shared_assistant import SharedAssistant


def state(ident='pc1', status='active', requests=None):
    return dict(id=ident, hostId='local', title='电脑对话', cwd='C:/work',
        threadRuntimeStatus=dict(type=status), currentPermissions=dict(runtimeWorkspaceRoots=['C:/work']),
        latestCollaborationMode=dict(mode='default', settings=dict(model='preserve-model')),
        requests=requests or [], turns=[], turnHistory=dict(kind='canonical', history=dict(entitiesByKey={
            'latest': dict(turnId='turn1', status='inProgress' if status == 'active' else 'completed',
                turnStartedAtMs=100, params=dict(input=[dict(type='text', text='原问题')]), items=[])})))


def command(ident='pc1', token=7, turn='turn1'):
    return dict(id=token, method='item/commandExecution/requestApproval', params=dict(
        threadId=ident, turnId=turn, itemId='cmd1', command='echo test',
        availableDecisions=['accept', dict(acceptWithExecpolicyAmendment={}), 'cancel']))


def projection(ident='pc1', status='active', requests=None):
    value = Projection('owner1')
    value.accept(dict(type='snapshot', revision=1, conversationState=state(ident, status, requests)))
    return value


class FakeDesktop:
    def __init__(self):
        self.lock = threading.RLock()
        self.connected = True
        self.error = ''
        self.sessions = {'pc1': projection(status='idle')}
        self.calls = []
        self.fail = False

    def peek(self, ident):
        value = self.sessions.get(ident)
        return value.snapshot(self.connected) if value else None

    def discover(self, ident):
        if not self.connected or ident not in self.sessions:
            raise DesktopUnavailable('no-client-found')
        return self.sessions[ident]

    def request(self, method, params, target=None, timeout=8):
        self.calls.append((method, copy.deepcopy(params), target))
        if self.fail:
            raise DeliveryUnknown('response lost')
        if method == 'thread-follower-start-turn':
            return dict(resultType='success', result=dict(result=dict(turn=dict(id='new-turn'))))
        if method.endswith('approval-decision') or method.endswith('submit-user-input'):
            self.sessions[params['conversationId']].requests = []
        return dict(resultType='success', result=dict(ok=True))

    def start(self):
        pass

    def close(self):
        pass


class ProjectionTests(unittest.TestCase):
    def test_native_canonical_snapshot_is_projected_and_bounded(self):
        raw = state()
        entities = raw['turnHistory']['history']['entitiesByKey']
        for i in range(12):
            entities[str(i)] = dict(turnId=str(i), status='completed', turnStartedAtMs=200 + i,
                params={}, items=[dict(type='agentMessage', text='x' * 25000)])
        p = Projection('owner')
        p.accept(dict(type='snapshot', revision=1, conversationState=raw))
        self.assertEqual(len(p.turns), 4)
        self.assertEqual(p.latest_turn()['turnId'], '11')
        self.assertEqual(len(p.reply), 20000)
        self.assertNotIn('turnHistory', p.data)
        self.assertFalse(p.snapshot()['can_steer'])

    def test_text_patches_do_not_change_watch_snapshot_and_gap_invalidates(self):
        p = projection()
        before = p.snapshot()
        p.accept(dict(type='patches', baseRevision=1, revision=2, patches=[
            dict(op='replace', path=['turnHistory', 'history', 'entitiesByKey', 'latest', 'items', 0, 'text'], value='delta')]))
        self.assertEqual(p.snapshot(), before)
        with self.assertRaises(ValueError):
            p.accept(dict(type='patches', baseRevision=0, revision=3, patches=[]))
        self.assertFalse(p.valid)

    def test_native_decisions_and_cross_thread_request_filter(self):
        p = projection(requests=[command(), command('wrong')])
        pending = p.pending()
        self.assertEqual(len(pending), 1)
        self.assertEqual([x['value'] for x in pending[0][0]['decisions']], ['accept', 'cancel'])
        self.assertEqual(pending[0][1]['id'], 7)
        p.accept(dict(type='patches', baseRevision=1, revision=2,
            patches=[dict(op='remove', path=['requests', 0])]))
        self.assertEqual(p.pending(), [])

    def test_file_change_requires_review_content_and_unsupported_stays_manual(self):
        request = dict(id=0, method='item/fileChange/requestApproval', params=dict(threadId='pc1', turnId='turn1', itemId='file1'))
        p = projection(requests=[request])
        self.assertFalse(p.pending()[0][0]['supported'])
        p.remember_item(dict(id='file1', type='fileChange', changes=[dict(path='a.txt', diff='+hello')]))
        self.assertTrue(p.pending()[0][0]['supported'])
        p.requests = [dict(id=9, method='item/permissions/requestApproval', params=dict(threadId='pc1', turnId='turn1'))]
        self.assertFalse(p.pending()[0][0]['supported'])

    def test_patch_arrays_and_protocol_owner_guards(self):
        rows = []
        rows = patch_value(rows, dict(op='add', path=[0], value='one'))
        self.assertEqual(rows, ['one'])
        rows = patch_value(rows, dict(op='replace', path=[], value=[]))
        self.assertEqual(rows, [])
        ipc = DesktopIPC()
        try:
            ipc.connected = True
            ipc.sessions['pc1'] = projection()
            message = dict(type='broadcast', method='thread-stream-state-changed', version=11,
                sourceClientId='other', params=dict(hostId='local', conversationId='pc1',
                    change=dict(type='snapshot', revision=2, conversationState=state())))
            ipc.handle(message)
            self.assertEqual(ipc.sessions['pc1'].revision, 1)
            message.update(sourceClientId='owner1', version=99)
            ipc.handle(message)
            self.assertFalse(ipc.sessions['pc1'].valid)
            self.assertTrue(ipc.peek('pc1')['readonly'])
        finally:
            ipc.close()

    def test_automatic_review_is_not_pushed_as_a_human_decision(self):
        raw = state(requests=[command()])
        raw['currentPermissions']['approvalsReviewer'] = 'auto_review'
        p = Projection('owner')
        p.accept(dict(type='snapshot', revision=1, conversationState=raw))
        self.assertEqual(p.pending(), [])
        p.accept(dict(type='patches', baseRevision=1, revision=2, patches=[
            dict(op='replace', path=['currentPermissions', 'approvalsReviewer'], value='user')]))
        self.assertEqual(len(p.pending()), 1)

    def test_secret_or_malformed_questions_stay_on_desktop(self):
        question = dict(id='q1', question='请选择', options=[dict(label='继续')])
        request = dict(id=12, method='item/tool/requestUserInput', params=dict(
            threadId='pc1', turnId='turn1', questions=[question]))
        p = projection(requests=[request])
        self.assertTrue(p.pending()[0][0]['supported'])
        question['isSecret'] = True
        self.assertFalse(p.pending()[0][0]['supported'])
        question['isSecret'] = False
        question['options'] = ['not an option object']
        self.assertFalse(p.pending()[0][0]['supported'])


class SharedTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.legacy = Assistant(Path(self.temp.name) / 'assistant-state.json')
        self.legacy.store.register('old-watch', '旧对话')
        self.legacy.changed(thread_id='old-watch', state='completed', reply='旧结果')
        self.desktop = FakeDesktop()
        self.opened = []
        self.a = SharedAssistant(self.legacy, desktop=self.desktop, open_thread=self.opened.append)

    def tearDown(self):
        self.temp.cleanup()

    def test_preferences_persist_independently_and_old_state_is_untouched(self):
        before = self.legacy.snapshot()
        self.assertTrue(self.a.preferences()['desktop_attention_enabled'])
        self.a.update_preferences(False)
        restored = SharedAssistant(self.legacy, desktop=self.desktop)
        self.assertFalse(restored.preferences()['desktop_attention_enabled'])
        self.assertEqual(self.legacy.snapshot(), before)
        with self.assertRaises(ValueError):
            self.a.update_preferences('false')

    def test_all_local_pending_conversations_are_paged_and_muting_keeps_manual_access(self):
        self.desktop.sessions = {str(i): projection(str(i), requests=[command(str(i))]) for i in range(45)}
        self.assertEqual(len(self.a.attention()['items']), 20)
        self.assertEqual(self.a.attention()['next_cursor'], 20)
        self.assertEqual(self.a.attention(40)['total'], 45)
        self.a.update_preferences(False)
        value = self.a.snapshot(True, '0')
        self.assertEqual(value['attention_count'], 45)
        self.assertEqual(len(value['pending']), 1)
        self.assertFalse(value['desktop_attention_enabled'])

    def test_send_uses_native_owner_preserves_settings_and_deduplicates(self):
        with patch.object(self.legacy, 'start_turn') as old_runner:
            value = self.a.send('接着聊', 'r1', False, 'pc1')
            self.assertEqual(value['accepted_thread_id'], 'pc1')
            again = self.a.send('接着聊', 'r1', False, 'pc1')
            self.assertEqual(again['accepted_thread_id'], 'pc1')
            self.assertEqual(len(self.desktop.calls), 1)
            old_runner.assert_not_called()
        method, params, owner = self.desktop.calls[0]
        self.assertEqual(method, 'thread-follower-start-turn')
        self.assertEqual(owner, 'owner1')
        request = params['turnStart']['request']
        self.assertNotIn('cwd', request)
        self.assertNotIn('model', request)
        self.assertNotIn('approvalsReviewer', request)
        self.assertEqual(request['clientUserMessageId'], 'r1')
        with self.assertRaises(ValueError):
            self.a.send('different', 'r1', False, 'pc1')

    def test_uncertain_send_is_never_replayed_even_after_restart(self):
        self.desktop.fail = True
        with self.assertRaises(DeliveryUnknown):
            self.a.send('消息', 'unknown1', False, 'pc1')
        self.desktop.fail = False
        restored = SharedAssistant(self.legacy, desktop=self.desktop)
        with self.assertRaises(DeliveryUnknown):
            restored.send('消息', 'unknown1', False, 'pc1')
        self.assertEqual(len(self.desktop.calls), 1)

    def test_closed_client_does_not_open_or_start_another_executor(self):
        self.desktop.connected = False
        with patch.object(self.legacy, 'ensure') as ensure:
            with self.assertRaises(DesktopUnavailable):
                self.a.send('任务', 'closed1', True, '')
            ensure.assert_not_called()
        self.assertEqual(self.opened, [])
        self.assertEqual(self.desktop.calls, [])

    def test_answer_first_wins_and_target_checks_precede_native_write(self):
        self.desktop.sessions['pc1'] = projection(requests=[command()])
        token = self.a.snapshot(True, 'pc1')['pending'][0]['id']
        with self.assertRaises(RuntimeError):
            self.a.answer(token, 'accept', thread_id='pc1', turn_id='wrong')
        self.assertEqual(self.desktop.calls, [])
        self.a.answer(token, 'cancel', thread_id='pc1', turn_id='turn1')
        self.assertEqual(self.desktop.calls[0][1]['decision'], 'cancel')
        with self.assertRaises(RuntimeError):
            self.a.answer(token, 'accept', thread_id='pc1', turn_id='turn1')
        self.assertEqual(len(self.desktop.calls), 1)

    def test_unsafe_unbound_follower_steer_is_rejected(self):
        self.desktop.sessions['pc1'] = projection()
        with self.assertRaises(DesktopUnavailable):
            self.a.steer('补充', 'steer1', 'pc1', 'turn1')
        self.assertEqual(self.desktop.calls, [])

    def test_stop_is_bound_to_the_expected_native_turn(self):
        self.desktop.sessions['pc1'] = projection()
        with self.assertRaises(RuntimeError):
            self.a.stop('pc1', 'stale')
        self.a.stop('pc1', 'turn1')
        self.assertEqual(self.desktop.calls[0][1], dict(conversationId='pc1', mode='user-stop', expectedTurnId='turn1'))

    def test_text_deltas_do_not_advance_bridge_revision(self):
        self.desktop.sessions['pc1'] = projection()
        self.a.publish()
        before = self.a.revision
        self.desktop.sessions['pc1'].accept(dict(type='patches', baseRevision=1, revision=2,
            patches=[dict(op='replace', path=['turnHistory', 'history', 'entitiesByKey', 'latest', 'items', 0, 'text'], value='x')]))
        self.a.publish()
        self.assertEqual(self.a.revision, before)

    def test_old_receipt_is_acknowledged_without_reexecution(self):
        import hashlib
        fingerprint = hashlib.sha256(json.dumps(['原请求', False, 'old-watch'], ensure_ascii=False).encode()).hexdigest()
        self.a.store.record('v04-request', dict(fingerprint=fingerprint, thread_id='old-watch'))
        self.a.send('原请求', 'v04-request', False, 'old-watch')
        self.assertEqual(self.desktop.calls, [])

    def test_empty_thread_writer_exits_before_desktop_handoff(self):
        calls = []
        class Manager:
            def __enter__(self):
                calls.append('enter')
                return self
            def call(self, method, params):
                calls.append(method)
                return dict(thread=dict(id='empty1')) if method in ('thread/start', 'thread/read') else {}
            def __exit__(self, *args):
                calls.append('exited')
        self.a.record_factory = Manager
        with patch.object(self.legacy, 'ensure') as ensure, patch.object(self.legacy, 'call',
                return_value=dict(thread=dict(id='persistent-writer'))) as rpc:
            self.assertEqual(self.a.create_empty('新对话'), 'empty1')
            ensure.assert_not_called()
            rpc.assert_not_called()
        self.assertEqual(calls, ['enter', 'thread/start', 'thread/name/set', 'thread/unsubscribe', 'thread/read', 'exited'])
        self.assertEqual(self.opened, [])

    def test_failed_record_creation_still_exits_without_executing_a_turn(self):
        calls = []
        class Manager:
            def __enter__(self):return self
            def call(self, method, params):
                calls.append(method)
                raise RuntimeError('record creation failed')
            def __exit__(self, *args):calls.append('exited')
        self.a.record_factory = Manager
        with patch.object(self.legacy, 'ensure') as ensure:
            with self.assertRaisesRegex(RuntimeError, 'record creation failed'):
                self.a.create_empty('新对话')
            ensure.assert_not_called()
        self.assertEqual(calls, ['thread/start', 'exited'])
        self.assertEqual(self.desktop.calls, [])

    def test_failed_handoff_retry_reuses_the_same_empty_record(self):
        with patch.object(self.a, 'create_empty', return_value='pc1') as create, patch.object(
                self.a, 'activate', side_effect=[DesktopUnavailable('handoff delayed'), {}]):
            with self.assertRaises(DesktopUnavailable):
                self.a.send('新问题', 'new-retry', True, '')
            value = self.a.send('新问题', 'new-retry', True, '')
            self.assertEqual(value['accepted_thread_id'], 'pc1')
            create.assert_called_once()
            self.assertEqual(len(self.desktop.calls), 1)

    def test_question_response_keeps_original_options_and_rejects_missing_values(self):
        q = dict(id='q', question='选哪个', isOther=False, options=[dict(label='原选项')])
        req = dict(id=8, method='item/tool/requestUserInput', params=dict(threadId='pc1', turnId='turn1', questions=[q]))
        self.desktop.sessions['pc1'] = projection(requests=[req])
        token = self.a.snapshot(True, 'pc1')['pending'][0]['id']
        for answer in [None, {}, {'q': None}, {'q': '其他'}, {'q': 'x' * 4001}]:
            with self.assertRaises(ValueError):
                self.a.answer(token, answers=answer, thread_id='pc1', turn_id='turn1')
        self.assertEqual(self.desktop.calls, [])
        self.a.answer(token, answers={'q': '原选项'}, thread_id='pc1', turn_id='turn1')
        self.assertEqual(self.desktop.calls[0][1]['response'], dict(answers={'q': dict(answers=['原选项'])}))

    def test_shared_http_preferences_attention_and_target_validation(self):
        from http.server import ThreadingHTTPServer
        from urllib.request import Request, urlopen
        from urllib.error import HTTPError
        import pc_listener
        server = ThreadingHTTPServer(('127.0.0.1', 0), pc_listener.Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        base = 'http://127.0.0.1:' + str(server.server_port)
        def get(path):
            return json.load(urlopen(base + path + ('&' if '?' in path else '?') + 't=' + pc_listener.TOKEN))
        def post(path, payload, auth=True):
            return json.load(urlopen(Request(base + path, data=json.dumps(payload).encode(), headers={
                'Authorization': 'Bearer ' + (pc_listener.TOKEN if auth else 'wrong')})))
        try:
            with patch.object(pc_listener, 'assistant', self.a):
                self.assertTrue(get('/assistant/preferences')['desktop_attention_enabled'])
                with self.assertRaises(HTTPError) as error:
                    post('/assistant/preferences', {'desktop_attention_enabled': False}, False)
                self.assertEqual(error.exception.code, 403)
                self.assertFalse(post('/assistant/preferences', {'desktop_attention_enabled': False})['desktop_attention_enabled'])
                self.assertFalse(get('/assistant/state?thread_id=pc1&compact=1')['readonly'])
                self.assertEqual(get('/assistant/events?after=-1&thread_id=pc1&compact=1')['thread_id'], 'pc1')
                self.assertEqual(get('/assistant/attention')['total'], 0)
                for path, payload in [('/assistant/preferences', {'desktop_attention_enabled': 'false'}),
                        ('/assistant/activate', {}), ('/assistant/send', {'prompt': 'test', 'request_id': 'a', 'new': True, 'thread_id': 'pc1'})]:
                    with self.assertRaises(HTTPError) as error:
                        post(path, payload)
                    self.assertEqual(error.exception.code, 400)
                with self.assertRaises(HTTPError) as error:
                    get('/assistant/attention?cursor=-1')
                self.assertEqual(error.exception.code, 400)
                with self.assertRaises(HTTPError) as error:
                    post('/assistant/stop', dict(thread_id='pc1', turn_id='stale'))
                self.assertEqual(error.exception.code, 409)
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

    def test_shared_backend_persistent_opt_in_and_environment_override(self):
        import os
        import pc_listener
        with patch.dict(os.environ, {}, clear=True), patch.object(pc_listener, 'env', {'DESKTOP_SHARED': '1'}):
            self.assertIsInstance(pc_listener.configured_assistant(), SharedAssistant)
            with patch.dict(os.environ, {'WATCHREMOTE_DESKTOP_SHARED': '0'}):
                self.assertIs(pc_listener.configured_assistant(), pc_listener.legacy_assistant)
        with patch.dict(os.environ, {}, clear=True), patch.object(pc_listener, 'env', {}):
            self.assertIs(pc_listener.configured_assistant(), pc_listener.legacy_assistant)

    def test_unreleased_shared_backend_requires_explicit_opt_in(self):
        import os
        import pc_listener
        self.assertTrue(callable(getattr(pc_listener, 'configured_assistant', None)),
            '未发布的共享后端必须经过显式开关选择')
        with patch.dict(os.environ, {'WATCHREMOTE_DESKTOP_SHARED': '0'}):
            self.assertIs(pc_listener.configured_assistant(), pc_listener.legacy_assistant)
        with patch.dict(os.environ, {'WATCHREMOTE_DESKTOP_SHARED': '1'}):
            self.assertIsInstance(pc_listener.configured_assistant(), SharedAssistant)


if __name__ == '__main__':
    unittest.main()
