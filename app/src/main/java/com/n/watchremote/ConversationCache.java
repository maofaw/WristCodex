package com.n.watchremote;
import java.io.*;
import java.nio.file.*;
import java.util.*;
/** Two recently viewed pages of metadata only. Call on the storage executor, never the UI thread. */
final class ConversationCache {
 static final int MAGIC=0x574c4301;
 final File root;
 final Map<String,List<Page>> volatilePages=new HashMap<>();
 ConversationCache(File root){this.root=root;}
 static final class Item {
  final String id,title;final boolean readonly;
  Item(String id,String title,boolean readonly){this.id=id;this.title=title;this.readonly=readonly;}
 }
 static final class Page {
  final boolean archived;final String cursor,next;final List<Item> items;final long updated;
  Page(boolean archived,String cursor,String next,List<Item> items,long updated){this.archived=archived;this.cursor=cursor;this.next=next;this.items=new ArrayList<>(items.subList(0,Math.min(20,items.size())));this.updated=updated;}
 }
 File file(String scope){return new File(root,ConversationState.hash(scope)+".v1");}
 synchronized List<Page> read(String scope){
  if(root==null)return new ArrayList<>(volatilePages.getOrDefault(scope,Collections.emptyList()));
  File f=file(scope);List<Page> pages=new ArrayList<>();if(!f.exists())return pages;
  try(DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(f)))){
   if(f.length()>256000||in.readInt()!=MAGIC)throw new IOException("cache header");int count=in.readInt();if(count<0||count>2)throw new IOException("cache pages");
   for(int n=0;n<count;n++){boolean archived=in.readBoolean();String cursor=in.readUTF(),next=in.readUTF();long time=in.readLong();int size=in.readInt();if(size<0||size>20)throw new IOException("cache items");List<Item> rows=new ArrayList<>();for(int i=0;i<size;i++)rows.add(new Item(in.readUTF(),in.readUTF(),in.readBoolean()));pages.add(new Page(archived,cursor,next,rows,time));}
   if(in.read()!=-1)throw new IOException("cache trailing bytes");return pages;
  }catch(Exception corrupt){f.delete();return new ArrayList<>();}
 }
 synchronized Page get(String scope,boolean archived,String cursor){for(Page p:read(scope))if(p.archived==archived&&p.cursor.equals(cursor))return p;return null;}
 synchronized Page recent(String scope,boolean archived){List<Page> pages=read(scope);for(int n=pages.size()-1;n>=0;n--)if(pages.get(n).archived==archived)return pages.get(n);return null;}
 synchronized Page fallback(String scope,boolean archived){
  List<Page> pages=read(scope);
  for(Page earlier:pages)if(earlier.archived==archived)for(Page later:pages)if(later!=earlier&&later.archived==archived&&earlier.next.equals(later.cursor))return earlier;
  for(Page page:pages)if(page.archived==archived)return page;return null;
 }
 synchronized void put(String scope,boolean archived,String cursor,String next,List<Item> rows,long updated)throws IOException{
  List<Page> pages=read(scope);pages.removeIf(p->p.archived==archived&&p.cursor.equals(cursor));pages.add(new Page(archived,cursor,next,rows,updated));while(pages.size()>2)pages.remove(0);
  if(root==null){volatilePages.clear();volatilePages.put(scope,pages);return;}
  root.mkdirs();File target=file(scope),temp=new File(target+".tmp");
  try(FileOutputStream stream=new FileOutputStream(temp);DataOutputStream out=new DataOutputStream(new BufferedOutputStream(stream))){out.writeInt(MAGIC);out.writeInt(pages.size());for(Page p:pages){out.writeBoolean(p.archived);out.writeUTF(p.cursor);out.writeUTF(p.next);out.writeLong(p.updated);out.writeInt(p.items.size());for(Item item:p.items){out.writeUTF(item.id);out.writeUTF(item.title);out.writeBoolean(item.readonly);}}out.flush();stream.getFD().sync();}
  try{Files.move(temp.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{temp.delete();}
 }
 synchronized void invalidate(String scope){if(root==null)volatilePages.remove(scope);else file(scope).delete();}
}
