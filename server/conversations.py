"""Private watch conversation index and bounded, read-only Codex history adapter."""
from collections import OrderedDict
import hashlib
import json
import math
from pathlib import Path
import threading
import time


class ConversationStore:
    TERMINAL_RECEIPT_LIMIT = 1024
    TERMINAL_RECEIPT_DAYS = 30
    def __init__(self, state_file):
        self.root=state_file.parent/(state_file.stem+'-conversations')
        self.lock=threading.RLock()
        self.index=self._read(self.root/'index.json',{})
        self.receipts = {}
        self.legacy_guards = {}
        self.corrupt_receipts = set()
        # One directory read per store construction, never per pending request.
        for path in (self.root/'receipts').glob('*.json'):
            value = self._read(path, None)
            self.receipts[path.stem] = value
            self._index_guard(path.stem, value, 1)
        self._prune_terminal()


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

    @staticmethod
    def _valid_decision(value):
        return (isinstance(value, dict) and value.get('status') in
            ('sending', 'unknown', 'accepted', 'rejected') and
            all(isinstance(value.get(k), str) and value[k] for k in ('thread_id', 'turn_id')))

    @staticmethod
    def _legacy_message(value):
        # Older message dedup receipts used two fields, with some deployments
        # also persisting status=accepted before turn IDs were recorded.
        # This exemption applies to directory indexing only: an exact decision
        # key still requires the decision schema in receipt().
        return (isinstance(value, dict) and (set(value) == {'fingerprint','thread_id'}
                or set(value) == {'fingerprint','thread_id','status'} and value['status'] == 'accepted')
            and isinstance(value['thread_id'],str) and len(value['thread_id']) <= 200
            and isinstance(value['fingerprint'],str) and len(value['fingerprint']) == 64
            and all(c in '0123456789abcdef' for c in value['fingerprint']))

    def _index_guard(self, key, value, delta):
        if self._legacy_message(value): return
        if not isinstance(value, dict) or value.get('receipt_kind') != 'other' and not self._valid_decision(value):
            if delta > 0: self.corrupt_receipts.add(key)
            else: self.corrupt_receipts.discard(key)
            return
        if value.get('receipt_kind') == 'other' or value.get('decision_id'): return
        status = value.get('status')
        if status not in ('unknown','sending','accepted'): return
        bucket = (value['thread_id'], value['turn_id'], status)
        count = self.legacy_guards.get(bucket, 0) + delta
        if count: self.legacy_guards[bucket] = count
        else: self.legacy_guards.pop(bucket, None)

    def receipt(self,ident):
        with self.lock:
            key = self._path('receipts',ident).stem
            if key not in self.receipts: return {}
            value = self.receipts[key]
            if ident.startswith(('decision:', 'decision-request:')) and not self._valid_decision(value):
                return dict(status='unknown', receipt_corrupt=True)
            return dict(value) if isinstance(value,dict) else {}

    def record(self,ident,value):
        with self.lock:
            value = dict(value, receipt_kind='decision' if ident.startswith(('decision:', 'decision-request:')) else 'other')
            value.setdefault('updated_at', time.time())
            path = self._path('receipts',ident)
            self._write(path,value)
            if path.stem in self.receipts: self._index_guard(path.stem, self.receipts[path.stem], -1)
            self.receipts[path.stem] = value
            self._index_guard(path.stem,value,1)
            # Cleanup at most once per minute, outside request lookups.
            if time.monotonic() >= getattr(self, 'next_receipt_cleanup', 0): self._prune_terminal()

    def _prune_terminal(self):
        """30-day/1024-record limit for proven terminal records only.

        Ambiguous legacy records, corrupt records, sending, unknown and accepted
        without native removal evidence are retained without eviction.
        """
        terminal = []
        for key, value in self.receipts.items():
            if key in self.corrupt_receipts: continue
            if not isinstance(value,dict) or value.get('receipt_kind') not in ('decision','other'): continue
            if value.get('receipt_kind') == 'decision' and not self._valid_decision(value): continue
            if value.get('status') != 'rejected' and not (
                    value.get('status') == 'accepted'
                    and isinstance(value.get('processed_at'),(int,float))
                    and not isinstance(value['processed_at'],bool)
                    and math.isfinite(value['processed_at']) and value['processed_at'] > 0): continue
            stamp = value.get('updated_at')
            if isinstance(stamp, (float,int)) and not isinstance(stamp,bool) and math.isfinite(stamp): terminal.append((stamp,key))
        terminal.sort()
        cutoff = time.time()-self.TERMINAL_RECEIPT_DAYS*86400
        for i,(stamp,key) in enumerate(terminal):
            if stamp >= cutoff and i >= len(terminal)-self.TERMINAL_RECEIPT_LIMIT: continue
            try: (self.root/'receipts'/(key+'.json')).unlink()
            except OSError: continue
            self._index_guard(key,self.receipts.pop(key),-1)
        self.next_receipt_cleanup = time.monotonic()+60

    def decision_guard(self, token, thread_id, turn_id):
        with self.lock:
            if self.corrupt_receipts: return dict(status='unknown',receipt_corrupt=True)
            for status in ('unknown','sending','accepted'):
                if self.legacy_guards.get((thread_id,turn_id,status)):
                    return dict(status=status,thread_id=thread_id,turn_id=turn_id)
            return {}


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
