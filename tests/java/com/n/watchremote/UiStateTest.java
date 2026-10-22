package com.n.watchremote;

/** Host-JVM checks for cross-conversation races; no Android mocks. */
public final class UiStateTest {
  static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
  public static void main(String[] args){
    check(UiState.effortIndex(null,new String[]{"low","high"})==0,"unspecified desktop effort remains unchanged");
    check(UiState.effortIndex("high",new String[]{"low","high"})==2,"explicit desktop effort maps after inherited entry");
    check(UiState.effortValue(0,new String[]{"low","high"})==null,"inherited option must not write a default effort");
    check("high".equals(UiState.effortValue(2,new String[]{"low","high"})),"explicit selection retains native value");
    UiState.Pager pages=new UiState.Pager();
    check("".equals(pages.first()),"first page starts without a cursor");
    check(pages.begin(1)==null,"repeat gestures cannot queue a second read");
    pages.success("cursor2");
    check(pages.page==0&&!pages.canPrevious()&&pages.canNext(),"first-page boundary");
    check("cursor2".equals(pages.begin(1)),"next uses native opaque cursor");
    pages.failed();check(pages.page==0&&pages.canNext(),"failed reads keep page and contents");
    check("cursor2".equals(pages.begin(1)),"failed next remains retryable");
    pages.success("cursor3");check(pages.page==1&&pages.canPrevious(),"only accepted reads advance page");
    check("".equals(pages.begin(-1)),"previous reuses prior request cursor");pages.success("changed2");
    check("changed2".equals(pages.begin(1)),"a refreshed page replaces its stale next cursor");pages.success("");
    check(!pages.canNext()&&pages.begin(1)==null,"last-page boundary");
    pages.first();pages.failed();check(pages.page==1,"failed return-to-recent preserves old page");
    pages.first();pages.success("fresh");check(pages.page==0&&!pages.canPrevious(),"return-to-recent resets history after success");
    check(UiState.swipePage(-60,10,100,334)==1,"left swipe advances");
    check(UiState.swipePage(60,10,100,334)==-1,"right swipe goes back");
    check(UiState.swipePage(-47,0,100,334)==0,"short drag is not a page");
    check(UiState.swipePage(-60,50,100,334)==0,"vertical or diagonal drag remains scroll");
    check(UiState.swipePage(60,0,23,334)==0&&UiState.swipePage(-60,0,311,334)==0,"system edge gestures retained");
    check(" 继续验收 ".equals(UiState.answerText(" 继续验收 ")),"original option whitespace must survive submission");
    check("  保留我的换行\n".equals(UiState.answerText("  保留我的换行\n")),"freeform answer must not be rewritten");
    check(UiState.answerText("")==null,"empty answer must not submit");
    check(UiState.answerText(" \t\n")==null,"blank answer must not submit");
    UiState.Preview p=new UiState.Preview();
    check(!p.update("a","t1","b","t1","m","wrong",1,false,false),"other conversation");
    check(!p.update("a","t1","a","old","m","wrong",1,false,false),"old turn");
    check(p.update("a","t1","a","t1","m","first",1,false,false),"first output");
    p.rendered(1000);
    check(p.renderDelay("a","t1","m",2,false,1500)==1500,"middle updates share a fixed two-second paint deadline");
    check(p.version==1&&p.text.equals("first"),"deferring paint must not consume the incoming version");
    check(p.renderDelay("a","t1","m",3,false,2500)==500,"newer bodies must not postpone the original deadline");
    check(p.renderDelay("a","t1","m",3,false,3000)==0,"latest body may paint when the deadline arrives");
    check(p.renderDelay("a","t1","m",2,true,1001)==0,"completion must bypass the paint deadline");
    check(p.renderDelay("a","t2","new",1,false,1001)==0,"a different turn first body must be immediate");
    check(p.renderDelay("a","t1","m",1,false,1001)==0,"duplicate snapshots must not schedule another repaint");
    check(!p.update("a","t1","a","t1","m","stale",0,false,false),"old version");
    check(!p.update("a","t1","a","t1","m","duplicate",1,false,false),"duplicate version");
    check(p.update("a","t1","a","t1","m","finished",2,true,false),"completion");
    check(!p.update("a","t1","a","t1","m","late",3,false,false),"completed output cannot become partial");
    check(p.update("a","t2","a","t2","m2","new",1,false,false),"new turn resets version");
    check(p.update("a","t2","a","t2","m3","replacement",1,false,false),"new message resets version");
    String large=new String(new char[1400]).replace('\0','字');
    check(p.update("a","t2","a","t2","m3",large,2,false,true),"bounded update");
    check(p.text.length()==1000&&p.clipped,"bounded preview");
    p.clear();check(p.itemId.isEmpty(),"clear navigation");
    long[] expected={1000,2000,4000,8000,15000,15000};
    for(int i=0;i<expected.length;i++)check(UiState.retryDelay(i+1)==expected[i],"backoff "+i);
    check(UiState.retryDelay(Integer.MAX_VALUE)==15000,"overflow-safe backoff");
    UiState.History history=new UiState.History();
    check(history.shouldRefresh(true,false),"empty history must load");
    check(history.shouldRefresh(false,true),"following latest may reconcile completion");
    check(!history.shouldRefresh(false,false)&&history.deferred,"reading latest page must not jump");
    history.loaded(true);
    check(!history.shouldRefresh(false,true),"bottom of older window is not latest history");
    history.loaded(false);
    check(history.shouldRefresh(false,true)&&!history.deferred,"explicit latest refresh resets browsing window");
    check(!UiState.canSubmit(true,false,false,false,false),"native idle transition must keep draft without submitting");
    check(UiState.canSubmit(true,false,false,true,false),"native ready conversation may send");
    check(!UiState.canSubmit(true,false,true,true,false),"running conversation requires steer capability");
    check(UiState.canSubmit(true,false,true,false,true),"steer uses its own capability");
    check(UiState.canSubmit(true,true,false,false,false),"blank conversation has no native capability yet");
    check(UiState.canSubmit(false,false,false,false,false),"legacy protocol remains compatible");
    System.out.println("UI protocol: identity, version, completion, bounds and backoff passed");
  }
}
