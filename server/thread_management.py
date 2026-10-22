"""Current-thread settings and narrow metadata operations, never model execution."""
import copy
import time

from decisions import digest
from desktop_ipc import DesktopUnavailable, DeliveryUnknown


class ThreadManagement:
    def operation_delivery(self, kind, ident, operation_id):
        if kind not in ('settings', 'manage') or not isinstance(ident, str) or not isinstance(operation_id, str):
            return 'not_sent'
        receipt = self.store.receipt(kind + ':' + digest([ident, operation_id]))
        return receipt.get('status', 'unknown') if receipt else 'not_sent'

    def open_on_desktop(self, ident):
        self.ident(ident)
        if not self.desktop.connected:
            raise DesktopUnavailable('电脑客户端未连接')
        metadata = self.conversations.rpc('thread/read', dict(threadId=ident, includeTurns=False))['thread']
        if metadata.get('id') != ident:
            raise RuntimeError('对话编号不一致，未打开其他对话')
        self.open_thread(ident)
        return dict(thread_id=ident, opened=True)

    def model_catalog(self):
        cached = getattr(self, '_model_catalog_cache', None)
        if cached and time.monotonic() - cached[0] < 60:
            return copy.deepcopy(cached[1])
        self.ensure()
        models, modes = [], []
        result = self.call('model/list', dict(limit=50, includeHidden=False))
        for item in result.get('data', [])[:50]:
            ident = item.get('model') or item.get('id')
            if not isinstance(ident, str) or not ident or len(ident) > 200:
                continue
            efforts = [e.get('reasoningEffort') for e in item.get('supportedReasoningEfforts', []) if isinstance(e, dict)]
            models.append(dict(id=ident, name=str(item.get('displayName') or ident)[:120],
                efforts=[e for e in efforts if isinstance(e, str) and len(e) <= 30]))
        presets = self.call('collaborationMode/list', {})
        for item in presets.get('data', []):
            if item.get('mode') in ('default', 'plan') and item['mode'] not in modes:
                modes.append(item['mode'])
        value = dict(models=models, modes=modes)
        self._model_catalog_cache = (time.monotonic(), value)
        return copy.deepcopy(value)

    def _settings_values(self, ident):
        self.refresh_verification()
        self.ident(ident)
        with self.desktop.lock:
            session = self.desktop.sessions.get(ident)
            if not self.desktop.connected or session is None or not session.valid:
                raise DesktopUnavailable('请先连接电脑上的此对话')
            data = session.data
            collaboration = data.get('latestCollaborationMode') or {}
            saved = data.get('latestThreadSettings') or {}
            model = (collaboration.get('settings') or {}).get('model') or data.get('latestModel') or saved.get('model')
            effort = data.get('latestReasoningEffort', saved.get('effort'))
            value = dict(thread_id=ident, model=model, effort=effort, mode=collaboration.get('mode'),
                version=digest([ident, model, effort, collaboration]), scope='current_thread')
            live = session.snapshot(True)
            idle = live['state'] not in ('starting', 'running', 'waiting') and not live.get('pending')
            value['can_save'] = idle and 'settings' in self.verified_capabilities
            value['unavailable_reason'] = '' if value['can_save'] else '请等待任务和确认处理结束' if not idle else '本机设置接口尚未通过验证，请在电脑设置'
            return value

    def thread_settings(self, ident):
        value = self._settings_values(ident)
        try:
            value.update(self.model_catalog())
        except (RuntimeError, OSError, ValueError):
            value.update(models=[], modes=[], can_save=False, unavailable_reason='暂时无法读取电脑提供的模型和模式，请稍后刷新或回电脑设置')
        return value

    def _operation_receipt(self, kind, ident, operation_id, fingerprint):
        if not isinstance(operation_id, str) or not operation_id or len(operation_id) > 100:
            raise ValueError('缺少操作编号')
        key = kind + ':' + digest([ident, operation_id])
        old = self.store.receipt(key)
        if old:
            if old.get('fingerprint') != fingerprint:
                raise ValueError('同一操作编号不能提交不同内容')
            if old.get('status') in ('sending', 'unknown'):
                raise DeliveryUnknown('操作送达未知，请先在电脑核对，不会重复发送')
            if old.get('status') == 'accepted':
                return key, old
            raise RuntimeError('原操作未成功，请刷新后重新决定')
        return key, None

    def save_thread_settings(self, ident, changes, expected_version, operation_id):
        self.ident(ident)
        if not isinstance(changes, dict) or not changes or set(changes) - {'model', 'effort', 'mode'}:
            raise ValueError('只能修改当前对话的模型、思考强度或规划模式')
        fingerprint = digest([ident, changes, expected_version])
        with self.operation_lock:
            key, old = self._operation_receipt('settings', ident, operation_id, fingerprint)
            if old:
                return self.thread_settings(ident)
            current = self.thread_settings(ident)
            if current['version'] != expected_version:
                raise RuntimeError('对话设置已变化，请刷新后再修改')
            if not current['can_save']:
                if 'settings' not in self.verified_capabilities:
                    raise DesktopUnavailable(current['unavailable_reason'])
                raise RuntimeError(current['unavailable_reason'])
            model = changes.get('model', current['model'])
            effort = changes.get('effort', current['effort'])
            mode = changes.get('mode', current['mode'])
            selected = next((m for m in current['models'] if m['id'] == model), None)
            if selected is None or effort is not None and effort not in selected['efforts']:
                raise ValueError('请选择电脑实际提供的模型与思考强度')
            if mode not in current['modes']:
                raise ValueError('请选择电脑实际提供的规划或执行模式')
            patch = {}
            if 'model' in changes:
                patch['model'] = model
            if 'effort' in changes:
                patch['effort'] = effort
            if 'mode' in changes:
                patch['collaborationMode'] = dict(mode=mode, settings=dict(model=model,
                    reasoning_effort=effort, developer_instructions=None))
            with self.desktop.lock:
                owner = self.desktop.sessions[ident].owner
            receipt = dict(fingerprint=fingerprint, thread_id=ident, status='sending')
            self.store.record(key, receipt)
            try:
                result = self.desktop.request('thread-follower-update-thread-settings', dict(conversationId=ident,
                    threadSettings=patch, activeTurnId=None,
                    condition=dict(ifModelEquals=current['model'], ifEffortEquals=current['effort'])), owner)
                if result.get('result', {}).get('applied') is not True:
                    raise DesktopUnavailable('电脑未应用设置，请刷新后再决定')
                receipt['status'] = 'accepted'
            except Exception as exc:
                receipt['status'] = 'rejected' if isinstance(exc, DesktopUnavailable) else 'unknown'
                raise
            finally:
                self.store.record(key, receipt)
        # Display only values observed from the owner's subsequent state.
        value = self.thread_settings(ident)
        value['synchronizing'] = any(value.get(k) != v for k, v in changes.items())
        return value

    def thread_management(self, ident):
        self.ident(ident)
        metadata = self.conversations.rpc('thread/read', dict(threadId=ident, includeTurns=False))['thread']
        if metadata.get('id') != ident:
            raise RuntimeError('电脑返回了其他对话，操作已停止')
        title = metadata.get('name') or str(metadata.get('preview') or '').split('\n')[0] or '未命名对话'
        live = self.desktop.peek(ident)
        idle = bool(live and not live['readonly'] and live['state'] not in ('starting', 'running', 'waiting') and not live.get('pending'))
        capabilities = self.capabilities(live)
        for name in ('rename', 'archive'):
            if not idle:
                capabilities[name] = dict(available=False, reason='请先连接此对话，并等待任务和待处理项结束')
        return dict(thread_id=ident, title=title[:120], version=digest([ident, title, metadata.get('section'), metadata.get('path')]),
            capabilities={k: capabilities[k] for k in ('rename', 'archive', 'restore')})

    def manage_thread(self, ident, action, expected_version, operation_id, name=None, confirmed=False):
        self.ident(ident)
        if action not in ('rename', 'archive', 'restore'):
            raise ValueError('不支持的对话管理操作')
        if action == 'archive' and confirmed is not True:
            raise ValueError('请先确认归档此对话')
        if action == 'rename' and (not isinstance(name, str) or not name.strip() or len(name) > 120 or '\x00' in name):
            raise ValueError('对话名称应为1到120个字符')
        fingerprint = digest([ident, action, expected_version, name, confirmed])
        with self.operation_lock:
            key, old = self._operation_receipt('manage', ident, operation_id, fingerprint)
            if old:
                return dict(old['result'])
            info = self.thread_management(ident)
            if info['version'] != expected_version:
                raise RuntimeError('对话信息已变化，请刷新')
            capability = info['capabilities'][action]
            if not capability['available']:
                raise DesktopUnavailable(capability['reason'])
            receipt = dict(fingerprint=fingerprint, thread_id=ident, status='sending')
            self.store.record(key, receipt)
            try:
                method = {'rename': 'thread/name/set', 'archive': 'thread/archive', 'restore': 'thread/unarchive'}[action]
                params = dict(threadId=ident)
                if action == 'rename':
                    params['name'] = name
                with self.record_factory() as manager:
                    result = manager.call(method, params)
                if action == 'restore' and result.get('thread', {}).get('id') != ident:
                    raise DeliveryUnknown('恢复结果尚未同步，请在电脑核对')
                if action == 'restore':
                    try:
                        self.desktop.record_restored(ident)
                    except Exception as exc:
                        raise DeliveryUnknown('记录已恢复，电脑同步尚未确认；请刷新核对，不会重复恢复') from exc
                value = dict(thread_id=ident, action=action, title=name if action == 'rename' else info['title'], applied=True)
                receipt.update(status='accepted', result=value)
            except Exception as exc:
                receipt['status'] = 'rejected' if isinstance(exc, DesktopUnavailable) else 'unknown'
                raise
            finally:
                self.store.record(key, receipt)
        return value
