package com.n.watchremote;
import java.io.*;
import java.nio.file.*;
import java.util.*;
/** Metadata only. Missing entries in an incomplete discovery are never completed. */
final class AttentionLedger {
 final File file;final LinkedHashMap<String,String> records=new LinkedHashMap<>();
 AttentionLedger(File file)throws IOException{this.file=file;if(file.exists())try(DataInputStream in=new DataInputStream(new FileInputStream(file))){if(in.readInt()!=1)throw new IOException("提醒记录版本不支持");int n=in.readInt();if(n<0||n>8192)throw new IOException("提醒记录过大");for(int i=0;i<n;i++)records.put(in.readUTF(),in.readUTF());}}
 String key(String scope,String id){return scope+":"+id;}
 void receive(String scope,Collection<String> ids)throws IOException{LinkedHashMap<String,String> next=new LinkedHashMap<>(records);for(String id:ids)next.putIfAbsent(key(scope,id),"received:"+System.currentTimeMillis());save(next);}
 List<String> eligible(String scope,Collection<String> ids){List<String> result=new ArrayList<>();for(String id:ids){String state=records.getOrDefault(key(scope,id),"");if(!state.startsWith("posted:")&&!state.startsWith("completed:"))result.add(id);}return result;}
 void completed(String scope,String id)throws IOException{LinkedHashMap<String,String> next=new LinkedHashMap<>(records);next.put(key(scope,id),"completed:"+System.currentTimeMillis());save(next);}
 void posted(String scope,Collection<String> ids)throws IOException{LinkedHashMap<String,String> next=new LinkedHashMap<>(records);for(String id:ids)next.put(key(scope,id),"posted:"+System.currentTimeMillis());save(next);}
 interface Post {void submit()throws IOException;}
 boolean deliver(String scope,Collection<String> ids,java.util.function.BooleanSupplier gate,Post post)throws IOException{
  receive(scope,ids);List<String> fresh=eligible(scope,ids);if(fresh.isEmpty()||!gate.getAsBoolean())return false;
  post.submit();posted(scope,fresh);return true;
 }
 void save(LinkedHashMap<String,String> next)throws IOException{
  int terminal=0;for(String value:next.values())if(value.startsWith("completed:"))terminal++;
  Iterator<Map.Entry<String,String>> old=next.entrySet().iterator();while(old.hasNext()&&terminal>256){if(old.next().getValue().startsWith("completed:")){old.remove();terminal--;}}
  if(next.size()>8192)throw new IOException("提醒记录已满，保留待核对记录");
  File tmp=new File(file.getPath()+".tmp");try(FileOutputStream stream=new FileOutputStream(tmp);DataOutputStream out=new DataOutputStream(stream)){out.writeInt(1);out.writeInt(next.size());for(Map.Entry<String,String> e:next.entrySet()){out.writeUTF(e.getKey());out.writeUTF(e.getValue());}out.flush();stream.getFD().sync();}
  Files.move(tmp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
  records.clear();records.putAll(next);
 }
 interface Work {void run()throws Exception;}
 static void acceptThenReconcile(Runnable accepted,Work reconcile)throws Exception{accepted.run();reconcile.run();}
 /** Only a complete same-revision candidate pass may be delivered. */
 static final class Validation {
  final String scope,revision;final List<String> inventory,candidates,valid=new ArrayList<>();int next;
  Validation(String scope,String revision,Collection<String> inventory,Collection<String> candidates){this.scope=scope;this.revision=revision;this.inventory=new ArrayList<>(inventory);this.candidates=new ArrayList<>(candidates);}
  boolean matches(String scope,String revision,Collection<String> inventory){return this.scope.equals(scope)&&this.revision.equals(revision)&&this.inventory.equals(new ArrayList<>(inventory));}
  interface Check {boolean actionable(String id)throws Exception;}
  boolean advance(long deadline,java.util.function.LongSupplier now,Check check,java.util.function.BooleanSupplier gate)throws Exception{
   while(next<candidates.size()){
    if(!gate.getAsBoolean()||now.getAsLong()>=deadline)return false;
    String id=candidates.get(next);boolean actionable=check.actionable(id);
    if(!gate.getAsBoolean()||now.getAsLong()>deadline)return false;
    if(actionable)valid.add(id);next++;
   }
   return gate.getAsBoolean();
  }
 }
 static boolean canAct(String request,String delivery,boolean allowed){return allowed&&request.equals("pending")&&(delivery.equals("not_sent")||delivery.equals("rejected"));}
 static final class Batch {
  final String scope,revision;final LinkedHashSet<String> ids=new LinkedHashSet<>(),cursors=new LinkedHashSet<>();boolean done;int pages;
  Batch(String scope,String revision){this.scope=scope;this.revision=revision;}
  void add(String scope,String revision,Collection<String> values,String next)throws IOException{
   if(done||!this.scope.equals(scope)||!this.revision.equals(revision)||++pages>64||(!next.isEmpty()&&!cursors.add(next)))throw new IOException("待处理快照已变化或过大，请刷新");
   ids.addAll(values);done=next.isEmpty();
  }
  boolean ready(){return done;}
 }
}
