import hashlib
import threading
import time
import unittest
from unittest.mock import patch

from desktop_ipc import DesktopIPC, DesktopUnavailable, Projection
import test_shared_assistant as fixtures
from test_shared_assistant import projection, state, command


PREFIX = ['turnHistory', 'history', 'entitiesByKey', 'latest']


def change(p, *patches):
    p.accept(dict(type='patches', baseRevision=p.revision, revision=p.revision + 1,
        patches=list(patches)))


def message(text, ident='answer', phase='final_answer'):
    return dict(type='agentMessage', id=ident, text=text, phase=phase)


class ReplyPreviewTests(unittest.TestCase):
    def test_bound_preview_tracks_text_replacement_completion_and_bounds(self):
        p = projection()
        self.assertIsNone(p.snapshot().get('reply_preview'))
        change(p, dict(op='add', path=PREFIX + ['items', 0], value=message('首段')))
        first = p.snapshot()['reply_preview']
        self.assertEqual(first, dict(thread_id='pc1', turn_id='turn1', item_id='answer',
            text='首段', version=first['version'], complete=False, clipped=False))
        change(p, dict(op='replace', path=PREFIX + ['items', 0, 'text'], value='新正文' * 400))
        second = p.snapshot()['reply_preview']
        self.assertEqual(second['text'], ('新正文' * 400)[:1000])
        self.assertTrue(second['clipped'])
        self.assertGreater(second['version'], first['version'])
        change(p, dict(op='replace', path=PREFIX + ['status'], value='completed'))
        self.assertTrue(p.snapshot()['reply_preview']['complete'])
        self.assertEqual(p.reply, '新正文' * 400)

    def test_replacement_removal_new_turn_and_gap_clear_stale_body(self):
        p = projection()
        change(p, dict(op='add', path=PREFIX + ['items', 0], value=message('old')))
        change(p, dict(op='replace', path=PREFIX + ['items', 0], value=message('new', 'new-id')))
        self.assertEqual(p.snapshot()['reply_preview']['item_id'], 'new-id')
        change(p, dict(op='remove', path=PREFIX + ['items', 0]))
        self.assertIsNone(p.snapshot()['reply_preview'])
        change(p, dict(op='add', path=PREFIX + ['items', 0], value=message('old turn')))
        change(p, dict(op='add', path=PREFIX[:-1] + ['second'], value=dict(
            turnId='turn2', turnStartedAtMs=200, status='inProgress', params={}, items=[])))
        self.assertIsNone(p.snapshot()['reply_preview'])
        self.assertEqual(p.reply, '')
        change(p, dict(op='replace', path=PREFIX + ['items', 0, 'text'], value='late old body'))
        self.assertIsNone(p.snapshot()['reply_preview'])
        with self.assertRaises(ValueError):
            p.accept(dict(type='patches', baseRevision=-1, revision=99, patches=[]))
        self.assertIsNone(p.snapshot()['reply_preview'])
        self.assertEqual(p.reply, '')

    def test_snapshot_and_container_replacement_rebuild_only_latest_body(self):
        raw = state()
        raw['turnHistory']['history']['entitiesByKey']['latest']['items'] = [message('body')]
        p = Projection('owner')
        p.accept(dict(type='snapshot', revision=10, conversationState=raw))
        first = p.snapshot()['reply_preview']
        change(p, dict(op='replace', path=PREFIX + ['items'], value=[message('replacement')]))
        self.assertEqual(p.snapshot()['reply_preview']['text'], 'replacement')
        self.assertGreater(p.snapshot()['reply_preview']['version'], first['version'])
        change(p, dict(op='replace', path=['turnHistory'], value=dict(history=dict(entitiesByKey={}))))
        self.assertIsNone(p.snapshot()['reply_preview'])
        self.assertEqual(p.reply, '')
        p.accept(dict(type='snapshot', revision=20, conversationState=state()))
        self.assertIsNone(p.snapshot()['reply_preview'])

    def test_reasoning_is_never_exposed_and_commentary_is_public_body(self):
        p = projection()
        change(p, dict(op='add', path=PREFIX + ['items', 0], value=dict(
            type='reasoning', id='private', text='secret chain', summary='secret summary')))
        self.assertIsNone(p.snapshot()['reply_preview'])
        change(p, dict(op='add', path=PREFIX + ['items', 1], value=message('我先检查数据', phase='commentary')))
        self.assertEqual(p.snapshot()['reply_preview']['text'], '我先检查数据')
        self.assertNotIn('secret', str(p.snapshot()))
        change(p, dict(op='replace', path=PREFIX + ['items', 1, 'type'], value='reasoning'))
        self.assertIsNone(p.snapshot()['reply_preview'])

    def test_empty_final_message_replaces_commentary_before_completion(self):
        p = projection()
        change(p, dict(op='add', path=PREFIX + ['items', 0], value=message('public progress', phase='commentary')))
        change(p, dict(op='add', path=PREFIX + ['items', 1], value=message('', 'final')))
        self.assertIsNone(p.snapshot()['reply_preview'])
        change(p, dict(op='replace', path=PREFIX + ['status'], value='completed'))
        self.assertIsNone(p.snapshot()['reply_preview'])
        self.assertEqual(p.reply, '')

    def test_same_prefix_long_body_still_advances_version_and_item_identity_is_required(self):
        p = projection()
        change(p, dict(op='add', path=PREFIX + ['items', 0], value=message('x' * 21000)))
        first = p.snapshot()['reply_preview']
        change(p, dict(op='replace', path=PREFIX + ['items', 0, 'text'], value='x' * 21001))
        self.assertGreater(p.snapshot()['reply_preview']['version'], first['version'])
        change(p, dict(op='remove', path=PREFIX + ['items', 0, 'id']))
        self.assertIsNone(p.snapshot()['reply_preview'])

    def test_repeated_snapshot_does_not_create_another_version(self):
        raw = state()
        raw['turnHistory']['history']['entitiesByKey']['latest']['items'] = [message('body')]
        p = Projection('owner')
        p.accept(dict(type='snapshot', revision=10, conversationState=raw))
        before = p.snapshot()['reply_preview']
        p.accept(dict(type='snapshot', revision=11, conversationState=raw))
        self.assertEqual(p.snapshot()['reply_preview'], before)

    def test_private_phase_and_finished_commentary_never_become_a_final_answer(self):
        p = projection()
        change(p, dict(op='add', path=PREFIX + ['items', 0], value=message('private analysis', phase='analysis')))
        self.assertIsNone(p.snapshot()['reply_preview'])
        self.assertEqual(p.reply, '')
        change(p, dict(op='replace', path=PREFIX + ['items', 0], value=message('public progress', phase='commentary')))
        change(p, dict(op='replace', path=PREFIX + ['status'], value='completed'))
        self.assertIsNone(p.snapshot()['reply_preview'])
        self.assertEqual(p.snapshot()['state'], 'completed')
        self.assertEqual(p.reply, '')

    def test_canonical_metadata_patches_preserve_body_and_request_correlation(self):
        p = projection()
        change(p, dict(op='add', path=PREFIX + ['items', 0], value=message('body')))
        before = p.snapshot()['reply_preview']
        change(p, dict(op='replace', path=['turnHistory', 'kind'], value='canonical'),
            dict(op='replace', path=['turnHistory', 'history', 'keys'], value=['latest']),
            dict(op='replace', path=PREFIX + ['params', 'clientUserMessageId'], value='request-id'))
        self.assertEqual(p.snapshot()['reply_preview'], before)
        self.assertEqual(p.snapshot()['request_id'], 'request-id')

    def test_revision_gap_with_body_clears_and_fresh_snapshot_recovers(self):
        p = projection()
        change(p, dict(op='add', path=PREFIX + ['items', 0], value=message('stale')))
        version = p.snapshot()['reply_preview']['version']
        with self.assertRaises(ValueError):
            p.accept(dict(type='patches', baseRevision=0, revision=4, patches=[]))
        self.assertIsNone(p.snapshot()['reply_preview'])
        self.assertEqual(p.reply, '')
        raw = state()
        raw['turnHistory']['history']['entitiesByKey']['latest']['items'] = [message('recovered')]
        p.accept(dict(type='snapshot', revision=5, conversationState=raw))
        self.assertEqual(p.snapshot()['reply_preview']['text'], 'recovered')
        self.assertGreater(p.snapshot()['reply_preview']['version'], version)
        change(p, dict(op='remove', path=PREFIX))
        self.assertIsNone(p.snapshot()['reply_preview'])

    def test_current_turn_failure_interruption_and_disconnect(self):
        for status in ['failed', 'interrupted']:
            p = projection()
            change(p, dict(op='add', path=PREFIX + ['items', 0], value=message('partial')))
            change(p, dict(op='replace', path=PREFIX + ['status'], value=status))
            self.assertTrue(p.snapshot()['reply_preview']['complete'])
            self.assertIsNone(p.snapshot(False)['reply_preview'])


class StartOrderingTests(unittest.TestCase):
    def test_turn_arrives_before_runtime_active_is_starting_until_real_terminal(self):
        p = projection(status='idle')
        change(p, dict(op='add', path=PREFIX[:-1] + ['second'], value=dict(
            turnId='turn2', turnStartedAtMs=200, status='inProgress', params={}, items=[])))
        pending = p.snapshot()
        self.assertEqual(pending['state'], 'starting')
        self.assertFalse(pending['can_send'])
        self.assertEqual(pending['summary'], '')
        self.assertFalse(pending['needs_attention'])
        change(p, dict(op='replace', path=['threadRuntimeStatus', 'type'], value='active'))
        self.assertEqual(p.snapshot()['state'], 'running')
        self.assertFalse(p.snapshot()['can_send'])
        second = PREFIX[:-1] + ['second']
        change(p, dict(op='add', path=second + ['items', 0], value=message('received 7')),
            dict(op='replace', path=second + ['status'], value='completed'))
        self.assertEqual(p.snapshot()['state'], 'completed')
        self.assertTrue(p.snapshot()['reply_preview']['complete'])
        self.assertFalse(p.snapshot()['can_send'])
        change(p, dict(op='replace', path=['threadRuntimeStatus', 'type'], value='idle'))
        self.assertTrue(p.snapshot()['can_send'])

    def test_owner_replacement_preserves_only_accepted_start_reservation(self):
        ipc = DesktopIPC()
        ipc.connected = True
        old = projection(status='idle')
        old.expect_turn('expected', 'accepted', 'turn1', 'task')
        ipc.sessions['pc1'] = old
        def snapshot_from_new_owner(ident, owner):
            ipc.sessions[ident].accept(dict(type='snapshot', revision=1,
                conversationState=state(status='idle')))
        try:
            with patch.object(ipc, 'request', return_value=dict(handledByClientId='owner2')), patch.object(
                    ipc, 'follow', side_effect=snapshot_from_new_owner):
                current = ipc.discover('pc1')
            self.assertIsNot(current, old)
            self.assertEqual(current.owner, 'owner2')
            self.assertEqual(current.snapshot()['state'], 'starting')
            self.assertEqual(current.snapshot()['turn_id'], 'expected')
            self.assertFalse(current.snapshot()['can_send'])
            self.assertIsNone(current.snapshot()['reply_preview'])
        finally:
            ipc.close()

    def test_stale_inprogress_snapshot_and_owner_loss_never_offer_a_new_send(self):
        raw = state(status='idle')
        raw['turnHistory']['history']['entitiesByKey']['latest']['status'] = 'inProgress'
        p = Projection('owner')
        p.accept(dict(type='snapshot', revision=1, conversationState=raw))
        self.assertEqual(p.snapshot()['state'], 'starting')
        self.assertFalse(p.snapshot()['can_send'])
        self.assertFalse(p.snapshot()['needs_attention'])
        p.valid = False
        self.assertTrue(p.snapshot()['readonly'])
        self.assertFalse(p.snapshot()['can_send'])
        self.assertIsNone(p.snapshot()['reply_preview'])
        self.assertTrue(p.snapshot(False)['readonly'])
        self.assertFalse(p.snapshot(False)['can_send'])
        change_status = state(status='idle')
        change_status['turnHistory']['history']['entitiesByKey']['latest']['status'] = 'interrupted'
        p.accept(dict(type='snapshot', revision=2, conversationState=change_status))
        self.assertEqual(p.snapshot()['state'], 'interrupted')
        self.assertTrue(p.snapshot()['can_send'])


class RetryStageTests(unittest.TestCase):
    @staticmethod
    def retry():
        return dict(type='error', message='Reconnecting secret endpoint', willRetry=True,
            errorInfo=dict(responseStreamDisconnected=dict(httpStatusCode=None)),
            additionalDetails='private token request timed out')

    def assert_retry(self, p):
        value = p.snapshot()
        self.assertEqual(value['message'], '模型连接重试中')
        self.assertEqual(value['state'], 'running')
        self.assertFalse(value['needs_attention'])
        self.assertEqual(value['pending_count'], 0)
        self.assertIsNone(value['reply_preview'])
        self.assertNotIn('secret', str(value))
        self.assertNotIn('private token', str(value))

    def test_snapshot_retry_stage_keeps_only_type_and_boolean(self):
        raw = state()
        raw['turnHistory']['history']['entitiesByKey']['latest']['items'] = [self.retry()]
        p = Projection('owner')
        p.accept(dict(type='snapshot', revision=1, conversationState=raw))
        self.assert_retry(p)
        self.assertEqual(p.message_items, [dict(type='error', willRetry=True)])

    def test_patch_retry_clears_on_resumed_reasoning_and_agent_output(self):
        p = projection()
        change(p, dict(op='add', path=PREFIX + ['items', 0], value=dict(type='reasoning', id='reasoning')))
        change(p, dict(op='add', path=PREFIX + ['items', 1], value=self.retry()))
        self.assert_retry(p)
        # Native output may resume by patching an existing earlier reasoning item.
        change(p, dict(op='add', path=PREFIX + ['items', 0, 'summary'], value=['private chain']))
        self.assertEqual(p.snapshot()['message'], '正在思考')
        self.assertNotIn('private chain', str(p.message_items))
        # Unrelated metadata must not replay the older retry item as current work.
        change(p, dict(op='replace', path=['title'], value='标题'))
        self.assertEqual(p.snapshot()['message'], '正在思考')
        change(p, dict(op='add', path=PREFIX + ['items', 2], value=self.retry()))
        self.assert_retry(p)
        change(p, dict(op='add', path=PREFIX + ['items', 3], value=message('received')))
        self.assertEqual(p.snapshot()['message'], '正在整理回复')
        self.assertEqual(p.snapshot()['reply_preview']['text'], 'received')

    def test_retry_boolean_patch_and_new_turn_clear_stage_without_alert(self):
        p = projection()
        item = self.retry()
        item['willRetry'] = False
        change(p, dict(op='add', path=PREFIX + ['items', 0], value=item))
        self.assertNotEqual(p.snapshot()['message'], '模型连接重试中')
        change(p, dict(op='replace', path=PREFIX + ['items', 0, 'willRetry'], value=True))
        self.assert_retry(p)
        change(p, dict(op='replace', path=PREFIX + ['items', 0, 'willRetry'], value=False))
        self.assertEqual(p.snapshot()['message'], '电脑正在处理')
        change(p, dict(op='replace', path=PREFIX + ['items', 0, 'willRetry'], value=True))
        self.assert_retry(p)
        change(p, dict(op='add', path=PREFIX[:-1] + ['second'], value=dict(
            turnId='turn2', turnStartedAtMs=200, status='inProgress', params={}, items=[])))
        self.assertEqual(p.snapshot()['message'], '电脑正在处理')
        self.assertFalse(p.snapshot()['needs_attention'])


class StreamingTests(unittest.TestCase):
    setUp = fixtures.SharedTests.setUp
    tearDown = fixtures.SharedTests.tearDown
    def test_invalidated_idle_owner_never_accepts_another_native_write(self):
        self.desktop.sessions['pc1'].valid = False
        with self.assertRaises(DesktopUnavailable):
            self.a.send('another task', 'invalid-owner', False, 'pc1')
        self.assertEqual(self.desktop.calls, [])

    def test_initial_start_ordering_blocks_another_native_write(self):
        p = self.desktop.sessions['pc1'] = projection(status='idle')
        change(p, dict(op='replace', path=PREFIX + ['status'], value='inProgress'))
        with self.assertRaises(DesktopUnavailable):
            self.a.send('another task', 'must-not-send', False, 'pc1')
        self.assertEqual(self.desktop.calls, [])

    def test_native_ack_starting_response_cannot_offer_send_or_stale_preview(self):
        p = self.desktop.sessions['pc1']
        change(p, dict(op='add', path=PREFIX + ['items', 0], value=message('old final')))
        result = self.a.send('new task', 'start-ack', False, 'pc1')
        self.assertEqual(result['state'], 'starting')
        self.assertFalse(result['can_send'])
        self.assertIsNone(result['reply_preview'])
        self.assertEqual(result['summary'], '')

    def test_accepted_ack_remains_starting_until_native_turn_arrives(self):
        p = self.desktop.sessions['pc1']
        change(p, dict(op='add', path=PREFIX + ['items', 0], value=message('old final')))
        self.a.send('new task', 'accepted-start', False, 'pc1')
        pending = self.a.snapshot(True, 'pc1')
        self.assertEqual(pending['state'], 'starting')
        self.assertEqual(pending['turn_id'], 'new-turn')
        self.assertEqual(pending['request_id'], 'accepted-start')
        self.assertFalse(pending['can_send'])
        self.assertIsNone(pending['reply_preview'])
        self.assertEqual(pending['summary'], '')
        # A retry of the accepted request is acknowledged, while a second task
        # cannot pass the native guard during the delayed follower snapshot.
        self.a.send('new task', 'accepted-start', False, 'pc1')
        with self.assertRaises(DesktopUnavailable):
            self.a.send('different task', 'different-start', False, 'pc1')
        self.assertEqual(len(self.desktop.calls), 1)
        change(p, dict(op='add', path=PREFIX[:-1] + ['second'], value=dict(
            turnId='new-turn', turnStartedAtMs=200, status='inProgress',
            params=dict(clientUserMessageId='accepted-start'), items=[])))
        self.assertEqual(self.a.snapshot(True, 'pc1')['state'], 'starting')
        second = PREFIX[:-1] + ['second']
        change(p, dict(op='add', path=second + ['items', 0], value=message('new final')),
            dict(op='replace', path=second + ['status'], value='completed'))
        final = self.a.snapshot(True, 'pc1')
        self.assertEqual(final['state'], 'completed')
        self.assertTrue(final['can_send'])
        self.assertEqual(final['reply_preview']['text'], 'new final')

    def test_accepted_start_owner_loss_is_readonly_until_authoritative_turn(self):
        p = self.desktop.sessions['pc1']
        self.a.send('new task', 'lost-owner-start', False, 'pc1')
        p.valid = False
        self.desktop.connected = False
        lost = self.a.snapshot(True, 'pc1')
        self.assertTrue(lost['readonly'])
        self.assertFalse(lost['can_send'])
        self.assertIsNone(lost['reply_preview'])
        # A stale historical snapshot after reconnect cannot complete the new turn.
        p.accept(dict(type='snapshot', revision=10, conversationState=state(status='idle')))
        self.desktop.connected = True
        self.assertEqual(self.a.snapshot(True, 'pc1')['state'], 'starting')
        older = state(status='idle')
        old_turn = older['turnHistory']['history']['entitiesByKey']['latest']
        old_turn.update(turnId='older-history', turnStartedAtMs=50)
        p.accept(dict(type='snapshot', revision=11, conversationState=older))
        self.assertEqual(self.a.snapshot(True, 'pc1')['state'], 'starting')
        self.assertFalse(self.a.snapshot(True, 'pc1')['can_send'])
        raw = state(status='idle')
        turn = raw['turnHistory']['history']['entitiesByKey']['latest']
        turn.update(turnId='new-turn', status='completed', items=[message('completed')])
        p.accept(dict(type='snapshot', revision=12, conversationState=raw))
        final = self.a.snapshot(True, 'pc1')
        self.assertEqual(final['state'], 'completed')
        self.assertTrue(final['can_send'])
        self.assertEqual(final['reply_preview']['turn_id'], 'new-turn')

    def test_owner_replacement_during_ack_cannot_reenable_stale_history(self):
        request = self.desktop.request
        def new_owner_before_ack(*args, **kwargs):
            new = projection(status='idle')
            new.owner = 'owner2'
            self.desktop.sessions['pc1'] = new
            return request(*args, **kwargs)
        with patch.object(self.desktop, 'request', side_effect=new_owner_before_ack):
            self.a.send('task', 'owner-change-start', False, 'pc1')
        current = self.a.snapshot(True, 'pc1')
        self.assertEqual(current['state'], 'starting')
        self.assertEqual(current['turn_id'], 'new-turn')
        self.assertFalse(current['can_send'])
        self.assertIsNone(current['reply_preview'])
        with self.assertRaises(DesktopUnavailable):
            self.a.send('another task', 'owner-change-second', False, 'pc1')
        self.assertEqual(len(self.desktop.calls), 1)

    def test_native_completion_before_ack_is_not_overwritten_by_start_marker(self):
        p = self.desktop.sessions['pc1']
        request = self.desktop.request
        def completed_before_ack(*args, **kwargs):
            change(p, dict(op='add', path=PREFIX[:-1] + ['second'], value=dict(
                turnId='new-turn', turnStartedAtMs=200, status='completed', params={},
                items=[message('already completed')])) )
            return request(*args, **kwargs)
        with patch.object(self.desktop, 'request', side_effect=completed_before_ack):
            result = self.a.send('fast task', 'fast-start', False, 'pc1')
        self.assertEqual(result['state'], 'completed')
        self.assertTrue(result['can_send'])
        self.assertTrue(result['reply_preview']['complete'])

    def test_terminal_status_does_not_allow_send_while_native_runtime_is_active(self):
        p = self.desktop.sessions['pc1'] = projection()
        change(p, dict(op='replace', path=PREFIX + ['status'], value='completed'))
        with self.assertRaises(DesktopUnavailable):
            self.a.send('task', 'active-native-guard', False, 'pc1')
        self.assertEqual(self.desktop.calls, [])

    def test_pending_preview_flushes_at_deadline_without_another_native_event(self):
        p = self.desktop.sessions['pc1'] = projection()
        change(p, dict(op='add', path=PREFIX + ['items', 0], value=message('first')))
        self.a.publish()
        change(p, dict(op='replace', path=PREFIX + ['items', 0, 'text'], value='second'))
        self.a.publish()
        self.assertIsNotNone(self.a.next_preview_at)
        # Accelerate the real deadline while exercising the actual event loop and
        # condition notification, without starting desktop discovery or an executor.
        self.a.preview_published_at['pc1'] = time.monotonic() - .98
        self.a.publish()
        before = self.a.revision
        self.a.running = True
        worker = threading.Thread(target=self.a.publish_loop, daemon=True)
        worker.start()
        try:
            with self.a.cv:
                self.assertTrue(self.a.cv.wait_for(lambda: self.a.revision > before, timeout=1))
            self.assertEqual(self.a.snapshot(True, 'pc1')['reply_preview']['text'], 'second')
        finally:
            self.a.running = False
            self.a.changed_event.set()
            worker.join(1)
            self.assertFalse(worker.is_alive())

    def test_longpoll_wakes_for_first_body_with_exact_binding(self):
        p = self.desktop.sessions['pc1'] = projection()
        self.a.publish()
        before = self.a.revision
        started = threading.Event()
        result = []
        def poll():
            started.set()
            result.append(self.a.events(before, True, 'pc1'))
        worker = threading.Thread(target=poll, daemon=True)
        worker.start()
        self.assertTrue(started.wait(1))
        change(p, dict(op='add', path=PREFIX + ['items', 0], value=message('first')))
        self.a.publish()
        worker.join(1)
        self.assertFalse(worker.is_alive())
        self.assertEqual(result[0]['reply_preview']['turn_id'], 'turn1')
        self.assertEqual(result[0]['reply_preview']['item_id'], 'answer')
        self.assertGreater(result[0]['revision'], before)

    def test_request_timing_logs_only_correlation_and_elapsed_durations(self):
        request_id = 'request-secret-looking-id'
        with self.assertLogs('shared_assistant', level='INFO') as logs, patch(
                'shared_assistant.time.monotonic', return_value=100) as clock:
            self.a.send('正文不可出现在日志 https://private.invalid/token', request_id, False, 'pc1')
            p = self.desktop.sessions['pc1'] = projection()
            p.turns['latest']['request_id'] = request_id
            clock.return_value = 100.25
            change(p, dict(op='add', path=PREFIX + ['items', 0], value=message('私有正文')))
            self.a.publish()
            clock.return_value = 101.5
            change(p, dict(op='replace', path=PREFIX + ['status'], value='completed'))
            self.a.publish()
        output = '\n'.join(logs.output)
        for phase in ['computer_received', 'native_received', 'first_body', 'completed']:
            self.assertIn(phase, output)
        self.assertIn(hashlib.sha256(request_id.encode()).hexdigest()[:12], output)
        self.assertIn('elapsed_ms=250', output)
        self.assertIn('elapsed_ms=1500', output)
        for private in [request_id, '正文', 'private.invalid', '/token']:
            self.assertNotIn(private, output)

    def test_native_text_input_is_desktop_renderable(self):
        self.a.send('接着聊', 'native-contract', False, 'pc1')
        params = self.desktop.calls[0][1]
        self.assertEqual(params['turnStart']['context'], dict(inheritThreadSettings=True))
        native = params['turnStart']['request']['input'][0]
        self.assertEqual(native, dict(type='text', text='【手表来源：请优先使用适合小屏阅读的简短回答。】\n接着聊', text_elements=[]))
        # Desktop renderer calls e.text_elements.some(...), so missing is a crash.
        self.assertIsInstance(native['text_elements'], list)

    def test_first_body_immediate_later_body_throttled_completion_immediate(self):
        p = self.desktop.sessions['pc1'] = projection()
        with patch('shared_assistant.time.monotonic', return_value=100) as clock:
            self.a.publish()
            baseline = self.a.revision
            change(p, dict(op='add', path=PREFIX + ['items', 0], value=message('a')))
            self.a.publish()
            self.assertGreater(self.a.revision, baseline)
            self.assertEqual(self.a.snapshot(True, 'pc1')['reply_preview']['text'], 'a')
            first_revision = self.a.revision
            clock.return_value = 100.2
            change(p, dict(op='replace', path=PREFIX + ['items', 0, 'text'], value='ab'))
            self.a.publish()
            self.assertEqual(self.a.revision, first_revision)
            self.assertEqual(self.a.snapshot(True, 'pc1')['reply_preview']['text'], 'a')
            clock.return_value = 101
            self.a.publish()
            self.assertGreater(self.a.revision, first_revision)
            self.assertEqual(self.a.snapshot(True, 'pc1')['reply_preview']['text'], 'ab')
            clock.return_value = 101.1
            change(p, dict(op='replace', path=PREFIX + ['items', 0, 'text'], value='done'),
                dict(op='replace', path=PREFIX + ['status'], value='completed'),
                dict(op='replace', path=['threadRuntimeStatus', 'type'], value='idle'))
            self.a.publish()
            self.assertEqual(self.a.snapshot(True, 'pc1')['reply_preview']['text'], 'done')
            self.assertTrue(self.a.snapshot(True, 'pc1')['reply_preview']['complete'])

    def test_approval_and_new_turn_bypass_throttle_without_leaking_pending_text(self):
        p = self.desktop.sessions['pc1'] = projection()
        with patch('shared_assistant.time.monotonic', return_value=100) as clock:
            change(p, dict(op='add', path=PREFIX + ['items', 0], value=message('a')))
            self.a.publish()
            previous = self.a.revision
            clock.return_value = 100.1
            change(p, dict(op='replace', path=PREFIX + ['items', 0, 'text'], value='ab'),
                dict(op='add', path=['requests', 0], value=command()))
            self.a.publish()
            self.assertGreater(self.a.revision, previous)
            view = self.a.snapshot(True, 'pc1')
            self.assertEqual(len(view['pending']), 1)
            self.assertEqual(view['reply_preview']['text'], 'a')
            change(p, dict(op='add', path=PREFIX[:-1] + ['second'], value=dict(
                turnId='turn2', turnStartedAtMs=200, status='inProgress', params={}, items=[])))
            self.a.publish()
            self.assertIsNone(self.a.snapshot(True, 'pc1')['reply_preview'])


if __name__ == '__main__':
    unittest.main()
