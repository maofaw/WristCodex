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
import hashlib
from ai_bridge import child_environment, WORKSPACE
from conversations import ConversationStore, Conversations

class Assistant:
    def __init__(self, state_file=None):
        self.file = Path(state_file or Path(__file__).with_name('assistant-state.json'))
        self.store=ConversationStore(self.file)
        self.cv = threading.Condition(threading.RLock())
        self.write_lock = threading.Lock()
        self.start_lock = threading.Lock()
        self.steer_lock = threading.Lock()
        self.proc = None
        self.waiters = {}
        self.counter = 0
        self.pending = {}
        self.last_delta = 0
        self.file_changes = {}
        self.timer = None
        self.state = dict(revision=0, state='idle', thread_id='', turn_id='', reply='',
                          message='准备接收任务', request_id='', pending=[], task='', supplements={})
        if self.file.exists():
            try:
                self.state.update(json.loads(self.file.read_text(encoding='utf-8')))
                self.state['pending'] = []
                if self.state['state'] in ('running', 'waiting', 'starting'):
                    self.state.update(state='interrupted', message='电脑服务已重启，可继续对话')
            except (ValueError, OSError):
                pass
        self.loaded = False
        if self.state['thread_id']:
            self.store.register(self.state['thread_id'],self.state.get('task','').split('\n')[0])
            self.store.save(self.state)
        self.conversations=Conversations(self)

    def snapshot(self, compact=False):
        with self.cv:
            value=json.loads(json.dumps(self.state))
            value.pop('supplements',None)
            value['protocol_version']=4
            value['title']=self.store.index.get(value['thread_id'],{}).get('title','新对话')
            reply=value.get('reply','').strip()
            value['summary']=reply[:180] if value['state'] in ('completed','failed','interrupted') else ''
            value['needs_attention']=value['state']=='failed' or bool(value['pending']) or (
                value['state']=='interrupted' and value['message']=='电脑服务已重启，可继续对话')
            if compact:value.pop('reply',None)
            return value

    def changed(self, **values):
        with self.cv:
            self.state.update(values)
            self.state['revision'] += 1
            self.state['pending'] = [x['view'] for x in self.pending.values()]
            self.file.parent.mkdir(exist_ok=True)
            tmp = self.file.with_suffix('.tmp')
            tmp.write_text(json.dumps(self.state, ensure_ascii=False), encoding='utf-8')
            tmp.replace(self.file)
            self.store.save(self.state)
            self.cv.notify_all()

    def events(self, after, compact=False):
        with self.cv:
            self.cv.wait_for(lambda: self.state['revision'] > after, timeout=20)
            return self.snapshot(compact)

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
            self.call('initialize',dict(clientInfo=dict(name='wristcodex',title='WristCodex',version='0.4.0'),
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
            detail=((params.get('reason') or '')+'\n'+(params.get('command') or params.get('grantRoot') or '文件修改')).strip()
            if len(detail)>10000:
                self.write(dict(id=event['id'],result=dict(decision='decline')))
                self.changed(message='操作详情过长，已拒绝；请在电脑处理')
                return
            view=dict(id=token,kind='approval',title='需要你的确认',detail=detail)
            reason=(params.get('reason') or '').strip()
            view['summary']=(reason.splitlines()[0][:120] if reason else
                ('电脑请求修改文件，请查看详情后决定' if method=='item/fileChange/requestApproval' else '电脑请求执行命令，请查看详情后决定'))
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
            questions=params.get('questions',[])
            view=dict(id=token,kind='questions',title='需要你决定',questions=questions,
                      summary=(questions[0].get('question','需要你选择下一步')[:120] if questions else '需要你选择下一步'))
        else:
            # Unsupported approval types are rejected, never automatically granted.
            if method=='item/permissions/requestApproval':
                self.write(dict(id=event['id'],result=dict(permissions={},scope='turn')))
            else:self.write(dict(id=event['id'],error=dict(code=-32601,message='请在电脑处理此类请求')))
            return
        with self.cv:
            self.pending[token]=dict(rpc_id=event['id'],method=method,view=view)
            self.changed(state='waiting',message='等待你确认')

    def check_target(self,thread_id=None,turn_id=None):
        if thread_id is not None and thread_id!=self.state['thread_id']:raise RuntimeError('对话已变化，操作未发送')
        if turn_id is not None and turn_id!=self.state['turn_id']:raise RuntimeError('任务已变化，操作未发送')

    def answer(self, token, decision=None, answers=None,thread_id=None,turn_id=None):
        with self.cv:
            self.check_target(thread_id,turn_id)
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
            if method=='item/started':
                item=p.get('item',{});kind=item.get('type')
                if kind=='fileChange':self.file_changes[item['id']]=item.get('changes',[])
                stages={'commandExecution':'正在执行电脑操作','fileChange':'正在修改文件',
                        'webSearch':'正在查询资料','mcpToolCall':'正在调用工具',
                        'agentMessage':'正在整理回复','reasoning':'正在思考'}
                stage=stages.get(kind)
                if stage and not self.pending and self.state['message']!=stage:self.changed(message=stage)
            elif method=='turn/started':
                self.changed(turn_id=p['turn']['id'],state='running',message='Codex 正在处理')
            elif method=='item/agentMessage/delta':
                self.state['reply']=(self.state['reply']+p.get('delta',''))[-20000:]
                # Text streams stay on the PC; the watch only needs phase changes.
            elif method=='item/completed' and p.get('item',{}).get('type')=='agentMessage':
                self.state['reply']=p['item'].get('text',self.state['reply'])[-20000:]
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

    def send(self, prompt, request_id, new=False,thread_id=None):
        if not isinstance(new,bool):raise ValueError('new 必须为布尔值')
        if thread_id is not None and (not isinstance(thread_id,str) or len(thread_id)>200):raise ValueError('对话编号无效')
        if not isinstance(prompt,str) or not prompt.strip() or len(prompt)>4000:raise ValueError('请输入 1–4000 字符')
        if not isinstance(request_id,str) or not request_id or len(request_id)>100:raise ValueError('请求编号无效')
        fingerprint=hashlib.sha256(json.dumps([prompt,new,thread_id],ensure_ascii=False).encode()).hexdigest()
        # Validate provenance outside the RPC-reader lock. Reading a PC conversation
        # never makes it writable, and browsing never selects the execution target.
        if thread_id and not new:self.conversations.writable(thread_id)
        with self.cv:
            receipt=self.store.receipt(request_id)
            if receipt:
                if receipt['fingerprint']!=fingerprint:raise ValueError('请求编号与原消息不一致')
                return dict(self.snapshot(),accepted_thread_id=receipt.get('thread_id',''))
            if request_id==self.state['request_id']:
                return dict(self.snapshot(),accepted_thread_id=self.state['thread_id'])
            if self.state['state'] in ('starting','running','waiting'):raise RuntimeError('当前任务未结束，请先回答或停止')
            target='' if new else (thread_id if thread_id is not None else self.state['thread_id'])
            if target!=self.state['thread_id']:
                self.store.save(self.state)
                self.state['thread_id']=target;self.loaded=False
            self.store.record(request_id,dict(fingerprint=fingerprint,thread_id=target))
            self.file_changes.clear()
            self.changed(state='starting',message='正在连接 Codex',request_id=request_id,reply='',turn_id='',task=prompt.strip()[:300],supplements={})
        threading.Thread(target=self.start_turn,args=(prompt,request_id),daemon=True).start()
        return dict(self.snapshot(),accepted_thread_id=target)

    def steer(self,prompt,request_id,thread_id,turn_id):
        if not isinstance(prompt,str) or not prompt.strip() or len(prompt)>4000:raise ValueError('请输入 1–4000 字符')
        if not isinstance(request_id,str) or not request_id or len(request_id)>100:raise ValueError('请求编号无效')
        fingerprint=hashlib.sha256(json.dumps([prompt,thread_id,turn_id],ensure_ascii=False).encode()).hexdigest()
        with self.steer_lock:
            with self.cv:
                receipts=self.state.setdefault('supplements',{})
                previous=receipts.get(request_id)
                if previous:
                    if previous['fingerprint']!=fingerprint:raise ValueError('补充编号已被其他内容使用')
                    if previous['status']=='accepted':return dict(self.snapshot(),supplement_accepted=True)
                    raise RuntimeError('补充是否送达尚未确认，请先查看当前任务，不要重复提交')
                if self.pending:raise RuntimeError('请先处理确认选项，再补充任务')
                if self.state['state']!='running' or not turn_id or thread_id!=self.state['thread_id'] or turn_id!=self.state['turn_id']:
                    raise RuntimeError('当前任务已变化，请刷新；补充未发送')
                if len(receipts)>=32:raise RuntimeError('本次任务已达到 32 次补充上限，请等待任务结束')
                receipts[request_id]=dict(fingerprint=fingerprint,status='sending')
                self.changed()
            try:
                result=self.call('turn/steer',dict(threadId=thread_id,expectedTurnId=turn_id,
                    input=[dict(type='text',text=prompt)]),timeout=15)
                if result.get('turnId')!=turn_id:raise RuntimeError('返回的任务编号不匹配')
            except Exception as exc:
                with self.cv:
                    receipts[request_id]['status']='unknown';self.changed()
                raise RuntimeError('无法确认补充是否送达，请先查看当前任务，不要重复提交') from exc
            with self.cv:
                receipts[request_id]['status']='accepted';self.changed()
                return dict(self.snapshot(),supplement_accepted=True)

    def start_turn(self,prompt,request_id=None):
        try:
            self.ensure()
            if not self.loaded:
                params=dict(approvalPolicy='on-request',approvalsReviewer='user',sandbox='workspace-write',excludeTurns=True)
                thread_id=self.state['thread_id']
                if thread_id:
                    params['threadId']=thread_id
                    result=self.call('thread/resume',params)
                else:
                    params.pop('excludeTurns',None)
                    params['cwd']=str(WORKSPACE)
                    params['developerInstructions']='用简洁中文回复，适合手表阅读。沿用上下文。权限或决策需要用户确认时等待，不要自行批准。'
                    result=self.call('thread/start',params)
                ident=result['thread']['id']
                self.store.register(ident,prompt.strip().split('\n')[0])
                if request_id:
                    receipt=self.store.receipt(request_id)
                    if receipt:self.store.record(request_id,dict(receipt,thread_id=ident))
                self.changed(thread_id=ident)
                self.loaded=True
            target=self.state['thread_id']
            result=self.call('turn/start',dict(threadId=target,input=[dict(type='text',text=prompt)]))
            with self.cv:
                if (request_id is None or self.state['request_id']==request_id) and self.state['thread_id']==target and self.state['state'] in ('running','waiting','starting'):
                    turn=result.get('turn',{}).get('id') or self.state['turn_id']
                    self.timer=threading.Timer(1200,self.expire,args=(request_id,target,turn));self.timer.daemon=True;self.timer.start()
        except Exception as exc:
            with self.cv:
                if request_id is None or self.state['request_id']==request_id:self.changed(state='failed',message=str(exc)[:1000])

    def expire(self,request_id,thread_id,turn_id):
        # A delayed timer from a finished conversation must never stop another one.
        with self.cv:
            if request_id is not None and request_id!=self.state['request_id']:return
        try:self.stop(thread_id,turn_id)
        except RuntimeError:pass

    def stop(self,thread_id=None,turn_id=None):
        with self.cv:
            self.check_target(thread_id,turn_id)
            target=self.state['thread_id'];turn=self.state['turn_id'];status=self.state['state']
        if turn and status in ('running','waiting'):
            self.call('turn/interrupt',dict(threadId=target,turnId=turn))
        elif status=='starting':raise RuntimeError('正在连接，请稍后再停止')
        return self.snapshot()

assistant=Assistant()
