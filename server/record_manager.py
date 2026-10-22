"""Short-lived record management only; never runs or resumes a conversation."""
import json
import os
from pathlib import Path
import queue
import shutil
import subprocess
import threading

from ai_bridge import WORKSPACE, child_environment


class RecordManager:
    METHODS = frozenset(('thread/start', 'thread/name/set', 'thread/unsubscribe', 'thread/read',
        'thread/archive', 'thread/unarchive'))

    def __init__(self):
        self.proc = None
        self.counter = 0
        self.replies = queue.Queue()
        self.write_lock = threading.Lock()

    def __enter__(self):
        candidates = list((Path(os.environ.get('LOCALAPPDATA', '')) / 'OpenAI' / 'Codex' / 'bin').glob('*/codex.exe'))
        exe = str(max(candidates, key=lambda p: p.stat().st_mtime)) if candidates else shutil.which('codex')
        if not exe:
            raise RuntimeError('电脑没有找到 Codex')
        WORKSPACE.mkdir(parents=True, exist_ok=True)
        self.proc = subprocess.Popen([exe, 'app-server', '--stdio'], stdin=subprocess.PIPE,
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, encoding='utf-8',
            cwd=WORKSPACE, env=child_environment(), bufsize=1,
            creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        threading.Thread(target=self.read, name='empty-record-manager', daemon=True).start()
        try:
            self._call('initialize', dict(clientInfo=dict(name='wristcodex_record_manager', version='0.5.0'),
                capabilities=dict(experimentalApi=True)))
            self.write(dict(method='initialized', params={}))
            return self
        except Exception:
            self.close()
            raise

    def write(self, value):
        with self.write_lock:
            self.proc.stdin.write(json.dumps(value, ensure_ascii=False) + '\n')
            self.proc.stdin.flush()

    def read(self):
        try:
            for line in self.proc.stdout:
                try:
                    value = json.loads(line)
                except ValueError:
                    continue
                if 'id' in value and 'method' not in value:
                    self.replies.put(value)
                elif 'id' in value:
                    # A record manager cannot grant any permission or answer tools.
                    self.write(dict(id=value['id'], error=dict(code=-32601, message='Record management only')))
        except (OSError, ValueError):
            pass
        finally:
            self.replies.put(None)

    def _call(self, method, params):
        self.counter += 1
        ident = self.counter
        self.write(dict(id=ident, method=method, params=params))
        while True:
            try:
                result = self.replies.get(timeout=15)
            except queue.Empty as exc:
                raise RuntimeError('会话记录管理超时，任务未执行') from exc
            if result is None:
                raise RuntimeError('会话记录管理连接已关闭，任务未执行')
            if result.get('id') == ident:
                if 'error' in result:
                    raise RuntimeError(result['error'].get('message', '会话记录管理失败'))
                return result['result']

    def call(self, method, params):
        if method not in self.METHODS:
            raise ValueError('管理进程禁止执行、恢复或补充任务')
        return self._call(method, params)

    def close(self):
        proc = self.proc
        if proc is None:
            return
        try:
            proc.stdin.close()
        except OSError:
            pass
        try:
            proc.wait(timeout=3)
        except subprocess.TimeoutExpired:
            # Only this manager's own child, never the desktop conversation owner.
            proc.terminate()
            proc.wait(timeout=3)
        finally:
            if proc.poll() is not None:
                proc.stdout.close()
        if proc.poll() is None:
            raise RuntimeError('记录管理进程尚未退出，不能交给电脑执行')

    def __exit__(self, *args):
        self.close()
