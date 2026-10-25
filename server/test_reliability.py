import json
from pathlib import Path
import tempfile
import unittest
from codex_session import Assistant
from shared_assistant import SharedAssistant
from desktop_ipc import DeliveryUnknown
from decisions import digest
from conversations import ConversationStore
from unittest.mock import patch
import time
import hashlib
from test_shared_assistant import FakeDesktop, projection, command


class ReliabilityTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.legacy = Assistant(Path(self.temp.name)/'state.json')
        self.desktop = FakeDesktop()
        self.desktop.connection_generation = 1
        self.desktop.sessions['pc1'] = projection(requests=[command()])
        self.a = SharedAssistant(self.legacy, desktop=self.desktop)

    def test_identity_unknown_scope_rotates_and_envelopes_match(self):
        first = self.a.snapshot(True)
        self.assertIsNone(first['connection_identity'])
        self.assertFalse(first['connection_identity_verified'])
        scope = first['connection_scope']
        self.assertEqual(self.a.preferences()['connection_scope'], scope)
        self.assertEqual(self.a.attention()['connection_scope'], scope)
        self.desktop.connection_generation += 1
        self.assertNotEqual(self.a.snapshot(True)['connection_scope'], scope)
        self.assertNotEqual(SharedAssistant(self.legacy, desktop=self.desktop).preferences()['connection_scope'], scope)

    def test_unknown_blocks_changed_version_after_restart_and_status_never_writes(self):
        view = self.desktop.sessions['pc1'].pending()[0][0]
        self.desktop.fail = True
        with self.assertRaises(DeliveryUnknown):
            self.a.answer(view['id'], 'accept', thread_id='pc1', turn_id='turn1', expected_version=view['version'])
        self.desktop.sessions['pc1'].requests[0]['params']['command'] = 'changed'
        new = self.desktop.sessions['pc1'].pending()[0][0]
        self.assertNotEqual(new['version'], view['version'])
        self.a = SharedAssistant(self.legacy, desktop=self.desktop)
        with self.assertRaises(DeliveryUnknown):
            self.a.answer(new['id'], 'cancel', thread_id='pc1', turn_id='turn1')
        result = self.a.answer_status(new['id'], 'pc1', 'turn1', new['version'])
        self.assertEqual(result['delivery_status'], 'unknown')
        self.assertFalse(result['can_submit'])
        self.desktop.sessions['pc1'].requests = []
        result = self.a.answer_status(new['id'], 'pc1', 'turn1', new['version'])
        self.assertEqual(result['delivery_status'], 'unknown')
        self.assertEqual(result['request_status'], 'processed')
        self.desktop.sessions['pc1'].valid = False
        self.assertEqual(self.a.answer_status(new['id'], 'pc1', 'turn1')['request_status'], 'unverified')
        self.assertEqual(len(self.desktop.calls), 1)

    def test_legacy_unknown_version_receipt_still_blocks(self):
        view = self.desktop.sessions['pc1'].pending()[0][0]
        self.a.store.record('decision:'+digest([view['id'], view['version']]),
            dict(thread_id='pc1', turn_id='turn1', status='unknown'))
        self.desktop.sessions['pc1'].requests[0]['params']['command'] = 'mutated'
        with self.assertRaises(DeliveryUnknown):
            self.a.answer(view['id'], 'accept', thread_id='pc1', turn_id='turn1')
        self.assertEqual(self.desktop.calls, [])

    def test_all_requests_page_revision_bound_and_incomplete(self):
        self.desktop.sessions['pc1'] = projection(requests=[command(token=i) for i in range(151)])
        page = self.a.attention()
        self.assertFalse(page['attention_complete'])
        self.assertTrue(page['attention_available'])
        rows = page['attention_requests'][:]
        while page['attention_next_cursor']:
            page = self.a.attention(request_cursor=page['attention_next_cursor'])
            rows.extend(page['attention_requests'])
        self.assertEqual(len({r['id'] for r in rows}), 151)
        self.assertLess(len(json.dumps(self.a.snapshot(True), ensure_ascii=False).encode()), 70000)
        cursor = self.a.attention()['attention_next_cursor']
        self.desktop.sessions['pc1'].requests.pop()
        with self.assertRaises(ValueError): self.a.attention(request_cursor=cursor)

    def test_scope_and_version_bound_status(self):
        view = self.desktop.sessions['pc1'].pending()[0][0]
        self.assertTrue(self.a.answer_status(view['id'],'pc1','turn1',view['version'])['can_submit'])
        self.assertFalse(self.a.answer_status(view['id'],'pc1','turn1','old')['can_submit'])
        self.assertEqual(self.a.answer_status(view['id'],'pc1','turn1', connection_scope='old')['request_status'], 'stale')
        with self.assertRaises(ValueError): self.a.answer(view['id'],'accept',thread_id='pc1',turn_id='turn1',connection_scope='old')
        self.assertEqual(self.desktop.calls, [])

    def test_corrupt_decision_receipt_cannot_make_unknown_retryable(self):
        view = self.desktop.sessions['pc1'].pending()[0][0]
        key = 'decision-request:'+digest(['pc1','turn1',view['id']])
        self.a.store.record(key, dict(status='unknown',decision_id=view['id']))
        self.a.store._path('receipts', key).write_text('{broken', encoding='utf-8')
        self.legacy.store=ConversationStore(self.legacy.file)
        self.a=SharedAssistant(self.legacy,desktop=self.desktop)
        with self.assertRaises(DeliveryUnknown):
            self.a.answer(view['id'],'accept',thread_id='pc1',turn_id='turn1')
        self.assertEqual(self.a.answer_status(view['id'],'pc1','turn1')['delivery_status'],'unknown')
        self.assertEqual(self.desktop.calls, [])

    def test_watch_source_marker_is_bridge_owned(self):
        self.desktop.sessions['pc1'] = projection(status='idle')
        self.a.send('hello', 'source-marker-test', thread_id='pc1')
        payload = self.desktop.calls[-1][1]['turnStart']['request']['input'][0]
        self.assertIn('手表', payload['text'])
        self.assertTrue(payload['text'].endswith('hello'))
        self.assertEqual(payload['text_elements'], [])

    def test_schema_corruption_stays_unknown_after_store_reconstruction(self):
        view=self.desktop.sessions['pc1'].pending()[0][0]
        key='decision-request:'+digest(['pc1','turn1',view['id']])
        for broken in ({},None,[],{'status':'invalid'},{'status':'unknown'}):
            with self.subTest(broken=broken):
                self.a.store._write(self.a.store._path('receipts',key),broken)
                self.legacy.store=ConversationStore(self.legacy.file)
                self.a=SharedAssistant(self.legacy,desktop=self.desktop)
                with self.assertRaises(DeliveryUnknown):
                    self.a.answer(view['id'],'accept',thread_id='pc1',turn_id='turn1')
                self.assertEqual(self.a.answer_status(view['id'],'pc1','turn1')['delivery_status'],'unknown')
        self.assertEqual(self.desktop.calls,[])

    def test_attention_rejects_generation_change_during_capture(self):
        original=self.a.decision_status
        def changed(view):
            result=original(view)
            self.desktop.connection_generation+=1
            return result
        with patch.object(self.a,'decision_status',side_effect=changed):
            with self.assertRaises(ValueError):self.a.attention()

    def test_legacy_guard_and_exact_lookups_do_not_read_disk_per_request(self):
        for i in range(100):
            self.a.store.record('old-'+str(i),dict(status='accepted',thread_id='elsewhere',turn_id=str(i)))
        self.a.store.record('decision:legacy',dict(status='unknown',thread_id='pc1',turn_id='turn1'))
        self.legacy.store=ConversationStore(self.legacy.file)
        self.a=SharedAssistant(self.legacy,desktop=self.desktop)
        with patch.object(self.a.store,'_read',side_effect=AssertionError('hot-path disk read')), \
                patch.object(Path,'glob',side_effect=AssertionError('hot-path directory scan')):
            for i in range(40):
                self.assertEqual(self.a.decision_receipt(str(i),'pc1','turn1')['status'],'unknown')

    def test_only_safely_terminal_receipts_are_retained_for_thirty_days(self):
        old=time.time()-31*86400
        self.a.store.record('decision:old-rejected',dict(status='rejected',thread_id='pc1',turn_id='turn1',updated_at=old))
        self.a.store.record('decision:old-unknown',dict(status='unknown',thread_id='pc1',turn_id='turn1',updated_at=old))
        self.a.store.record('decision:old-accepted',dict(status='accepted',thread_id='pc1',turn_id='turn1',updated_at=old))
        store=ConversationStore(self.legacy.file)
        self.assertEqual(store.receipt('decision:old-rejected'),{})
        self.assertEqual(store.receipt('decision:old-unknown')['status'],'unknown')
        self.assertEqual(store.receipt('decision:old-accepted')['status'],'accepted')

    def test_terminal_quota_never_evicts_unknown_or_unproven_accepted(self):
        for i in range(3):
            self.a.store.record('decision:terminal-'+str(i),dict(status='rejected',thread_id='pc1',turn_id='turn1',updated_at=time.time()+i))
        for status in ('unknown','accepted'):
            self.a.store.record('decision:retain-'+status,dict(status=status,thread_id='pc1',turn_id='turn1'))
        with patch.object(ConversationStore,'TERMINAL_RECEIPT_LIMIT',2):
            store=ConversationStore(self.legacy.file)
        self.assertEqual(store.receipt('decision:terminal-0'),{})
        self.assertEqual(store.receipt('decision:terminal-1')['status'],'rejected')
        self.assertEqual(store.receipt('decision:terminal-2')['status'],'rejected')
        self.assertEqual(store.receipt('decision:retain-unknown')['status'],'unknown')
        self.assertEqual(store.receipt('decision:retain-accepted')['status'],'accepted')

    def test_required_version_status_permissions_and_form(self):
        self.a.refresh_verification()
        for kind in ('permissions','form'):
            view=dict(id='request1',thread_id='pc1',turn_id='turn1',version='version1',kind=kind,supported=True)
            self.a.verified_capabilities.update(('permissions','forms'))
            with patch.object(self.desktop.sessions['pc1'],'pending',return_value=[(view,{})]):
                result=self.a.answer_status('request1','pc1','turn1')
                self.assertFalse(result['can_submit'])
                self.assertTrue(result['unavailable_reason'])
                self.assertTrue(self.a.answer_status('request1','pc1','turn1','version1')['can_submit'])

    def test_invalid_terminal_claims_survive_age_and_quota_cleanup(self):
        view=self.desktop.sessions['pc1'].pending()[0][0]
        key='decision-request:'+digest(['pc1','turn1',view['id']])
        path=self.a.store._path('receipts',key)
        for status in ('rejected','accepted'):
            for aged in (True,False):
                with self.subTest(status=status,aged=aged):
                    self.desktop.sessions['pc1']=projection(requests=[command()])
                    self.desktop.calls.clear()
                    broken=dict(receipt_kind='decision',status=status,updated_at=time.time()-(31*86400 if aged else 0))
                    if status=='accepted':broken['processed_at']=time.time()-1
                    self.a.store._write(path,broken)
                    with patch.object(ConversationStore,'TERMINAL_RECEIPT_LIMIT',0):
                        self.legacy.store=ConversationStore(self.legacy.file)
                    self.a=SharedAssistant(self.legacy,desktop=self.desktop)
                    self.assertTrue(path.exists())
                    self.assertEqual(self.a.answer_status(view['id'],'pc1','turn1')['delivery_status'],'unknown')
                    with self.assertRaises(DeliveryUnknown):
                        self.a.answer(view['id'],'accept',thread_id='pc1',turn_id='turn1')
                    self.assertEqual(self.desktop.calls,[])

    def test_raw_v04_message_receipt_preserves_dedup_without_blocking_decisions(self):
        fingerprint=hashlib.sha256(json.dumps(['legacy',False,'pc1'],ensure_ascii=False).encode()).hexdigest()
        old=dict(fingerprint=fingerprint,thread_id='pc1')
        self.a.store._write(self.a.store._path('receipts','legacy-message'),old)
        self.legacy.store=ConversationStore(self.legacy.file)
        self.a=SharedAssistant(self.legacy,desktop=self.desktop)
        self.assertEqual(self.a.store.receipt('legacy-message'),old)
        self.assertEqual(self.a.send('legacy','legacy-message',thread_id='pc1')['accepted_thread_id'],'pc1')
        view=self.desktop.sessions['pc1'].pending()[0][0]
        self.assertTrue(self.a.answer_status(view['id'],'pc1','turn1',view['version'])['can_submit'])
        self.assertEqual(self.desktop.calls,[])

    def test_exact_malformed_decision_cannot_impersonate_legacy_message(self):
        view=self.desktop.sessions['pc1'].pending()[0][0]
        key='decision-request:'+digest(['pc1','turn1',view['id']])
        self.a.store._write(self.a.store._path('receipts',key),dict(fingerprint='a'*64,thread_id='pc1'))
        self.legacy.store=ConversationStore(self.legacy.file)
        self.a=SharedAssistant(self.legacy,desktop=self.desktop)
        with self.assertRaises(DeliveryUnknown):
            self.a.answer(view['id'],'accept',thread_id='pc1',turn_id='turn1')
        self.assertEqual(self.desktop.calls,[])

    def test_accepted_legacy_message_receipt_does_not_block_unrelated_decision(self):
        fingerprint=hashlib.sha256(json.dumps(['legacy',False,'pc1'],ensure_ascii=False).encode()).hexdigest()
        old=dict(fingerprint=fingerprint,thread_id='pc1',status='accepted')
        path=self.a.store._path('receipts','legacy-message')
        self.a.store._write(path,old)
        before=path.read_bytes()
        self.legacy.store=ConversationStore(self.legacy.file)
        self.a=SharedAssistant(self.legacy,desktop=self.desktop)
        self.assertEqual(self.a.store.receipt('legacy-message'),old)
        self.assertEqual(self.a.send('legacy','legacy-message',thread_id='pc1')['accepted_thread_id'],'pc1')
        view=self.desktop.sessions['pc1'].pending()[0][0]
        self.assertTrue(self.a.answer_status(view['id'],'pc1','turn1',view['version'])['can_submit'])
        self.assertEqual(path.read_bytes(),before)
        self.assertEqual(self.desktop.calls,[])

    def test_exact_decision_cannot_impersonate_accepted_legacy_message(self):
        view=self.desktop.sessions['pc1'].pending()[0][0]
        key='decision-request:'+digest(['pc1','turn1',view['id']])
        self.a.store._write(self.a.store._path('receipts',key),dict(fingerprint='a'*64,thread_id='pc1',status='accepted'))
        self.legacy.store=ConversationStore(self.legacy.file)
        self.a=SharedAssistant(self.legacy,desktop=self.desktop)
        with self.assertRaises(DeliveryUnknown):
            self.a.answer(view['id'],'accept',thread_id='pc1',turn_id='turn1')
        self.assertEqual(self.desktop.calls,[])

    def test_unknown_legacy_message_shape_remains_guarded(self):
        self.a.store._write(self.a.store._path('receipts','ambiguous'),dict(fingerprint='a'*64,thread_id='pc1',status='unknown'))
        self.legacy.store=ConversationStore(self.legacy.file)
        self.a=SharedAssistant(self.legacy,desktop=self.desktop)
        view=self.desktop.sessions['pc1'].pending()[0][0]
        self.assertFalse(self.a.answer_status(view['id'],'pc1','turn1',view['version'])['can_submit'])
        self.assertEqual(self.desktop.calls,[])
