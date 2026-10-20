"""Watch facade for the running desktop's native conversation owner."""
from collections import OrderedDict
import hashlib
import logging
import json
import os
from pathlib import Path
import threading
import time
from urllib.parse import quote

from conversations import Conversations
from desktop_ipc import DesktopIPC, DesktopUnavailable, DeliveryUnknown, native_text
from record_manager import RecordManager
from ai_bridge import WORKSPACE


LOG = logging.getLogger(__name__)


class SharedConversations(Conversations):
    def describe(self, thread):
        value = super().describe(thread)
        live = self.assistant.desktop.peek(value['id'])
        value.update(source='shared' if live else value['source'],
            readonly=not live or live['readonly'], can_activate=self.assistant.desktop.connected,
            state=live['state'] if live else 'idle',
            unavailable_reason='' if live and not live['readonly'] else '在电脑连接此对话后可继续')
        return value

    def metadata(self, ident):
        self.ident(ident)
        if self.assistant.desktop.connected:
            try:
                self.assistant.desktop.discover(ident)
            except RuntimeError:
                pass
        return super().metadata(ident)

    def messages(self, ident, cursor=None):
        value = super().messages(ident, cursor)
        value['active'] = self.assistant.snapshot(True, ident)
        return value


class SharedAssistant:
    def __init__(self, legacy, file=None, desktop=None, open_thread=None, record_factory=None):
        self.legacy = legacy
        self.store = legacy.store
        self.file = Path(file or legacy.file.with_name('assistant-desktop.json'))
        self.cv = threading.Condition(threading.RLock())
        self.operation_lock = threading.Lock()
        self.preference_lock = threading.Lock()
        self.publish_lock = threading.RLock()
        self.changed_event = threading.Event()
        self.running = False
        try:
            saved = json.loads(self.file.read_text(encoding='utf-8'))
        except (OSError, ValueError):
            saved = {}
        self.revision = max(saved.get('revision', 0), legacy.snapshot()['revision']) + 1
        self.preferred = saved.get('preferred') or legacy.snapshot()['thread_id']
        self.desktop_attention_enabled = saved.get('desktop_attention_enabled', True)
        self.desktop = desktop or DesktopIPC(self.changed_event.set)
        self.open_thread = open_thread or (lambda ident: os.startfile('codex://threads/' + quote(ident, safe='')))
        self.record_factory = record_factory or RecordManager
        self.conversations = SharedConversations(self)
        self.last_signature = ''
        self.published_previews = {}
        self.preview_published_at = {}
        self.next_preview_at = None
        self.request_timings = OrderedDict()

    def persist(self):
        with self.preference_lock:
            self.file.parent.mkdir(parents=True, exist_ok=True)
            temp = self.file.with_suffix('.tmp')
            temp.write_text(json.dumps(dict(revision=self.revision, preferred=self.preferred,
                desktop_attention_enabled=self.desktop_attention_enabled)), encoding='utf-8')
            temp.replace(self.file)

    # This long-lived app-server is used for persisted history only.
    # Existing desktop conversations are NEVER resumed or executed through it.
    def ensure(self):
        self.legacy.ensure()

    def call(self, method, params):
        if method not in ('thread/read', 'thread/list', 'thread/items/list'):
            raise RuntimeError('此通道仅用于读取历史')
        return self.legacy.call(method, params)

    def start(self):
        if self.running:
            return
        self.running = True
        # The listener has no global logging setup. Enable only this privacy-safe
        # timing stream when the live shared service starts.
        if not LOG.handlers and not logging.getLogger().handlers:
            LOG.addHandler(logging.StreamHandler())
        LOG.setLevel(logging.INFO)
        self.desktop.start()
        threading.Thread(target=self.publish_loop, name='desktop-watch-state', daemon=True).start()
        threading.Thread(target=self.discover_loop, name='desktop-owner-catalog', daemon=True).start()

    def close(self):
        self.running = False
        self.changed_event.set()
        self.desktop.close()
        # This child only serves persisted history. Never stop the desktop owner.
        proc = self.legacy.proc
        if proc is not None and proc.poll() is None:
            proc.terminate()

    def publish_loop(self):
        while self.running:
            with self.cv:
                due = self.next_preview_at
            timeout = max(0, due - time.monotonic()) if due is not None else 1
            self.changed_event.wait(timeout)
            self.changed_event.clear()
            self.publish()

    @staticmethod
    def preview_identity(value):
        return tuple(value.get(k) for k in ('thread_id', 'turn_id', 'item_id')) if value else None

    def publish(self, force=False):
        with self.publish_lock:
            self._publish(force)

    def _publish(self, force=False):
        with self.desktop.lock:
            states = [s.snapshot(self.desktop.connected) for s in self.desktop.sessions.values() if s.data]
        now = time.monotonic()
        with self.cv:
            self.observe_timings(states, now)
            self.next_preview_at = None
            present = {s['thread_id'] for s in states}
            for ident in set(self.published_previews) - present:
                self.published_previews.pop(ident, None)
                self.preview_published_at.pop(ident, None)
            for state in states:
                ident = state['thread_id']
                candidate = state['reply_preview']
                previous = self.published_previews.get(ident)
                if candidate != previous:
                    due = self.preview_published_at.get(ident, 0) + 1
                    immediate = (candidate is None or previous is None
                        or self.preview_identity(candidate) != self.preview_identity(previous)
                        or candidate.get('complete')
                        or candidate.get('complete') != previous.get('complete'))
                    if immediate or now >= due:
                        self.published_previews[ident] = candidate
                        self.preview_published_at[ident] = now
                    else:
                        self.next_preview_at = min(self.next_preview_at or due, due)
                state['reply_preview'] = self.published_previews.get(ident)
            signature = json.dumps([self.desktop.connected, self.desktop.error,
                self.desktop_attention_enabled, self.preferred, states], sort_keys=True, ensure_ascii=False)
            if force or signature != self.last_signature:
                self.last_signature = signature
                self.revision += 1
                self.persist()
                self.cv.notify_all()

    def timing(self, request_id, phase, now):
        with self.cv:
            entry = self.request_timings.get(request_id)
            if entry is None or phase in entry['phases']:
                return
            entry['phases'].add(phase)
            LOG.info('request_timing correlation=%s phase=%s elapsed_ms=%d',
                hashlib.sha256(request_id.encode()).hexdigest()[:12], phase,
                max(0, round((now - entry['received']) * 1000)))

    def observe_timings(self, states, now):
        for state in states:
            request_id = state.get('request_id')
            if state.get('reply_preview'):
                self.timing(request_id, 'first_body', now)
            if (state.get('state') in ('completed', 'failed', 'interrupted')
                    or (state.get('reply_preview') or {}).get('complete')):
                self.timing(request_id, 'completed', now)

    def discover_loop(self):
        was_connected = False
        while self.running:
            if self.desktop.connected:
                try:
                    cursor = None
                    # Full startup scan finds old conversations already waiting for
                    # approval; later native owner announcements discover new ones.
                    while self.running and self.desktop.connected:
                        self.legacy.ensure()
                        page = self.legacy.call('thread/list', dict(limit=20, cursor=cursor,
                            sortKey='updated_at', sortDirection='desc', useStateDbOnly=True))
                        for thread in page['data']:
                            if not thread.get('ephemeral'):
                                self.desktop.schedule(thread['id'])
                        cursor = page.get('nextCursor')
                        if not cursor or was_connected:
                            break
                        time.sleep(.1)
                    was_connected = True
                except (RuntimeError, OSError, ValueError):
                    pass
            else:
                was_connected = False
            for _ in range(100 if was_connected else 10):
                if not self.running:
                    return
                time.sleep(.1)

    def preferences(self):
        return dict(desktop_attention_enabled=self.desktop_attention_enabled,
            desktop_connected=self.desktop.connected, desktop_error=self.desktop.error)

    def update_preferences(self, enabled):
        if not isinstance(enabled, bool):
            raise ValueError('提醒开关必须为布尔值')
        self.desktop_attention_enabled = enabled
        self.publish(True)
        return self.snapshot(True)

    def attention(self, cursor=0):
        if not isinstance(cursor, int) or cursor < 0:
            raise ValueError('待处理页码无效')
        rows = []
        keys = []
        if self.desktop.connected:
            with self.desktop.lock:
                for state in self.desktop.sessions.values():
                    if not state.valid:
                        continue
                    pending = state.pending()
                    keys.extend(v['id'] for v, _ in pending)
                    if pending:
                        first = pending[0][0]
                        rows.append({k: first[k] for k in ('id', 'thread_id', 'turn_id', 'title', 'summary', 'supported')}
                            | dict(count=len(pending)))
        rows.sort(key=lambda r: (r['title'], r['thread_id']))
        return dict(items=rows[cursor:cursor + 20], keys=keys[:128], total=sum(r['count'] for r in rows),
            conversations=len(rows), next_cursor=cursor + 20 if cursor + 20 < len(rows) else None)

    def snapshot(self, compact=False, thread_id=None):
        self.publish()
        ident = thread_id or self.preferred
        live = self.desktop.peek(ident)
        if live:
            value = live
            with self.cv:
                preview = self.published_previews.get(ident)
                current = live.get('reply_preview')
                value['reply_preview'] = (preview if self.preview_identity(preview)
                    == self.preview_identity(current) else None)
            if not compact:
                with self.desktop.lock:
                    value['reply'] = self.desktop.sessions[ident].reply
        else:
            old = self.legacy.snapshot(compact)
            value = old if ident == old['thread_id'] else dict(thread_id=ident or '', title='电脑对话',
                turn_id='', request_id='', state='idle', message='在电脑连接此对话后可继续', pending=[], summary='')
            value.update(readonly=True, can_send=False, can_steer=False, desktop=True,
                can_activate=self.desktop.connected)
            if not self.desktop.connected:
                value['message'] = '电脑客户端未连接，草稿已保留'
        value.setdefault('reply_preview', None)
        if value.get('readonly'):
            value.update(pending=[], needs_attention=False, reply_preview=None)
            if self.desktop.connected and self.desktop.error:
                value['message'] = self.desktop.error
        value.update(protocol_version=5, revision=self.revision, **self.preferences())
        pending = self.attention()
        value.update(attention_preview=pending['items'], attention_count=pending['total'],
            attention_conversations=pending['conversations'], attention_keys=pending['keys'])
        return value

    def events(self, after, compact=False, thread_id=None):
        with self.cv:
            self.cv.wait_for(lambda: self.revision > after, timeout=20)
        return self.snapshot(compact, thread_id)

    @staticmethod
    def ident(value):
        if not isinstance(value, str) or not value or len(value) > 200:
            raise ValueError('需要明确的对话编号')
        return value

    def activate(self, ident):
        self.ident(ident)
        if not self.desktop.connected:
            raise DesktopUnavailable('电脑客户端未连接；请先打开客户端')
        try:
            self.desktop.discover(ident)
            return self.snapshot(True, ident)
        except DesktopUnavailable:
            pass
        # Do not hand off a v0.4 task while it is still executing.
        old = self.legacy.snapshot()
        if ident == old['thread_id']:
            if old['state'] in ('running', 'waiting', 'starting'):
                raise RuntimeError('旧版任务尚未结束，请先在原版本处理')
            self.legacy.ensure()
            self.legacy.call('thread/unsubscribe', dict(threadId=ident))
            self.legacy.loaded = False
        # Validate existence through a metadata-only read, then use the desktop's
        # own URL handler. No shell interpolation and no new executor.
        self.legacy.ensure()
        self.legacy.call('thread/read', dict(threadId=ident, includeTurns=False))
        self.open_thread(ident)
        deadline = time.monotonic() + 12
        while self.desktop.connected and time.monotonic() < deadline:
            try:
                self.desktop.discover(ident)
                self.publish()
                return self.snapshot(True, ident)
            except DesktopUnavailable:
                time.sleep(.2)
        raise DesktopUnavailable('已请求电脑打开该对话，请稍后手动重试；未发送任务')

    def create_empty(self, title):
        if not self.desktop.connected:
            raise DesktopUnavailable('电脑客户端未连接')
        # Unsubscribing alone leaves an active writer. The short-lived manager
        # must exit before the desktop is asked to own this same conversation.
        with self.record_factory() as manager:
            result = manager.call('thread/start', dict(cwd=str(WORKSPACE), ephemeral=False))
            ident = result['thread']['id']
            manager.call('thread/name/set', dict(threadId=ident, name=title[:80]))
            manager.call('thread/unsubscribe', dict(threadId=ident))
            persisted = manager.call('thread/read', dict(threadId=ident, includeTurns=False))
            if persisted['thread']['id'] != ident:
                raise RuntimeError('空会话记录编号不一致，任务未执行')
        self.store.register(ident, title)
        return ident

    def _receipt(self, request_id, fingerprint):
        old = self.store.receipt(request_id)
        if old:
            if old['fingerprint'] != fingerprint:
                raise ValueError('请求编号与原内容不一致')
            if old.get('status') in ('sending', 'unknown'):
                raise DeliveryUnknown('未确认是否送达，请先查看电脑会话；不会重复执行')
            if old.get('status') == 'rejected':
                return None
            return old
        if request_id == self.legacy.state['request_id']:
            return dict(thread_id=self.legacy.state['thread_id'], status='accepted')
        return None

    @staticmethod
    def validate_message(prompt, request_id):
        if not isinstance(prompt, str) or not prompt.strip() or len(prompt) > 4000:
            raise ValueError('请输入 1–4000 字符')
        if not isinstance(request_id, str) or not request_id or len(request_id) > 100:
            raise ValueError('请求编号无效')

    def send(self, prompt, request_id, new=False, thread_id=None):
        received_at = time.monotonic()
        self.validate_message(prompt, request_id)
        if not isinstance(new, bool):
            raise ValueError('new 必须为布尔值')
        if thread_id is not None and (not isinstance(thread_id, str) or len(thread_id) > 200):
            raise ValueError('对话编号无效')
        if new and thread_id:
            raise ValueError('新对话不能指定已有对话编号')
        fingerprint = hashlib.sha256(json.dumps([prompt, new, thread_id], ensure_ascii=False).encode()).hexdigest()
        with self.operation_lock:
            previous = self._receipt(request_id, fingerprint)
            if previous:
                return dict(self.snapshot(True, previous['thread_id']), accepted_thread_id=previous['thread_id'])
            with self.cv:
                self.request_timings[request_id] = dict(received=received_at, phases=set())
                while len(self.request_timings) > 256:
                    self.request_timings.popitem(last=False)
            self.timing(request_id, 'computer_received', received_at)
            rejected = self.store.receipt(request_id) or {}
            # A failed desktop handoff may already have created an empty record.
            # An explicit retry reuses it; it never creates a second conversation.
            ident = rejected.get('thread_id', '') if new else self.ident(thread_id or self.preferred)
            receipt = dict(fingerprint=fingerprint, thread_id=ident, status='sending')
            self.store.record(request_id, receipt)
            sent = False
            try:
                if not ident:
                    ident = self.create_empty(prompt.strip().splitlines()[0])
                    receipt['thread_id'] = ident
                    self.store.record(request_id, receipt)
                self.activate(ident)
                live = self.desktop.peek(ident)
                with self.desktop.lock:
                    session = self.desktop.sessions[ident]
                    live = session.snapshot(self.desktop.connected)
                    # Turn completion can precede the native runtime-idle patch.
                    # Publish completion promptly while still refusing a new task
                    # until the desktop owner has finished transitioning.
                    if not live.get('can_send'):
                        raise DesktopUnavailable('该对话正在执行，请刷新后补充当前回合')
                    owner = session.owner
                    previous_turn_id = live['turn_id']
                    previous_started_at = session.latest_turn().get('turnStartedAtMs') or 0
                sent = True
                result = self.desktop.request('thread-follower-start-turn', dict(conversationId=ident,
                    turnStart=dict(request=dict(threadId=ident, input=[native_text(prompt)],
                        clientUserMessageId=request_id), context=dict(inheritThreadSettings=True))), owner, timeout=20)
                self.timing(request_id, 'native_received', time.monotonic())
                turn = result.get('result', {}).get('result', {}).get('turn', {})
                with self.desktop.lock:
                    current = self.desktop.sessions.get(ident)
                    if current is not None:
                        current.expect_turn(turn.get('id', ''), request_id, previous_turn_id, prompt,
                            previous_started_at)
                receipt.update(status='accepted', turn_id=turn.get('id', ''))
                self.store.record(request_id, receipt)
                self.preferred = ident
                self.publish(True)
                value = self.snapshot(True, ident)
                value.update(accepted_thread_id=ident, request_id=request_id)
                if turn.get('id') and value.get('turn_id') != turn['id']:
                    value.update(turn_id=turn['id'], state='starting', message='电脑已接收',
                        can_send=False, reply_preview=None, summary='', task=prompt[:300])
                return value
            except Exception as exc:
                receipt['status'] = 'unknown' if sent and not isinstance(exc, DesktopUnavailable) else 'rejected'
                self.store.record(request_id, receipt)
                raise

    def target(self, thread_id, turn_id, pending_id=None):
        self.ident(thread_id)
        with self.desktop.lock:
            session = self.desktop.sessions.get(thread_id)
            if not self.desktop.connected or session is None or not session.valid:
                raise DesktopUnavailable('桌面连接已断开，操作未发送')
            live = session.snapshot(True)
            if not turn_id or live['turn_id'] != turn_id:
                raise RuntimeError('当前回合已变化，操作未发送')
            if pending_id is not None:
                pending = next(((view, raw) for view, raw in session.pending() if view['id'] == pending_id), None)
                if pending is None:
                    raise RuntimeError('此请求已处理或失效，请刷新')
                if pending[0]['turn_id'] != turn_id:
                    raise RuntimeError('确认所属回合已变化')
                return session.owner, live, pending
            return session.owner, live

    def steer(self, prompt, request_id, thread_id, turn_id):
        self.validate_message(prompt, request_id)
        fingerprint = hashlib.sha256(json.dumps([prompt, thread_id, turn_id], ensure_ascii=False).encode()).hexdigest()
        with self.operation_lock:
            # Preserve uncertain v0.4 supplement delivery receipts across upgrades.
            old = self.legacy.state.get('supplements', {}).get(request_id)
            if old:
                if old['fingerprint'] != fingerprint or old['status'] != 'accepted':
                    raise DeliveryUnknown('原补充的送达状态不明，请先查看会话')
                return dict(self.snapshot(True, thread_id), supplement_accepted=True)
            if self._receipt(request_id, fingerprint):
                return dict(self.snapshot(True, thread_id), supplement_accepted=True)
            owner, live = self.target(thread_id, turn_id)
            if not live.get('can_steer'):
                raise DesktopUnavailable(live.get('steer_unavailable_reason', '此客户端不支持安全补充'))
            if live['pending'] or live['state'] != 'running':
                raise RuntimeError('请先处理确认或等待当前任务建立')
            with self.desktop.lock:
                data = self.desktop.sessions[thread_id].data
                restore = dict(id=request_id, cwd=data.get('cwd'), context=dict(
                    workspaceRoots=(data.get('currentPermissions') or {}).get('runtimeWorkspaceRoots') or [data.get('cwd')],
                    collaborationMode=data.get('latestCollaborationMode')))
            receipt = dict(fingerprint=fingerprint, thread_id=thread_id, turn_id=turn_id, status='sending')
            self.store.record(request_id, receipt)
            try:
                self.desktop.request('thread-follower-steer-turn', dict(conversationId=thread_id,
                    input=[native_text(prompt)], restoreMessage=restore, clientUserMessageId=request_id), owner, timeout=15)
                receipt['status'] = 'accepted'
            except Exception as exc:
                receipt['status'] = 'rejected' if isinstance(exc, DesktopUnavailable) else 'unknown'
                raise
            finally:
                self.store.record(request_id, receipt)
            return dict(self.snapshot(True, thread_id), supplement_accepted=True)

    def answer(self, token, decision=None, answers=None, thread_id=None, turn_id=None):
        with self.operation_lock:
            owner, _, (view, raw) = self.target(thread_id, turn_id, token)
            if not view['supported']:
                raise RuntimeError('此类请求请在电脑处理')
            params = dict(conversationId=thread_id, requestId=raw['id'])
            if view['kind'] == 'approval':
                if decision not in [o['value'] for o in view['decisions']]:
                    raise ValueError('此确认选项不适用')
                params['decision'] = decision
                method = 'thread-follower-command-approval-decision' if raw['method'].startswith('item/commandExecution') else 'thread-follower-file-approval-decision'
            else:
                if not isinstance(answers, dict) or any(not isinstance(answers.get(q['id']), str)
                        or not answers[q['id']].strip() or len(answers[q['id']]) > 4000 for q in view['questions']):
                    raise ValueError('请回答全部问题')
                for q in view['questions']:
                    if q.get('isOther') is False and q.get('options') and answers[q['id']] not in [o['label'] for o in q['options']]:
                        raise ValueError('请选择电脑提供的原始选项')
                params['response'] = dict(answers={q['id']: dict(answers=[answers[q['id']]]) for q in view['questions']})
                method = 'thread-follower-submit-user-input'
            self.desktop.request(method, params, owner)
        return self.snapshot(True, thread_id)

    def stop(self, thread_id=None, turn_id=None):
        with self.operation_lock:
            owner, live = self.target(thread_id, turn_id)
            if live['state'] not in ('running', 'waiting'):
                raise RuntimeError('此回合已经结束')
            self.desktop.request('thread-follower-interrupt-turn', dict(conversationId=thread_id,
                expectedTurnId=turn_id, mode='user-stop'), owner)
        return self.snapshot(True, thread_id)
