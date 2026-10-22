import copy
from pathlib import Path
import tempfile
import unittest

from codex_session import Assistant
from desktop_ipc import DesktopUnavailable, DeliveryUnknown
from shared_assistant import SharedAssistant
from test_shared_assistant import FakeDesktop, projection, command


class ManagementTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.legacy = Assistant(Path(self.temp.name) / 'state.json')
        self.desktop = FakeDesktop()
        self.a = SharedAssistant(self.legacy, desktop=self.desktop)
        self.a.model_catalog = lambda: dict(models=[dict(id='preserve-model', name='原模型', efforts=['low', 'high'])], modes=['default', 'plan'])
        self.desktop.sessions['pc1'].data['latestReasoningEffort'] = 'high'
        self.metadata = dict(id='pc1', name='原对话', section='normal')
        self.a.conversations.rpc = lambda method, params: dict(thread=copy.deepcopy(self.metadata))
        self.management_calls = []
        test = self
        class Manager:
            def __enter__(self):return self
            def __exit__(self, *args):test.management_calls.append(('exit', {}))
            def call(self, method, params):
                test.management_calls.append((method, copy.deepcopy(params)))
                if method == 'thread/name/set':test.metadata['name'] = params['name']
                return dict(thread=copy.deepcopy(test.metadata))
        self.a.record_factory = Manager

    def tearDown(self):self.temp.cleanup()

    def test_settings_are_readable_but_writes_require_verified_capability(self):
        settings = self.a.thread_settings('pc1')
        self.assertEqual(settings['model'], 'preserve-model')
        self.assertEqual(settings['effort'], 'high')
        self.assertFalse(settings['can_save'])
        with self.assertRaises(DesktopUnavailable):
            self.a.save_thread_settings('pc1', dict(effort='low'), settings['version'], 'settings1')
        self.assertEqual(self.desktop.calls, [])

    def test_settings_preserve_other_thread_and_native_permissions(self):
        self.a.verified_capabilities.add('settings')
        self.desktop.sessions['pc2'] = projection('pc2', status='idle')
        original = copy.deepcopy(self.desktop.sessions['pc2'].data)
        def native(method, params, target=None, timeout=8):
            self.desktop.calls.append((method, copy.deepcopy(params), target))
            patch = params['threadSettings']
            self.desktop.sessions['pc1'].data['latestReasoningEffort'] = patch.get('effort', 'high')
            return dict(resultType='success', result=dict(applied=True))
        self.desktop.request = native
        before = self.a.thread_settings('pc1')
        after = self.a.save_thread_settings('pc1', dict(effort='low'), before['version'], 'settings1')
        self.assertEqual(after['effort'], 'low')
        self.assertEqual(self.desktop.sessions['pc2'].data, original)
        patch = self.desktop.calls[0][1]['threadSettings']
        self.assertEqual(patch, dict(effort='low'))
        self.assertNotIn('permissions', patch)
        self.assertEqual(self.desktop.calls[0][1]['condition']['ifModelEquals'], 'preserve-model')

    def test_settings_reject_busy_stale_invalid_and_global_fields(self):
        self.a.verified_capabilities.add('settings')
        version = self.a.thread_settings('pc1')['version']
        for value in [dict(model='invented-model'), dict(effort='unsupported'), dict(globalDefault=True), dict(mode='unrestricted')]:
            with self.assertRaises(ValueError):
                self.a.save_thread_settings('pc1', value, version, 'bad')
        with self.assertRaises(RuntimeError):
            self.a.save_thread_settings('pc1', dict(effort='low'), 'stale', 'bad')
        self.desktop.sessions['pc1'] = projection(requests=[command()])
        with self.assertRaises(RuntimeError):
            self.a.save_thread_settings('pc1', dict(effort='low'), version, 'busy')
        self.assertEqual(self.desktop.calls, [])

    def test_rename_uses_metadata_api_exits_and_keeps_original_id(self):
        self.a.verified_capabilities.add('rename')
        info = self.a.thread_management('pc1')
        result = self.a.manage_thread('pc1', 'rename', info['version'], 'rename1', name='我的对话')
        self.assertEqual(result['thread_id'], 'pc1')
        self.assertEqual(result['title'], '我的对话')
        self.assertEqual([x[0] for x in self.management_calls], ['thread/name/set', 'exit'])
        self.assertEqual(self.desktop.calls, [])

    def test_archive_requires_confirmation_and_idle_without_pending(self):
        self.a.verified_capabilities.add('archive')
        info = self.a.thread_management('pc1')
        with self.assertRaises(ValueError):
            self.a.manage_thread('pc1', 'archive', info['version'], 'a1')
        self.desktop.sessions['pc1'] = projection(requests=[command()])
        with self.assertRaises(RuntimeError):
            self.a.manage_thread('pc1', 'archive', info['version'], 'a2', confirmed=True)
        self.assertEqual(self.management_calls, [])

    def test_unknown_settings_response_is_not_replayed(self):
        self.a.verified_capabilities.add('settings')
        self.desktop.fail = True
        version = self.a.thread_settings('pc1')['version']
        for _ in range(2):
            with self.assertRaises(DeliveryUnknown):
                self.a.save_thread_settings('pc1', dict(effort='low'), version, 'same')
        self.assertEqual(len(self.desktop.calls), 1)

    def test_restore_preserves_id_and_notifies_native_desktop_once(self):
        self.a.verified_capabilities.add('restore')
        events=[];self.desktop.record_restored=events.append
        info=self.a.thread_management('pc1')
        first=self.a.manage_thread('pc1','restore',info['version'],'restore1')
        second=self.a.manage_thread('pc1','restore',info['version'],'restore1')
        self.assertEqual(first,second)
        self.assertEqual(first['thread_id'],'pc1')
        self.assertEqual(events,['pc1'])
        self.assertEqual([x[0] for x in self.management_calls],['thread/unarchive','exit'])

    def test_management_version_invalidates_when_record_location_changes(self):
        self.a.verified_capabilities.add('restore')
        self.metadata['path']='C:/codex/archived_sessions/qa.jsonl'
        original=self.a.thread_management('pc1')
        self.metadata['path']='C:/codex/sessions/qa.jsonl'
        with self.assertRaises(RuntimeError):
            self.a.manage_thread('pc1','restore',original['version'],'stale-location')
        self.assertEqual(self.management_calls,[])

    def test_restored_record_with_notification_disconnect_is_unknown_not_rejected(self):
        self.a.verified_capabilities.add('restore')
        def disconnected(ident):raise DesktopUnavailable('电脑刚断连')
        self.desktop.record_restored=disconnected
        info=self.a.thread_management('pc1')
        for attempt in range(2):
            with self.assertRaises(DeliveryUnknown):
                self.a.manage_thread('pc1','restore',info['version'],'restore-unknown')
        self.assertEqual(self.a.operation_delivery('manage','pc1','restore-unknown'),'unknown')
        self.assertEqual([x[0] for x in self.management_calls],['thread/unarchive','exit'])

    def test_open_desktop_only_opens_the_validated_existing_thread_without_model_work(self):
        opened=[];self.a.open_thread=opened.append
        result=self.a.open_on_desktop('pc1')
        self.assertTrue(result['opened']);self.assertEqual(opened,['pc1'])
        with self.assertRaises(RuntimeError):self.a.open_on_desktop('another-id')
        self.assertEqual(opened,['pc1']);self.assertEqual(self.desktop.calls,[])


if __name__ == '__main__':unittest.main()
