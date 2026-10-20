"""LAN HTTP listener for remote lock/sleep/status of a Windows PC.

Endpoints (GET, token required): /lock /sleep /status
Companion to the wearos-remote Wear OS app, reads TOKEN and port from
.env, same dir first then repo root (see sample.env). Run hidden at logon: startup shortcut
to pythonw.exe with this file as argument. Allow the port inbound in
Windows Firewall (private profile). For Wake-on-LAN, enable magic
packet wake on the NIC and disable fast startup.
"""
import ctypes
import subprocess
import threading
import json
import os
import hmac
import ai_bridge
from codex_session import assistant as legacy_assistant
from shared_assistant import SharedAssistant
import voice
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse, parse_qs

def configured_assistant():
  # Keep the deployed v0.4 path until the real desktop handoff gate passes.
  # Checking out development sources must not enable an incomplete backend.
  return (SharedAssistant(legacy_assistant) if os.environ.get('WATCHREMOTE_DESKTOP_SHARED') == '1'
          else legacy_assistant)

assistant = configured_assistant()

# same dir first (standalone copy), then repo root (in-repo copy)
here = Path(__file__).resolve()
env_path = next(
  (p for p in (here.parent / ".env", here.parents[1] / ".env") if p.exists()), None)
env = {}
if env_path is not None:
  env = dict(line.split("=", 1) for line in env_path.read_text(encoding="utf-8-sig").splitlines()
             if "=" in line and not line.startswith("#"))
TOKEN = os.environ.get("WATCHREMOTE_TOKEN", env.get("TOKEN", "")).strip()
if len(TOKEN) < 16 or TOKEN == "replace-with-your-random-token":
  raise SystemExit("Set TOKEN in server/.env to a random secret of at least 16 characters")
PORT = int(os.environ.get("WATCHREMOTE_PORT", str(urlparse(env.get("PC_BASE", "")).port or 8877)))
BIND = os.environ.get("WATCHREMOTE_BIND", env.get("BIND", "127.0.0.1"))

def session_locked():
  # LogonUI.exe runs while lock screen shown; false positives only during
  # brief UAC prompts. Upgrade path: WTSQuerySessionInformation if it annoys
  out = subprocess.run(
    ["tasklist", "/FI", "IMAGENAME eq LogonUI.exe", "/NH"],
    capture_output=True, text=True, creationflags=subprocess.CREATE_NO_WINDOW)
  return "LogonUI.exe" in out.stdout

class Handler(BaseHTTPRequestHandler):
  def reply_json(self, code, value):
    body=json.dumps(value,ensure_ascii=False).encode('utf-8')
    self.send_response(code)
    self.send_header('Content-Type','application/json; charset=utf-8')
    self.send_header('Content-Length',str(len(body)))
    self.end_headers()
    self.wfile.write(body)
  def reply(self, code, text):
    body = text.encode()
    self.send_response(code)
    self.send_header("Content-Type", "text/plain; charset=utf-8")
    self.send_header("Content-Length", str(len(body)))
    self.end_headers()
    self.wfile.write(body)

  def do_GET(self):
    url = urlparse(self.path)
    if parse_qs(url.query).get("t", [""])[0] != TOKEN:
      self.reply(403, "bad token")
      return
    if url.path == "/lock":
      ctypes.windll.user32.LockWorkStation()
      self.reply(200, "locked")
    elif url.path == "/sleep":
      self.reply(200, "sleeping")
      # delay so response reaches caller before NIC drops
      threading.Timer(1.0, lambda: ctypes.windll.powrprof.SetSuspendState(False, True, False)).start()
    elif url.path == "/status":
      self.reply(200, "awake, locked" if session_locked() else "awake, unlocked")
    elif url.path == "/ai/status":
      self.reply(200, ai_bridge.display(ai_bridge.snapshot()))
    elif url.path == '/assistant/state':
      try:
        query=parse_qs(url.query);compact=query.get('compact',['0'])[0]=='1'
        if len(query.get('thread_id',[''])[0])>200:raise ValueError('对话编号无效')
        self.reply_json(200,assistant.snapshot(compact,query['thread_id'][0]) if 'thread_id' in query else assistant.snapshot(compact))
      except ValueError as exc:self.reply(400,str(exc))
    elif url.path == '/assistant/events':
      try:
        after=int(parse_qs(url.query).get('after',['-1'])[0])
        query=parse_qs(url.query);compact=query.get('compact',['0'])[0]=='1'
        if len(query.get('thread_id',[''])[0])>200:raise ValueError('对话编号无效')
        self.reply_json(200,assistant.events(after,compact,query['thread_id'][0]) if 'thread_id' in query else assistant.events(after,compact))
      except ValueError:self.reply(400,'invalid revision')
    elif url.path == '/assistant/preferences':
      self.reply_json(200,assistant.preferences())
    elif url.path == '/assistant/attention':
      try:self.reply_json(200,assistant.attention(int(parse_qs(url.query).get('cursor',['0'])[0])))
      except ValueError as exc:self.reply(400,str(exc))
    elif url.path.startswith('/assistant/conversations'):
      try:
        query=parse_qs(url.query);cursor=query.get('cursor',[None])[0]
        if cursor is not None and len(cursor)>4096:raise ValueError('分页编号无效')
        ident=query.get('thread_id',[''])[0]
        if url.path=='/assistant/conversations':value=assistant.conversations.list(cursor)
        elif url.path=='/assistant/conversations/messages':value=assistant.conversations.messages(ident,cursor)
        elif url.path=='/assistant/conversations/message':
          value=assistant.conversations.message(ident,query.get('item_id',[''])[0],int(query.get('page',['0'])[0]))
        else:self.reply(404,'unknown');return
        self.reply_json(200,value)
      except (ValueError,UnicodeError) as exc:self.reply(400,str(exc))
      except RuntimeError as exc:self.reply(409,str(exc))
    else:
      self.reply(404, "unknown")

  def do_POST(self):
    url = urlparse(self.path)
    if not hmac.compare_digest(self.headers.get('Authorization', ''), 'Bearer ' + TOKEN):
      self.reply(403, 'bad token')
      return
    try:
      length = int(self.headers.get('Content-Length', '0'))
      if url.path == '/assistant/voice':
        if not 1000 <= length <= 1100000:
          self.reply(413,'录音长度无效');return
        self.connection.settimeout(30)
        self.reply_json(200,{'text':voice.transcribe(self.rfile.read(length))})
        return
      if length < 0 or length > 24000:
        self.reply(413, 'request too large'); return
      self.connection.settimeout(10)
      payload = json.loads(self.rfile.read(length) or b'{}')
      if not isinstance(payload, dict):
        raise ValueError('需要 JSON 对象')
      if url.path == '/ai/task':
        job = ai_bridge.submit(payload.get('prompt'), payload.get('request_id'))
        self.reply(202, ai_bridge.display(job))
      elif url.path == '/ai/stop':
        self.reply(200, ai_bridge.display(ai_bridge.stop()))
      elif url.path == '/assistant/send':
        self.reply_json(202,assistant.send(payload.get('prompt'),payload.get('request_id'),payload.get('new',False),payload.get('thread_id')))
      elif url.path == '/assistant/answer':
        self.reply_json(200,assistant.answer(payload.get('id'),payload.get('decision'),payload.get('answers'),payload.get('thread_id'),payload.get('turn_id')))
      elif url.path == '/assistant/steer':
        self.reply_json(200,assistant.steer(payload.get('prompt'),payload.get('request_id'),payload.get('thread_id'),payload.get('turn_id')))
      elif url.path == '/assistant/stop':
        self.reply_json(200,assistant.stop(payload.get('thread_id'),payload.get('turn_id')))
      elif url.path == '/assistant/preferences':
          self.reply_json(200,assistant.update_preferences(payload.get('desktop_attention_enabled')))
      elif url.path == '/assistant/activate':
          self.reply_json(200,assistant.activate(payload.get('thread_id')))
      else:
        self.reply(404, 'unknown')
    except (ValueError, UnicodeError) as exc:
      self.reply(400, str(exc))
    except RuntimeError as exc:
      self.reply(409, str(exc))

  def log_message(self, fmt, *args):
    pass

if __name__ == '__main__':
  if isinstance(assistant, SharedAssistant):assistant.start()
  try:
    ThreadingHTTPServer((BIND, PORT), Handler).serve_forever()
  finally:
    if isinstance(assistant, SharedAssistant):assistant.close()
