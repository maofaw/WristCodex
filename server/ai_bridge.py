"""Single-task, authenticated-by-parent Codex bridge. No shell command interpolation."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import threading
import time
import uuid

HERE = Path(__file__).resolve().parent
WORKSPACE = Path(os.environ.get('WATCHREMOTE_WORKSPACE', str(HERE.parent / 'workspace')))
STATE = HERE / 'ai-last-task.json'
LOCK = threading.RLock()
PROC = None
JOB = {'state': 'idle', 'message': '尚未提交任务'}
if STATE.exists():
    try:
        JOB = json.loads(STATE.read_text(encoding='utf-8'))
        if JOB.get('state') in ('queued', 'running', 'stopping'):
            JOB.update(state='interrupted', message='服务重启，之前任务状态无法确认')
    except (ValueError, OSError):
        pass

def save():
    temp = STATE.with_suffix('.tmp')
    temp.write_text(json.dumps(JOB, ensure_ascii=False), encoding='utf-8')
    temp.replace(STATE)

def child_environment():
    env = os.environ.copy()
    # The CLI does not automatically inherit the Windows browser proxy.
    if os.name == 'nt' and not env.get('HTTPS_PROXY') and not env.get('https_proxy'):
        import winreg
        try:
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r'Software\Microsoft\Windows\CurrentVersion\Internet Settings') as key:
                enabled = winreg.QueryValueEx(key, 'ProxyEnable')[0]
                proxy = winreg.QueryValueEx(key, 'ProxyServer')[0]
            if enabled and proxy:
                mapping = dict(item.split('=', 1) for item in proxy.split(';') if '=' in item)
                https = mapping.get('https') or mapping.get('http') or (proxy if not mapping else '')
                if https:
                    address = https if '://' in https else 'http://' + https
                    env['HTTPS_PROXY'] = address
                    env.setdefault('HTTP_PROXY', address)
                    env.setdefault('NO_PROXY', 'localhost,127.0.0.1')
        except OSError:
            pass
    return env

def snapshot():
    with LOCK:
        return dict(JOB)

def submit(prompt, request_id):
    global JOB
    if not isinstance(prompt, str) or not prompt.strip() or len(prompt) > 4000:
        raise ValueError('任务必须为 1–4000 字符的文字')
    if not isinstance(request_id, str) or not request_id or len(request_id) > 100:
        raise ValueError('缺少有效请求编号')
    with LOCK:
        if JOB.get('request_id') == request_id:
            return dict(JOB)
        if JOB['state'] in ('queued', 'running', 'stopping'):
            raise RuntimeError('已有任务执行中，请先查看或停止')
        JOB = {'id': str(uuid.uuid4()), 'request_id': request_id, 'prompt': prompt.strip(),
               'state': 'queued', 'message': '任务已接收', 'result': '', 'started': time.time()}
        save()
        threading.Thread(target=execute, args=(JOB['id'],), daemon=True).start()
        return dict(JOB)

def terminate(p):
    if p.poll() is not None:
        return
    if os.name == 'nt':
        subprocess.run(['taskkill', '/PID', str(p.pid), '/T', '/F'], capture_output=True,
                       creationflags=subprocess.CREATE_NO_WINDOW, timeout=10)
    else:
        p.terminate()

def stop():
    with LOCK:
        if JOB['state'] not in ('queued', 'running', 'stopping'):
            return dict(JOB)
        JOB.update(state='stopping', message='已请求停止；已完成的文件修改不会撤销')
        save()
        p = PROC
    if p is not None:
        terminate(p)
    return snapshot()

def execute(job_id):
    global PROC
    p = None
    try:
        WORKSPACE.mkdir(exist_ok=True)
        output_dir = HERE / 'ai-results'
        output_dir.mkdir(exist_ok=True)
        output = output_dir / (job_id + '.txt')
        log = output_dir / (job_id + '.log')
        candidates = list((Path(os.environ.get('LOCALAPPDATA', '')) / 'OpenAI' / 'Codex' / 'bin').glob('*/codex.exe'))
        codex = str(max(candidates, key=lambda x: x.stat().st_mtime)) if candidates else shutil.which('codex')
        if not codex:
            raise RuntimeError('找不到 Codex 程序，请在电脑安装并登录 Codex')
        with LOCK:
            if JOB['state'] == 'stopping':
                JOB.update(state='interrupted', message='任务已停止'); save(); return
            prompt = JOB['prompt']
            JOB.update(state='running', message='Codex 正在执行'); save()
            # Never grants escalation: operations outside the workspace fail under the sandbox.
            cmd = [codex, '-a', 'never', 'exec', '--sandbox', 'workspace-write',
                   '--skip-git-repo-check', '--ephemeral', '--color', 'never',
                   '--cd', str(WORKSPACE), '--output-last-message', str(output), '-']
            log_file = log.open('wb')
            try:
                p = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=log_file, stderr=subprocess.STDOUT,
                                     cwd=WORKSPACE, env=child_environment(), creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
            finally:
                log_file.close()
            PROC = p
        p.stdin.write((prompt + '\n\n请用简洁中文回答。任务工作目录为当前目录；若操作超出允许权限，说明原因，不要绕过限制。').encode('utf-8'))
        p.stdin.close()
        try:
            code = p.wait(timeout=1200)
        except subprocess.TimeoutExpired:
            terminate(p)
            raise RuntimeError('任务超过 20 分钟，已停止')
        with LOCK:
            if JOB['state'] == 'stopping':
                JOB.update(state='interrupted', message='任务已停止')
            elif code == 0 and output.exists():
                JOB.update(state='completed', message='执行完成', result=output.read_text(encoding='utf-8')[:20000])
            else:
                detail = log.read_text(encoding='utf-8', errors='replace')[-1500:]
                JOB.update(state='failed', message='Codex 执行失败，请检查电脑登录或权限', result=detail)
            JOB['finished'] = time.time(); save()
    except Exception as exc:
        if p is not None and p.poll() is None:
            terminate(p)
        with LOCK:
            JOB.update(state='failed', message=str(exc)[:500]); save()
    finally:
        with LOCK:
            if PROC is p:
                PROC = None

def display(job):
    labels = {'idle':'未开始','queued':'已接收','running':'执行中','stopping':'停止中',
              'completed':'已完成','failed':'失败','interrupted':'已中止'}
    return '\n'.join(filter(None, [labels.get(job['state'], job['state']),
                                  job.get('message'), job.get('result', '')[:1800]]))
