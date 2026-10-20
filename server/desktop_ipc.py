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

    def remember_item(self, item):
        if not isinstance(item, dict):
            return
        kind = item.get('type')
        if kind == 'agentMessage' and item.get('phase') != 'commentary':
            self.reply = str(item.get('text') or '')[:20000]
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
        if kind in stages:
            self.stage = stages[kind]

    def accept(self, change):
        if change.get('type') == 'snapshot':
            state = change['conversationState']
            if state.get('hostId') != 'local':
                raise ValueError('只支持本机桌面对话')
            self.data = {k: state.get(k) for k in self.FIELDS}
            self.requests = state.get('requests') or []
            if not isinstance(self.requests, list):
                raise ValueError('待确认结构不受支持')
            self.turns.clear()
            latest = sorted(turns_in(state), key=lambda pair: pair[1].get('turnStartedAtMs') or 0)[-4:]
            for key, turn in latest:
                self.remember_turn(key, turn)
            if latest:
                self.reply = ''
                self.items.clear()
                for item in latest[-1][1].get('items') or []:
                    self.remember_item(item)
            self.revision = change['revision']
            self.valid = True
            return
        if not self.valid or change.get('baseRevision') != self.revision:
            self.valid = False
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
            elif path[:3] == ['turnHistory', 'history', 'entitiesByKey'] and len(path) >= 4:
                key = path[3]
                if len(path) == 4 and patch.get('op') != 'remove':
                    self.remember_turn(key, patch['value'])
                    for item in patch['value'].get('items') or []:
                        self.remember_item(item)
                elif key in self.turns and len(path) == 5 and path[4] in ('turnId', 'status', 'turnStartedAtMs', 'error'):
                    self.turns[key][path[4]] = patch.get('value')
                elif key in self.turns and path[4:] == ['params', 'approvalsReviewer']:
                    self.turns[key]['reviewer'] = patch.get('value')
                elif key in self.turns and 'items' in path and isinstance(patch.get('value'), dict):
                    self.remember_item(patch['value'])
            elif path[0] == 'turns' and len(path) == 2 and isinstance(patch.get('value'), dict):
                self.remember_turn(str(path[1]), patch['value'])
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
        state = 'waiting' if pending else 'running' if runtime == 'active' else turn.get('status') or 'idle'
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
