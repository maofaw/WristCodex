"""Private watch conversation index and bounded, read-only Codex history adapter."""
from collections import OrderedDict
import hashlib
import json
import math
from pathlib import Path
import threading
import time


class ConversationStore:
    def __init__(self, state_file):
        self.root=state_file.parent/(state_file.stem+'-conversations')
        self.lock=threading.RLock()
        self.index=self._read(self.root/'index.json',{})

    @staticmethod
    def _read(path, default):
        try:return json.loads(path.read_text(encoding='utf-8'))
        except (OSError,ValueError):return default

    def _write(self,path,value):
        path.parent.mkdir(parents=True,exist_ok=True)
        temp=path.with_suffix('.tmp')
        temp.write_text(json.dumps(value,ensure_ascii=False),encoding='utf-8')
        temp.replace(path)

    def _path(self,kind,ident):
        return self.root/kind/(hashlib.sha256(ident.encode()).hexdigest()+'.json')

    def owns(self,ident):
        with self.lock:return ident in self.index

    def register(self,ident,title=''):
        if not ident:return
        with self.lock:
            if ident not in self.index:
                self.index[ident]={'title':title[:120] or '手表对话','updated_at':int(time.time())}
                self._write(self.root/'index.json',self.index)

    def save(self,state):
        ident=state.get('thread_id','')
        with self.lock:
            if not self.owns(ident):return
            self._write(self._path('states',ident),state)

    def state(self,ident):
        with self.lock:return self._read(self._path('states',ident),{})

    def receipt(self,ident):
        with self.lock:return self._read(self._path('receipts',ident),{})

    def record(self,ident,value):
        with self.lock:self._write(self._path('receipts',ident),value)


class Conversations:
    def __init__(self,assistant):
        self.assistant=assistant
        self.store=assistant.store
        self.cache=OrderedDict()
        self.cache_lock=threading.Lock()

    @staticmethod
    def ident(value):
        if not isinstance(value,str) or not value or len(value)>200:raise ValueError('对话编号无效')
        return value

    def rpc(self,method,params):
        self.assistant.ensure()
        return self.assistant.call(method,params)

    def describe(self,thread):
        ident=thread['id']
        if thread.get('originator')=='wristcodex':self.store.register(ident,thread.get('name') or thread.get('preview','').split('\n')[0])
        owned=self.store.owns(ident)
        title=thread.get('name') or (self.store.index.get(ident,{}) .get('title') if owned else None) or thread.get('preview','').split('\n')[0] or '未命名对话'
        active=self.assistant.snapshot(True)
        return dict(id=ident,title=title[:120],readonly=not owned,source='watch' if owned else 'computer',
            updated_at=thread.get('updatedAt',0),active=ident==active['thread_id'],
            state=active['state'] if ident==active['thread_id'] else 'idle')

    def metadata(self,ident):
        self.ident(ident)
        return self.describe(self.rpc('thread/read',dict(threadId=ident,includeTurns=False))['thread'])

    def writable(self,ident):
        self.ident(ident)
        if not self.store.owns(ident) and self.metadata(ident)['readonly']:
            raise RuntimeError('电脑对话当前只读，请新建手表对话')

    def list(self,cursor=None,archived=False):
        if cursor is not None and (not isinstance(cursor,str) or len(cursor)>4096):raise ValueError('分页编号无效')
        params=dict(limit=20,cursor=cursor,sortKey='updated_at',sortDirection='desc',useStateDbOnly=True)
        if archived:params['archived']=True
        result=self.rpc('thread/list',params)
        rows=[self.describe(t) for t in result['data'] if not t.get('ephemeral') and not t.get('parentThreadId')]
        for row in rows:row['archived']=archived
        return dict(conversations=rows,next_cursor=result.get('nextCursor'),active=self.assistant.snapshot(True))

    @staticmethod
    def text(item):
        if item.get('type')=='agentMessage' and item.get('phase')!='commentary':return item.get('text','')
        if item.get('type')=='userMessage':
            return '\n'.join(c.get('text','') if c.get('type')=='text' else '[图片或附件]' for c in item.get('content',[]))
        return ''

    def remember(self,thread_id,item_id,text):
        key=(thread_id,item_id)
        with self.cache_lock:
            self.cache[key]=text[:20000]
            self.cache.move_to_end(key)
            while len(self.cache)>64:self.cache.popitem(last=False)

    def messages(self,ident,cursor=None):
        meta=self.metadata(ident)
        rows=[]
        # Skip a bounded run of tool-only pages; never fetch an entire rollout.
        for _ in range(3):
            result=self.rpc('thread/items/list',dict(threadId=ident,limit=10,cursor=cursor,sortDirection='desc'))
            for entry in result['data']:
                item=entry.get('item',entry)
                text=self.text(item)
                if not text:continue
                item_id=item['id'];self.remember(ident,item_id,text)
                rows.append(dict(id=item_id,role='user' if item['type']=='userMessage' else 'assistant',
                    text=text[:180],expandable=len(text)>180,clipped=len(text)>20000))
            cursor=result.get('nextCursor')
            if rows or not cursor:break
        rows.reverse()
        return dict(conversation=meta,messages=rows,next_cursor=cursor,active=self.assistant.snapshot(True))

    def message(self,ident,item_id,page):
        self.ident(ident)
        if not isinstance(item_id,str) or len(item_id)>200:raise ValueError('消息编号无效')
        if not isinstance(page,int) or page<0:raise ValueError('页码无效')
        with self.cache_lock:text=self.cache.get((ident,item_id))
        if text is None:raise RuntimeError('记录缓存已更新，请重新打开该对话后再试')
        total=max(1,math.ceil(len(text)/1000))
        if page>=total:raise ValueError('页码超出范围')
        return dict(text=text[page*1000:(page+1)*1000],page=page,pages=total)
