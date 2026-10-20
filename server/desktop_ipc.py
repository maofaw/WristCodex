"""Versioned follower connection to the *running* Windows Codex desktop.

Never creates a router, starts a model, resumes a thread in another executor,
or edits the desktop's files. All writes go through the native owner client.
"""
from collections import OrderedDict
import ctypes
import hashlib
import json
import os
import queue
import struct
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor


PIPE = r'\\.\pipe\codex-ipc'
VERSIONS = {
    'thread-owner-discovery': 1,
    'thread-follower-start-turn': 2,
    'thread-follower-steer-turn': 1,
    'thread-follower-interrupt-turn': 4,
    'thread-follower-read-model-settings': 1,
    'thread-follower-command-approval-decision': 1,
    'thread-follower-file-approval-decision': 1,
    'thread-follower-submit-user-input': 1,
}
MAX_FRAME = 64 * 1024 * 1024  # PC-side only; snapshots are projected immediately.


class DesktopUnavailable(RuntimeError):
    pass


class DeliveryUnknown(RuntimeError):
    pass


class NativePipe:
    def __init__(self, path=PIPE):
        if os.name != 'nt':
            raise DesktopUnavailable('当前仅支持 Windows 桌面客户端')
        import msvcrt
        self.file = open(path, 'r+b', buffering=0)
        self.handle = msvcrt.get_osfhandle(self.file.fileno())
        self.kernel = ctypes.WinDLL('kernel32', use_last_error=True)
        self.kernel.PeekNamedPipe.argtypes = [ctypes.c_void_p, ctypes.c_void_p,
            ctypes.c_uint32, ctypes.c_void_p, ctypes.POINTER(ctypes.c_uint32), ctypes.c_void_p]
        self.kernel.PeekNamedPipe.restype = ctypes.c_int
        self.buffer = bytearray()

    def write(self, value):
        body = json.dumps(value, ensure_ascii=False, separators=(',', ':')).encode('utf-8')
        if not 0 < len(body) <= MAX_FRAME:
            raise ValueError('桌面请求过大')
        data = memoryview(struct.pack('<I', len(body)) + body)
        while data:
            written = self.file.write(data)
            if not written:
                raise OSError('桌面连接已关闭')
            data = data[written:]

    def read(self, timeout=1):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if len(self.buffer) >= 4:
                size = struct.unpack_from('<I', self.buffer)[0]
                if not 0 < size <= MAX_FRAME:
                    raise ValueError('桌面状态帧大小不受支持')
                if len(self.buffer) >= size + 4:
                    raw = bytes(self.buffer[4:size + 4])
                    del self.buffer[:size + 4]
                    return json.loads(raw)
            available = ctypes.c_uint32()
            if not self.kernel.PeekNamedPipe(self.handle, None, 0, None, ctypes.byref(available), None):
                raise ctypes.WinError(ctypes.get_last_error())
            if available.value:
                self.buffer.extend(self.file.read(min(available.value, 65536)))
            else:
                time.sleep(.04)
        raise TimeoutError('等待桌面事件')

    def close(self):
        self.file.close()


def patch_value(root, patch):
    """Apply an Immer JSON patch to the small requests projection only."""
    path = patch['path']
    if not path:
        return patch.get('value')
    parent = root
    for key in path[:-1]:
        parent = parent[key]
    key = path[-1]
    if patch['op'] == 'remove':
        del parent[key]
    elif isinstance(parent, list) and patch['op'] == 'add':
        parent.insert(key, patch['value'])
    else:
        parent[key] = patch['value']
    return root


def native_text(text):
    """Native owner input, including the desktop renderer's required elements."""
    return dict(type='text', text=text, text_elements=[])


def turns_in(state):
    history = (state.get('turnHistory') or {}).get('history') or {}
    return list(history.get('entitiesByKey', {}).items()) or [
        (str(i), t) for i, t in enumerate(state.get('turns') or [])]


class Projection:
    """Keep headers, pending requests and bounded review data, never full history."""
    FIELDS = ('id', 'hostId', 'title', 'cwd', 'threadRuntimeStatus',
        'latestModel', 'latestCollaborationMode', 'currentPermissions', 'latestThreadSettings')

    def __init__(self, owner):
        self.owner = owner
        self.revision = -1
        self.valid = False
        self.data = {}
        self.turns = OrderedDict()
        self.requests = []
        self.items = {}
        self.reply = ''
        self.stage = '电脑正在处理'
        self.current_key = None
        self.message_items = []
        self.preview = None
        self.preview_version = 0
        self.preview_body_hash = None

    def remember_turn(self, key, turn):
        self.turns[key] = {k: turn.get(k) for k in ('turnId', 'status', 'turnStartedAtMs', 'error')}
        params = turn.get('params') or {}
        self.turns[key]['request_id'] = params.get('clientUserMessageId') or ''
        self.turns[key]['reviewer'] = params.get('approvalsReviewer')
        self.turns[key]['task'] = '\n'.join(x.get('text', '') for x in params.get('input', [])
            if x.get('type') == 'text')[:300]
        while len(self.turns) > 4:
            oldest = min(self.turns, key=lambda k: self.turns[k].get('turnStartedAtMs') or 0)
            del self.turns[oldest]

    @staticmethod
    def project_item(item):
        # Keep current-turn indexes and public body only. Tool arguments, output
        # and reasoning text never enter the watch projection.
        if not isinstance(item, dict):
            return {}
        if item.get('type') == 'error':
            # Error messages/details may contain private endpoints and tokens.
            return dict(type='error', willRetry=item.get('willRetry') is True)
        value = {k: item.get(k) for k in ('id', 'type', 'phase')}
        if item.get('type') == 'agentMessage' and item.get('phase') in (None, 'commentary', 'final_answer'):
            text = str(item.get('text') or '')
            value['text'] = text[:20000]
            value['_text_digest'] = item.get('_text_digest') or hashlib.sha256(text.encode()).hexdigest()
        if item.get('type') == 'fileChange':
            changes = item.get('changes') or []
            if len(json.dumps(changes, ensure_ascii=False)) <= 10000:
                value['changes'] = changes
        return value

    def remember_item(self, item, update_stage=True):
        if not isinstance(item, dict):
            return
        kind = item.get('type')
        if kind == 'fileChange':
            changes = item.get('changes') or []
            detail = json.dumps(changes, ensure_ascii=False)
            if changes and len(detail) <= 10000 and item.get('id'):
                self.items[item['id']] = detail
                if len(self.items) > 16:
                    self.items.pop(next(iter(self.items)))
        stages = {'reasoning': '正在思考', 'commandExecution': '正在执行电脑操作',
            'fileChange': '正在修改文件', 'webSearch': '正在查询资料',
            'mcpToolCall': '正在调用工具', 'agentMessage': '正在整理回复'}
        if update_stage:
            if kind == 'error':
                self.stage = '模型连接重试中' if item.get('willRetry') is True else '电脑正在处理'
            elif kind in stages:
                self.stage = stages[kind]

    def clear_body(self):
        self.message_items = []
        self.reply = ''
        self.preview = None
        self.items.clear()
        self.stage = '电脑正在处理'

    def select_current(self, source=None):
        key = max(self.turns, key=lambda k: self.turns[k].get('turnStartedAtMs') or 0, default=None)
        if key != self.current_key:
            self.current_key = key
            self.clear_body()
        if source is not None and key is not None and key in source:
            items = source[key].get('items') or []
            if not isinstance(items, list) or len(items) > 2048:
                raise ValueError('当前回合消息结构不受支持')
            self.message_items = [self.project_item(i) for i in items]
            self.stage = '电脑正在处理'
            for item in self.message_items:
                self.remember_item(item)

    def refresh_body(self, update_stage=True):
        if update_stage:
            self.stage = '电脑正在处理'
        self.reply = ''
        self.items.clear()
        body = None
        for index, item in enumerate(self.message_items):
            self.remember_item(item, update_stage)
            if item.get('type') == 'agentMessage':
                body = (index, item)
                if item.get('phase') != 'commentary':
                    self.reply = item.get('text') or ''
        candidate = None
        turn = self.latest_turn()
        ended = turn.get('status') in ('completed', 'failed', 'interrupted')
        if (body and body[1].get('text') and body[1].get('id') and turn.get('turnId')
                and not (ended and body[1].get('phase') == 'commentary')):
            index, item = body
            candidate = dict(thread_id=self.data.get('id', ''), turn_id=turn['turnId'],
                item_id=item['id'], text=item['text'][:1000],
                complete=ended,
                clipped=len(item['text']) > 1000)
        old = {k: v for k, v in (self.preview or {}).items() if k != 'version'} or None
        body_hash = body[1].get('_text_digest') if candidate else None
        if candidate != old or body_hash != self.preview_body_hash:
            self.preview_version += 1
        self.preview_body_hash = body_hash
        self.preview = dict(candidate, version=self.preview_version) if candidate else None

    def load_turns(self, entries):
        previous, previous_hash = self.preview, self.preview_body_hash
        self.turns.clear()
        latest = sorted(entries, key=lambda pair: pair[1].get('turnStartedAtMs') or 0)[-4:]
        self.clear_body()
        self.current_key = None
        for key, turn in latest:
            self.remember_turn(key, turn)
        self.select_current(dict(latest))
        self.preview, self.preview_body_hash = previous, previous_hash

    def patch_turn(self, key, path, patch):
        if not path:
            if patch['op'] == 'remove':
                self.turns.pop(key, None)
                self.select_current()
            else:
                self.remember_turn(key, patch['value'])
                self.select_current({key: patch['value']})
            return
        if key not in self.turns:
            return  # Old turns outside the bounded projection are irrelevant.
        if len(path) == 1 and path[0] in ('turnId', 'status', 'turnStartedAtMs', 'error'):
            previous_id = self.latest_turn().get('turnId')
            self.turns[key][path[0]] = patch.get('value')
            self.select_current()
            if previous_id != self.latest_turn().get('turnId'):
                self.clear_body()
        elif path == ['params', 'approvalsReviewer']:
            self.turns[key]['reviewer'] = patch.get('value')
        elif path == ['params', 'clientUserMessageId']:
            self.turns[key]['request_id'] = patch.get('value') or ''
        elif path[0] == 'items' and key == self.current_key:
            tail = path[1:]
            if not tail:
                values = patch.get('value') or []
                if not isinstance(values, list) or len(values) > 2048:
                    raise ValueError('当前回合消息结构不受支持')
                self.message_items = [self.project_item(i) for i in values]
                self.refresh_body()
            elif len(tail) == 1:
                value = dict(patch, path=tail)
                if patch['op'] != 'remove':
                    value['value'] = self.project_item(patch['value'])
                self.message_items = patch_value(self.message_items, value)
                if len(self.message_items) > 2048:
                    raise ValueError('当前回合消息过多')
                if patch['op'] == 'remove':
                    self.refresh_body()
                else:
                    self.remember_item(self.message_items[tail[0]])
            else:
                index, field = tail[:2]
                item = self.message_items[index]
                if field in ('id', 'type', 'phase', 'text', 'changes', 'willRetry'):
                    if field == 'text' and item.get('type') != 'agentMessage':
                        self.remember_item(item)
                        return
                    if field == 'text':
                        item.pop('_text_digest', None)
                    item = patch_value(item, dict(patch, path=tail[1:]))
                    self.message_items[index] = self.project_item(item)
                # A resumed native reasoning item can precede an older retry in
                # array order. Use event order for stage, without keeping its text.
                self.remember_item(self.message_items[index])

    def accept(self, change):
        if change.get('type') == 'snapshot':
            state = change['conversationState']
            if state.get('hostId') != 'local':
                raise ValueError('只支持本机桌面对话')
            self.data = {k: state.get(k) for k in self.FIELDS}
            self.requests = state.get('requests') or []
            if not isinstance(self.requests, list):
                raise ValueError('待确认结构不受支持')
            self.load_turns(turns_in(state))
            self.refresh_body()
            self.revision = change['revision']
            self.valid = True
            return
        if not self.valid or change.get('baseRevision') != self.revision:
            self.valid = False
            self.clear_body()
            raise ValueError('桌面状态版本不连续')
        for patch in change.get('patches') or []:
            path = patch.get('path') or []
            if not path:
                raise ValueError('不支持的桌面状态更新')
            if path[0] == 'requests':
                self.requests = patch_value(self.requests, dict(patch, path=path[1:]))
                if not isinstance(self.requests, list):
                    raise ValueError('待确认结构不受支持')
            elif len(path) == 1 and path[0] in self.FIELDS:
                self.data[path[0]] = patch.get('value')
            elif path[0] in self.FIELDS and isinstance(self.data.get(path[0]), (dict, list)):
                self.data[path[0]] = patch_value(self.data[path[0]], dict(patch, path=path[1:]))
            elif path in (['turnHistory'], ['turnHistory', 'history'],
                    ['turnHistory', 'history', 'entitiesByKey']):
                value = patch.get('value') or {}
                for field in ('history', 'entitiesByKey')[len(path) - 1:]:
                    value = value.get(field) or {}
                self.load_turns(list(value.items()))
            elif path[:3] == ['turnHistory', 'history', 'entitiesByKey'] and len(path) >= 4:
                self.patch_turn(path[3], path[4:], patch)
            elif path[0] == 'turns':
                if len(path) == 1:
                    self.load_turns([(str(i), t) for i, t in enumerate(patch.get('value') or [])])
                else:
                    self.patch_turn(str(path[1]), path[2:], patch)
        self.refresh_body(update_stage=False)
        self.revision = change['revision']

    def latest_turn(self):
        return max(self.turns.values(), key=lambda t: t.get('turnStartedAtMs') or 0, default={})

    def pending(self):
        result = []
        for request in self.requests:
            if not isinstance(request, dict):
                continue
            params = request.get('params') or {}
            if not isinstance(params, dict) or not isinstance(request.get('id'), (str, int)):
                continue
            if params.get('threadId') != self.data.get('id'):
                continue
            method = request.get('method')
            reviewer = (params.get('approvalsReviewer') or self.latest_turn().get('reviewer')
                or (self.data.get('currentPermissions') or {}).get('approvalsReviewer') or 'user')
            if method in ('item/commandExecution/requestApproval', 'item/fileChange/requestApproval') and reviewer != 'user':
                # An automatic reviewer deciding an operation is not a request for
                # the human. Never race that review with a watch approval.
                continue
            identity = [self.data['id'], params.get('turnId'), request.get('id')]
            token = 'desktop:' + hashlib.sha256(json.dumps(identity).encode()).hexdigest()
            view = dict(id=token, thread_id=self.data['id'], turn_id=params.get('turnId') or '',
                title=self.data.get('title') or '电脑对话', summary=(params.get('reason') or '电脑需要你决定')[:120],
                kind='manual', detail='请在电脑处理此类请求', supported=False)
            if method in ('item/commandExecution/requestApproval', 'item/fileChange/requestApproval'):
                command = method == 'item/commandExecution/requestApproval'
                detail = params.get('command') if command else self.items.get(params.get('itemId'))
                options = params.get('availableDecisions')
                if options is None:
                    options = ['accept', 'decline', 'cancel']
                labels = {'accept': '仅本次允许', 'decline': '拒绝', 'cancel': '取消任务'}
                decisions = [dict(value=o, label=labels[o]) for o in options if isinstance(o, str) and o in labels]
                if detail and len(detail) <= 10000 and decisions:
                    view.update(kind='approval', supported=True, detail=detail,
                        summary=(params.get('reason') or ('允许执行这项电脑操作？' if command else '允许这些文件修改？'))[:120],
                        decisions=decisions)
            elif method == 'item/tool/requestUserInput':
                questions = params.get('questions') or []
                if isinstance(questions, list) and 0 < len(questions) <= 6 and len(json.dumps(questions, ensure_ascii=False)) <= 16000 and all(
                        isinstance(q, dict) and isinstance(q.get('id'), str) and isinstance(q.get('question'), str)
                        and not q.get('isSecret') and len(q['question']) <= 4000
                        and isinstance(q.get('options') or [], list) and len(q.get('options') or []) <= 10
                        and all(isinstance(o, dict) and isinstance(o.get('label'), str)
                            and len(o['label']) <= 200 and len(str(o.get('description') or '')) <= 1000
                            for o in (q.get('options') or []))
                        for q in questions) and len({q['id'] for q in questions}) == len(questions):
                    view.update(kind='questions', supported=True, questions=questions,
                        summary=questions[0].get('question', '需要你回答')[:120])
            result.append((view, request))
        return result

    def snapshot(self, connected=True):
        turn = self.latest_turn()
        pending = [v for v, _ in self.pending()]
        runtime = (self.data.get('threadRuntimeStatus') or {}).get('type')
        terminal = turn.get('status') in ('completed', 'failed', 'interrupted')
        state = ('waiting' if pending else turn['status'] if terminal else
            'running' if runtime == 'active' else turn.get('status') or 'idle')
        if state == 'inProgress':
            state = 'running' if runtime == 'active' else 'interrupted'
        message = {'waiting': '等待你确认', 'completed': '任务完成', 'interrupted': '任务已停止',
            'failed': '执行失败', 'idle': '就绪'}.get(state, self.stage)
        if not connected or not self.valid:
            message = '电脑客户端未连接，草稿已保留'
        return dict(thread_id=self.data.get('id', ''), title=self.data.get('title') or '电脑对话',
            turn_id=turn.get('turnId') or (pending[0]['turn_id'] if pending else ''),
            request_id=turn.get('request_id') or '', state=state, message=message,
            summary=self.reply[:180] if state in ('completed', 'failed', 'interrupted') else '',
            task=turn.get('task') or '', pending=pending[:1], pending_count=len(pending),
            reply_preview=dict(self.preview) if self.preview and connected and self.valid else None,
            needs_attention=state == 'failed' or bool(pending), readonly=not connected or not self.valid,
            desktop=True, source='shared', can_send=connected and self.valid,
            # The current native follower steer RPC has no expectedTurnId. Do not
            # risk steering a replacement turn if the owner changes it in flight.
            can_steer=False, steer_unavailable_reason='电脑当前接口不支持绑定回合的补充，请等本轮结束')


class DesktopIPC:
    def __init__(self, on_change=lambda: None, pipe_factory=NativePipe):
        self.on_change = on_change
        self.pipe_factory = pipe_factory
        self.lock = threading.RLock()
        self.write_lock = threading.Lock()
        self.pipe = None
        self.client_id = 'initializing-client'
        self.connected = False
        self.error = '等待桌面客户端连接'
        self.sessions = {}
        self.waiters = {}
        self.running = False
        self.pool = ThreadPoolExecutor(max_workers=4, thread_name_prefix='desktop-follow')
        self.scheduled = set()

    def start(self):
        with self.lock:
            if self.running:
                return
            self.running = True
        threading.Thread(target=self.listen, name='desktop-ipc', daemon=True).start()

    def close(self):
        self.running = False
        if self.pipe:
            self.pipe.close()
        self.pool.shutdown(wait=False, cancel_futures=True)

    def write(self, message):
        with self.write_lock:
            if self.pipe is None:
                raise DesktopUnavailable('电脑客户端未连接')
            self.pipe.write(message)

    def request(self, method, params, target=None, timeout=8):
        ident = uuid.uuid4().hex
        waiter = queue.Queue(maxsize=1)
        with self.lock:
            if not self.connected:
                raise DesktopUnavailable('电脑客户端未连接，不会另开执行器')
            self.waiters[ident] = waiter
        message = dict(type='request', requestId=ident, sourceClientId=self.client_id,
            version=VERSIONS[method], method=method, params=params, timeoutMs=int(timeout * 1000))
        if target:
            message['targetClientId'] = target
        try:
            self.write(message)
            response = waiter.get(timeout=timeout + 1)
            if response.get('resultType') != 'success':
                error = response.get('error', '桌面请求失败')
                if error in ('no-client-found', 'request-version-mismatch', 'no-handler-for-request'):
                    raise DesktopUnavailable(error)
                raise DeliveryUnknown('桌面是否接收尚未确认，请先查看会话')
            return response
        except queue.Empty as exc:
            raise DeliveryUnknown('桌面响应超时，不会重复提交') from exc
        finally:
            with self.lock:
                self.waiters.pop(ident, None)

    def discover(self, ident):
        response = self.request('thread-owner-discovery', dict(hostId='local', conversationId=ident), timeout=2)
        owner = response['handledByClientId']
        with self.lock:
            current = self.sessions.get(ident)
            if current is None or current.owner != owner:
                self.sessions[ident] = Projection(owner)
            elif current.valid:
                return current
        self.follow(ident, owner)
        deadline = time.monotonic() + 4
        while self.connected and time.monotonic() < deadline:
            with self.lock:
                current = self.sessions.get(ident)
                if current and current.valid:
                    return current
            time.sleep(.04)
        raise DesktopUnavailable('等待电脑接入该对话，请稍后重试')

    def follow(self, ident, owner):
        self.write(dict(type='broadcast', method='thread-stream-following-changed', version=1,
            sourceClientId=self.client_id, targetClientIds=[owner],
            params=dict(hostId='local', conversationId=ident, following=True)))

    def schedule(self, ident):
        with self.lock:
            if ident in self.scheduled or not self.running:
                return
            self.scheduled.add(ident)
        def work():
            try:
                self.discover(ident)
            except (RuntimeError, OSError):
                pass
            finally:
                with self.lock:
                    self.scheduled.discard(ident)
        self.pool.submit(work)

    def peek(self, ident):
        with self.lock:
            value = self.sessions.get(ident)
            return value.snapshot(self.connected) if value and value.data else None

    def handle(self, message):
        kind = message.get('type')
        if kind == 'response':
            with self.lock:
                waiter = self.waiters.get(message.get('requestId'))
                if waiter and waiter.empty():
                    waiter.put(message)
            return
        if kind == 'client-discovery-request':
            self.write(dict(type='client-discovery-response', requestId=message['requestId'], response=dict(canHandle=False)))
            return
        if kind == 'request':
            self.write(dict(type='response', requestId=message['requestId'], resultType='error', error='no-handler-for-request'))
            return
        if kind != 'broadcast':
            return
        method = message.get('method')
        params = message.get('params') or {}
        if method == 'client-status-changed' and params.get('status') == 'disconnected':
            with self.lock:
                for state in self.sessions.values():
                    if state.owner == params.get('clientId'):
                        state.valid = False
            self.on_change()
        if params.get('hostId') != 'local':
            return
        ident = params.get('conversationId')
        if not isinstance(ident, str) or not ident or len(ident) > 200:
            return
        if method == 'thread-stream-following-status-requested' and message.get('version') == 1:
            self.schedule(ident)
        elif method == 'thread-stream-state-changed':
            with self.lock:
                state = self.sessions.get(ident)
                if state is None or state.owner != message.get('sourceClientId'):
                    return
                if message.get('version') != 11:
                    state.valid = False
                    self.error = '电脑客户端协议已变化，暂时只读'
                    self.on_change()
                    return
                before = state.snapshot(self.connected)
                try:
                    change = params['change']
                    if change.get('type') == 'snapshot' and change.get('conversationState', {}).get('id') != ident:
                        raise ValueError('会话编号不一致')
                    state.accept(params['change'])
                except (ValueError, KeyError, TypeError, IndexError):
                    state.valid = False
                    state.clear_body()
                    self.follow(ident, state.owner)
                after = state.snapshot(self.connected)
                refresh = before['state'] in ('running', 'waiting') and after['state'] not in ('running', 'waiting')
                if before != after:
                    self.on_change()
                if refresh:
                    self.follow(ident, state.owner)

    def listen(self):
        while self.running:
            try:
                pipe = self.pipe_factory()
                self.pipe = pipe
                ident = uuid.uuid4().hex
                pipe.write(dict(type='request', requestId=ident, sourceClientId='initializing-client',
                    version=0, method='initialize', params=dict(clientType='wristcodex')))
                deadline = time.monotonic() + 5
                while time.monotonic() < deadline:
                    response = pipe.read(1)
                    if response.get('requestId') == ident and response.get('type') == 'response':
                        if response.get('resultType') != 'success':
                            raise DesktopUnavailable('桌面握手失败')
                        self.client_id = response['result']['clientId']
                        break
                else:
                    raise DesktopUnavailable('桌面握手超时')
                self.connected = True
                self.error = ''
                self.on_change()
                for thread in list(self.sessions):
                    self.schedule(thread)
                while self.running:
                    try:
                        self.handle(pipe.read(1))
                    except TimeoutError:
                        pass
            except (OSError, RuntimeError, ValueError, KeyError) as exc:
                self.error = '电脑客户端未连接' if isinstance(exc, OSError) else str(exc)[:160]
            finally:
                self.connected = False
                if self.pipe:
                    self.pipe.close()
                self.pipe = None
                with self.lock:
                    for state in self.sessions.values():
                        state.valid = False
                    for waiter in self.waiters.values():
                        if waiter.empty():
                            waiter.put(dict(resultType='error', error='client-disconnected'))
                self.on_change()
            for _ in range(30):
                if not self.running:
                    return
                time.sleep(.1)
