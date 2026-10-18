"""Persistent Codex app-server session and bounded event notifications."""
import json
from pathlib import Path
import queue
import subprocess
import threading
import time
import uuid
import os
import shutil
from ai_bridge import child_environment, WORKSPACE

class Assistant:
    def __init__(self, state_file=None):
        self.file = Path(state_file or Path(__file__).with_name('assistant-state.json'))
        self.cv = threading.Condition(threading.RLock())
        self.write_lock = threading.Lock()
        self.start_lock = threading.Lock()
        self.proc = None
        self.waiters = {}
        self.counter = 0
        self.pending = {}
        self.last_delta = 0
        self.file_changes = {}
        self.timer = None
        self.state = dict(revision=0, state='idle', thread_id='', turn_id='', reply='',
                          message='准备接收任务', request_id='', pending=[])
        if self.file.exists():
            try:
                self.state.update(json.loads(self.file.read_text(encoding='utf-8')))
                self.state['pending'] = []
                if self.state['state'] in ('running', 'waiting', 'starting'):
                    self.state.update(state='interrupted', message='电脑服务已重启，可继续对话')
            except (ValueError, OSError):
                pass
        self.loaded = False

    def snapshot(self):
        with self.cv:
            return json.loads(json.dumps(self.state))

    def changed(self, **values):
        with self.cv:
            self.state.update(values)
            self.state['revision'] += 1
            self.state['pending'] = [x['view'] for x in self.pending.values()]
            self.file.parent.mkdir(exist_ok=True)
            tmp = self.file.with_suffix('.tmp')
            tmp.write_text(json.dumps(self.state, ensure_ascii=False), encoding='utf-8')
            tmp.replace(self.file)
            self.cv.notify_all()

    def events(self, after):
        with self.cv:
            self.cv.wait_for(lambda: self.state['revision'] > after, timeout=20)
            return self.snapshot()

    def write(self, obj):
        with self.write_lock:
            if self.proc is None or self.proc.poll() is not None:
                raise RuntimeError('Codex 连接已断开，请重新发送')
            self.proc.stdin.write(json.dumps(obj, ensure_ascii=False)+'\n')
            self.proc.stdin.flush()

    def call(self, method, params, timeout=45):
        with self.cv:
            self.counter += 1
            ident = self.counter
            waiter = queue.Queue(maxsize=1)
            self.waiters[ident] = waiter
        try:
            self.write(dict(id=ident, method=method, params=params))
            result = waiter.get(timeout=timeout)
            if 'error' in result:
                raise RuntimeError(result['error'].get('message', 'Codex 接口错误'))
            return result['result']
        except queue.Empty:
            raise RuntimeError('Codex 响应超时，请检查电脑连接')
        finally:
            with self.cv:
                self.waiters.pop(ident, None)

    def ensure(self):
        with self.start_lock:
            if self.proc is not None and self.proc.poll() is None:
                return
            candidates = list((Path(os.environ.get('LOCALAPPDATA',''))/'OpenAI'/'Codex'/'bin').glob('*/codex.exe'))
            exe = str(max(candidates,key=lambda p:p.stat().st_mtime)) if candidates else shutil.which('codex')
            if not exe:
                raise RuntimeError('电脑没有找到 Codex')
            WORKSPACE.mkdir(parents=True,exist_ok=True)
            self.proc = subprocess.Popen([exe,'app-server','--stdio'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,text=True,encoding='utf-8',bufsize=1,cwd=WORKSPACE,
                env=child_environment(),creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
            self.loaded = False
            threading.Thread(target=self.read,args=(self.proc,),daemon=True).start()
            self.call('initialize',dict(clientInfo=dict(name='wristcodex',title='WristCodex',version='0.2.0'),
                                      capabilities=dict(experimentalApi=True)))
            self.write(dict(method='initialized',params={}))

    def read(self, proc):
        try:
            for line in proc.stdout:
                try:
                    event=json.loads(line)
                except ValueError:
                    continue
                if 'id' in event and 'method' not in event:
                    with self.cv:
                        target=self.waiters.get(event['id'])
                    if target: target.put(event)
                elif 'id' in event:
                    self.request(event)
                else:
                    self.notify(event)
        finally:
            with self.cv:
                if self.proc is proc:
                    self.loaded=False
                    self.pending.clear()
                    if self.state['state'] in ('running','waiting','starting'):
                        self.changed(state='failed',message='Codex 连接中断，重新发送可恢复会话')
                    for target in self.waiters.values():
                        if target.empty():target.put({'error':{'message':'Codex 连接中断'}})

    def request(self, event):
        method=event['method'];params=event.get('params',{})
        if params.get('threadId') != self.state['thread_id']:
            self.write(dict(id=event['id'],error=dict(code=-32602,message='Unexpected thread')));return
        token=str(uuid.uuid4())
        if method in ('item/commandExecution/requestApproval','item/fileChange/requestApproval'):
            view=dict(id=token,kind='approval',title='需要你的确认',
                      detail=(params.get('reason','')+'\n'+(params.get('command') or params.get('grantRoot') or '文件修改'))[:3000])
            if method == 'item/fileChange/requestApproval':
                changes=self.file_changes.get(params.get('itemId'),[])
                detail=json.dumps(changes,ensure_ascii=False)
                if changes and len(detail)<=10000:
                    view['detail']+='\n'+detail
                else:
                    self.write(dict(id=event['id'],result=dict(decision='decline')))
                    self.changed(message='文件修改缺少可审阅内容，已拒绝；请在电脑检查')
                    return
        elif method == 'item/tool/requestUserInput':
            view=dict(id=token,kind='questions',title='需要你决定',questions=params.get('questions',[]))
        else:
            # Unsupported approval types are rejected, never automatically granted.
            if method=='item/permissions/requestApproval':
                self.write(dict(id=event['id'],result=dict(permissions={},scope='turn')))
            else:self.write(dict(id=event['id'],error=dict(code=-32601,message='请在电脑处理此类请求')))
            return
        with self.cv:
            self.pending[token]=dict(rpc_id=event['id'],method=method,view=view)
            self.changed(state='waiting',message='等待你确认')

    def answer(self, token, decision=None, answers=None):
        with self.cv:
            pending=self.pending.get(token)
            if pending is None:raise ValueError('确认已失效，请刷新')
            if pending['view']['kind']=='approval':
                if decision not in ('accept','decline'):raise ValueError('无效确认选项')
                result={'decision':decision}
            else:
                if not isinstance(answers,dict):raise ValueError('缺少问题答案')
                questions=pending['view']['questions']
                result={'answers':{q['id']:{'answers':[str(answers[q['id']])[:4000]]} for q in questions if q['id'] in answers}}
                if len(result['answers'])!=len(questions):raise ValueError('请回答全部问题')
            self.write(dict(id=pending['rpc_id'],result=result))
            self.pending.pop(token,None)
            self.changed(state='waiting' if self.pending else 'running',message='答案已提交')
        return self.snapshot()

    def notify(self, event):
        method=event.get('method');p=event.get('params',{})
        if p.get('threadId') and p['threadId']!=self.state['thread_id']:return
        with self.cv:
            if method=='item/started' and p.get('item',{}).get('type')=='fileChange':
                self.file_changes[p['item']['id']]=p['item'].get('changes',[])
            elif method=='turn/started':
                self.changed(turn_id=p['turn']['id'],state='running',message='Codex 正在处理')
            elif method=='item/agentMessage/delta':
                self.state['reply']=(self.state['reply']+p.get('delta',''))[-20000:]
                if time.monotonic()-self.last_delta>2:
                    self.last_delta=time.monotonic();self.changed()
            elif method=='item/completed' and p.get('item',{}).get('type')=='agentMessage':
                self.changed(reply=p['item'].get('text',self.state['reply'])[-20000:])
            elif method=='serverRequest/resolved':
                for key,value in list(self.pending.items()):
                    if value['rpc_id']==p.get('requestId'):self.pending.pop(key,None)
                self.changed()
            elif method=='turn/completed':
                turn=p['turn'];self.pending.clear()
                if self.timer:self.timer.cancel()
                state=turn.get('status','failed')
                message={'completed':'任务完成','interrupted':'任务已停止','failed':'执行失败'}.get(state,state)
                if turn.get('error'):message+='：'+str(turn['error'].get('message',''))[:500]
                self.changed(state=state,message=message)

    def send(self, prompt, request_id, new=False):
        if not isinstance(new,bool):raise ValueError('new 必须为布尔值')
        if not isinstance(prompt,str) or not prompt.strip() or len(prompt)>4000:raise ValueError('请输入 1–4000 字符')
        if not isinstance(request_id,str) or not request_id or len(request_id)>100:raise ValueError('请求编号无效')
        with self.cv:
            if request_id==self.state['request_id']:return self.snapshot()
            if self.state['state'] in ('starting','running','waiting'):raise RuntimeError('当前任务未结束，请先回答或停止')
            if new:self.state['thread_id']='';self.loaded=False
            self.changed(state='starting',message='正在连接 Codex',request_id=request_id,reply='')
        threading.Thread(target=self.start_turn,args=(prompt,),daemon=True).start()
        return self.snapshot()

    def start_turn(self,prompt):
        try:
            self.ensure()
            if not self.loaded:
                params=dict(cwd=str(WORKSPACE),approvalPolicy='on-request',approvalsReviewer='user',sandbox='workspace-write')
                thread_id=self.state['thread_id']
                if thread_id:
                    params['threadId']=thread_id
                    result=self.call('thread/resume',params)
                else:
                    params['developerInstructions']='用简洁中文回复，适合手表阅读。沿用上下文。权限或决策需要用户确认时等待，不要自行批准。'
                    result=self.call('thread/start',params)
                self.changed(thread_id=result['thread']['id'])
                self.loaded=True
            self.call('turn/start',dict(threadId=self.state['thread_id'],input=[dict(type='text',text=prompt)]))
            with self.cv:
                if self.state['state'] in ('running','waiting','starting'):
                    self.timer=threading.Timer(1200,self.stop);self.timer.daemon=True;self.timer.start()
        except Exception as exc:
            self.changed(state='failed',message=str(exc)[:1000])

    def stop(self):
        if self.state['turn_id'] and self.state['state'] in ('running','waiting'):
            self.call('turn/interrupt',dict(threadId=self.state['thread_id'],turnId=self.state['turn_id']))
        elif self.state['state']=='starting':raise RuntimeError('正在连接，请稍后再停止')
        return self.snapshot()

assistant=Assistant()
