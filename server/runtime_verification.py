"""Machine-local evidence gate. Merely finding an RPC never enables a write."""
import json
import os
from pathlib import Path
import re
import subprocess


def running_desktop_build():
    if os.name != 'nt':
        return ''
    try:
        query = "Get-CimInstance Win32_Process | Where-Object {$_.Name -eq 'ChatGPT.exe'} | Select-Object -ExpandProperty ExecutablePath -Unique"
        result = subprocess.run(['powershell.exe', '-NoProfile', '-NonInteractive', '-Command', query],
            capture_output=True, text=True, timeout=10, creationflags=subprocess.CREATE_NO_WINDOW)
        values = set(re.findall(r'OpenAI\.Codex_[^\\\r\n]+', result.stdout))
        return next(iter(values)) if len(values) == 1 else ''
    except (OSError, subprocess.SubprocessError):
        return ''


def verified_actions(path):
    try:
        record = json.loads(Path(path).read_text(encoding='utf-8'))
        if record.get('format') != 1 or not record.get('desktop_build') or record['desktop_build'] != running_desktop_build():
            return set()
        return {name for name, evidence in record.get('actions', {}).items()
            if isinstance(evidence, dict) and evidence.get('passed') is True and evidence.get('tested_at')
            and evidence.get('evidence_file')}
    except (OSError, ValueError, TypeError, AttributeError):
        return set()
