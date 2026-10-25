package com.n.watchremote;
import java.io.*;
import java.nio.file.*;
import java.util.*;
public final class ConversationStateTest {
 static void check(boolean v,String m){if(!v)throw new AssertionError(m);}
 public static void main(String[] args)throws Exception{
  check(ConversationState.normalize("HTTPS://Example.COM:443/").equals("https://example.com"),"canonical address");
  check(!ConversationState.scope("https://pc","a","s").equals(ConversationState.scope("https://pc","b","s")),"account partition");
  check(!ConversationState.scope("https://pc","","s").equals(ConversationState.scope("https://pc","","t")),"unknown identity session isolation");
  check(ConversationState.sameDraft(" hi "," hi ")&&!ConversationState.sameDraft(" hi ","hi"),"edited whitespace preserved");
  check(ConversationState.canStop("a","1","a","1",true,false),"current turn");
  check(!ConversationState.canStop("a","1","a","2",true,false),"reject stale turn");
  check(!ConversationState.canStop("a","1","a","1",true,true),"reject duplicate submission");
  check(ConversationState.recoveryScope("HTTPS://PC:443/","").equals(ConversationState.recoveryScope("https://pc","")),"durable unknown recovery uses normalized endpoint");
  check(!ConversationState.recoveryScope("https://pc","").equals(ConversationState.recoveryScope("https://pc","account")),"unassigned records cannot automatically join account");
  check(ConversationState.initialTarget("account","normal","main","old-account").equals("normal"),"live verified monitor reopens scoped normal view");
  check(ConversationState.initialTarget("account","","main","old-account").equals("main"),"verified first open prefers bound main");
  check(ConversationState.initialTarget("new-account","","","old-account").isEmpty(),"new verified account cannot adopt global viewed thread");
  Map<String,Object> legacy=new HashMap<>();legacy.put("draft:t","draft");legacy.put("pending:t","receipt");legacy.put("operation:t:settings","op");legacy.put("token","secret");
  check(ConversationState.recoveryRecords(legacy).size()==3,"legacy drafts and unknown operations exposed without credentials");
  UiState.Pager offline=new UiState.Pager();offline.first();offline.success("two");offline.begin(1);offline.success("three");offline.failed();
  check(offline.page==1&&offline.canPrevious()&&offline.canNext(),"accepted cached next page survives failed refresh");
  UiState.Pager restored=offline.copy();offline.first();offline.success("");check(restored.page==1&&restored.cursors.get(1).equals("two"),"temporary navigation saves independent pager");
  File root=Files.createTempDirectory("list-cache-test").toFile();
  ConversationCache chain=new ConversationCache(null);List<ConversationCache.Item> one=Collections.singletonList(new ConversationCache.Item("message","title",false));
  chain.put("scope",false,"","page2",one,1);chain.put("scope",false,"page2","page3",one,2);chain.put("scope",false,"page3","page4",one,3);
  ConversationCache.Page cold=chain.fallback("scope",false);
  check(cold.cursor.equals("page2"),"cold offline fallback starts at retained predecessor");
  UiState.Pager coldPager=UiState.Pager.cached(cold.cursor,cold.next);
  String forward=coldPager.begin(1);ConversationCache.Page third=chain.get("scope",false,forward);check(third!=null,"both retained pages are reachable offline");coldPager.success(third.next);
  check(coldPager.begin(-1).equals("page2"),"offline previous returns retained predecessor");
  UiState.AttentionPage attention=new UiState.AttentionPage("current-page","next-page",12);UiState.AttentionPage snapshot=attention.copy();
  check(snapshot.current.equals("current-page")&&snapshot.next.equals("next-page")&&snapshot.hasNext()&&snapshot.countLabel().equals("12 项等待处理"),"notification return preserves request cursor, next action, footer and total separately");
  check(!UiState.canRememberAnchor("normal-A","temporary-B",false),"temporary message rows cannot overwrite restored normal target anchor");
  ConversationCache c=new ConversationCache(root);List<ConversationCache.Item> rows=new ArrayList<>();
  for(int n=0;n<25;n++)rows.add(new ConversationCache.Item("id"+n,"title",false));
  c.put("a",false,"","next",rows,1);c.put("a",false,"next","third",rows,2);c.put("a",true,"","",rows,3);
  check(c.get("a",false,"")==null,"only last two pages");
  check(c.get("a",false,"next").items.size()==20,"bounded metadata");
  check(c.recent("a",false).cursor.equals("next"),"cold offline fallback locates retained non-first page");
  check(c.get("b",false,"next")==null,"partition cache");
  check(new ConversationCache(root).get("a",true,"")!=null,"restart restore");
  Files.write(c.file("a").toPath(),new byte[]{1,2});
  check(new ConversationCache(root).get("a",true,"")==null,"corruption becomes cache miss");
  c.invalidate("a");check(c.get("a",false,"next")==null,"management invalidation");
  System.out.println("ConversationStateTest passed");
 }
}
