package com.n.watchremote;

/** Host-JVM checks for cross-conversation races; no Android mocks. */
public final class UiStateTest {
  static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
  public static void main(String[] args){
    UiState.Preview p=new UiState.Preview();
    check(!p.update("a","t1","b","t1","m","wrong",1,false,false),"other conversation");
    check(!p.update("a","t1","a","old","m","wrong",1,false,false),"old turn");
    check(p.update("a","t1","a","t1","m","first",1,false,false),"first output");
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
