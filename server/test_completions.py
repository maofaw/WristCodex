"""Completion fixtures only: no listener, executor, network, or device."""
from pathlib import Path
import tempfile
import unittest

from codex_session import Assistant
from shared_assistant import SharedAssistant
from test_shared_assistant import FakeDesktop, projection, state


class CompletionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.desktop = FakeDesktop()
        self.desktop.connection_generation = 1
        self.assistant = SharedAssistant(Assistant(Path(self.temp.name) / 'state.json'),
            desktop=self.desktop, open_thread=lambda _: self.fail('must not open desktop'))

    def put(self, ident='pc1', status='inProgress', runtime='active', turn='turn1', requests=None):
        raw = state(ident, runtime, requests)
        raw['turnHistory']['history']['entitiesByKey']['latest'].update(turnId=turn, status=status)
        current = self.desktop.sessions.setdefault(ident, projection(ident))
        current.accept(dict(type='snapshot', revision=current.revision + 1, conversationState=raw))

    def notice(self, ident='pc1'):
        value = self.assistant.snapshot(True, ident)
        notice = value['completion_notifications']
        self.assertEqual(notice['scope'], value['connection_scope'])
        return notice

    def test_first_completed_snapshot_does_not_replay_history(self):
        notice = self.notice()
        self.assertTrue(notice['available'])
        self.assertEqual((notice['cursor'], notice['oldest'], notice['events']), (0, 1, []))

    def test_native_completed_waits_for_runtime_idle_and_emits_once(self):
        self.put()
        self.assistant.publish()
        self.put(status='completed')
        self.assertEqual(self.notice()['events'], [])
        self.put(status='completed', runtime='idle')
        event = self.notice()['events'][0]
        self.assertEqual(event, dict(sequence=1, thread_id='pc1', turn_id='turn1', title='电脑对话'))
        self.assistant.publish(True)
        self.assertEqual(self.notice()['events'], [event])
        self.put()
        self.notice()
        self.put(status='completed', runtime='idle')
        self.assertEqual(self.notice()['events'], [event])

    def test_all_sessions_publish_to_unrelated_selected_thread_and_events(self):
        for ident in ('pc2', 'pc3'):
            self.put(ident)
        self.assistant.publish()
        for ident in ('pc2', 'pc3'):
            self.put(ident, status='completed', runtime='idle')
        self.assistant.publish()
        value = self.assistant.events(self.assistant.revision - 1, True, 'pc1')
        self.assertEqual([e['thread_id'] for e in value['completion_notifications']['events']], ['pc2', 'pc3'])
        self.assertEqual(self.desktop.calls, [])

    def test_pending_and_preview_are_not_success(self):
        self.put()
        self.notice()
        p = self.desktop.sessions['pc1']
        p.preview = dict(thread_id='pc1', turn_id='turn1', item_id='answer', complete=True)
        self.assertEqual(self.notice()['cursor'], 0)
        from test_shared_assistant import command
        self.put(status='completed', runtime='idle', requests=[command()])
        self.assertEqual(self.notice()['cursor'], 0)
        self.put(status='completed', runtime='idle')
        self.assertEqual(self.notice()['cursor'], 1)

    def test_failure_stop_and_readonly_cannot_later_become_success(self):
        for terminal in ('failed', 'interrupted', 'readonly'):
            with self.subTest(terminal=terminal):
                self.put(turn=terminal)
                self.notice()
                if terminal == 'readonly':
                    self.desktop.sessions['pc1'].valid = False
                else:
                    self.put(status=terminal, runtime='idle', turn=terminal)
                self.assertEqual(self.notice()['cursor'], 0)
                self.put(status='completed', runtime='idle', turn=terminal)
                self.assertEqual(self.notice()['cursor'], 0)

    def test_first_readonly_running_does_not_arm(self):
        self.put()
        self.desktop.sessions['pc1'].valid = False
        self.notice()
        self.put(status='completed', runtime='idle')
        self.assertEqual(self.notice()['events'], [])

    def test_turn_identity_must_match(self):
        self.put(turn='old')
        self.notice()
        self.put(turn='new', status='completed', runtime='idle')
        self.assertEqual(self.notice()['cursor'], 0)

    def test_generation_change_resets_scope_buffer_and_observations(self):
        self.put()
        old = self.notice()['scope']
        self.desktop.connection_generation += 1
        self.put(status='completed', runtime='idle')
        fresh = self.notice()
        self.assertNotEqual(fresh['scope'], old)
        self.assertEqual((fresh['cursor'], fresh['events']), (0, []))

    def test_disconnect_resets_and_reconnect_does_not_replay(self):
        self.put()
        self.notice()
        self.put(status='completed', runtime='idle')
        before = self.notice()
        self.desktop.connected = False
        offline = self.notice()
        self.assertFalse(offline['available'])
        self.assertNotEqual(offline['scope'], before['scope'])
        self.assertEqual(offline['events'], [])
        self.desktop.connected = True
        self.assertEqual(self.notice()['cursor'], 0)

    def test_buffer_is_64_and_events_are_detached_copies(self):
        for i in range(70):
            self.put(turn=str(i))
            self.assistant.publish()
            self.put(turn=str(i), status='completed', runtime='idle')
            self.assistant.publish()
        notice = self.notice()
        self.assertEqual((len(notice['events']), notice['cursor'], notice['oldest']), (64, 70, 7))
        notice['events'][0]['title'] = 'mutated'
        self.assertEqual(self.notice()['events'][0]['title'], '电脑对话')

    def test_scope_change_during_assembly_is_rejected(self):
        original = self.assistant.capabilities
        def changed(*args):
            self.desktop.connection_generation += 1
            return original(*args)
        self.assistant.capabilities = changed
        with self.assertRaises(ValueError):
            self.assistant.snapshot(True)

    def test_native_generation_change_during_collection_cannot_arm_new_scope(self):
        self.put()
        p = self.desktop.sessions['pc1']
        original = p.snapshot
        def changed(*args):
            self.desktop.connection_generation += 1
            return original(*args)
        p.snapshot = changed
        self.assistant.publish()
        p.snapshot = original
        self.put(status='completed', runtime='idle')
        self.assertEqual(self.notice()['cursor'], 0)

    def test_mislabeled_completion_epoch_is_rejected(self):
        self.assistant.completions.scope = 'stale-epoch'
        with self.assertRaises(ValueError):
            self.assistant.snapshot(True)


class TransitionTests(unittest.TestCase):
    def test_each_active_state_arms_and_pending_count_blocks(self):
        from completions import CompletionNotifications
        for active in ('starting', 'running', 'waiting'):
            with self.subTest(active=active):
                tracker = CompletionNotifications('scope')
                value = dict(thread_id='t', turn_id='turn', readonly=False, state=active)
                tracker.observe([value], True)
                value.update(state='completed', can_send=True, pending_count=1)
                tracker.observe([value], True)
                self.assertEqual(tracker.cursor, 0)
                value['pending_count'] = 0
                tracker.observe([value], True)
                self.assertEqual(tracker.cursor, 1)

    def test_evicted_events_cannot_be_reemitted(self):
        from completions import CompletionNotifications
        tracker = CompletionNotifications('scope')
        for i in list(range(65)) + [0]:
            value = dict(thread_id='t', turn_id=str(i), readonly=False, state='running')
            tracker.observe([value], True)
            value.update(state='completed', can_send=True)
            tracker.observe([value], True)
        self.assertEqual((tracker.cursor, len(tracker.events)), (65, 64))


if __name__ == '__main__':
    unittest.main()
