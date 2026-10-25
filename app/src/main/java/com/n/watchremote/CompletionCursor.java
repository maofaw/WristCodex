package com.n.watchremote;
import java.util.function.BooleanSupplier;
import java.util.function.LongPredicate;

/** A bounded event stream checkpoint. No event text, identifiers, threads or timers. */
final class CompletionCursor {
  private String scope="";
  private long cursor;
  private boolean baseline=true;
  private boolean completionRebasePending;
  CompletionCursor(){}
  CompletionCursor(String savedScope,long savedCursor){scope=savedScope==null?"":savedScope;cursor=Math.max(0,savedCursor);}
  void resetBaseline(){baseline=true;}
  boolean beginRebase(){if(completionRebasePending)return false;completionRebasePending=true;return true;}
  void finishRebase(){completionRebasePending=false;}
  boolean rebasePending(){return completionRebasePending;}
  /** Snapshot cut point and setting must be persisted together before releasing deferred polls. */
  boolean rebase(String nextScope,long end,LongPredicate persist){
    if(!completionRebasePending||nextScope==null||nextScope.isEmpty()||end<0)return false;
    long next=nextScope.equals(scope)?Math.max(cursor,end):end;
    if(!persist.test(next))return false;
    scope=nextScope;cursor=next;baseline=false;return true;
  }
  boolean receive(String nextScope,long end,long oldest,long[] events,boolean enabled,boolean current,BooleanSupplier persist){
    if(completionRebasePending||!current||nextScope==null||nextScope.isEmpty()||end<0||oldest<1||events==null||events.length>64)return false;
    boolean same=nextScope.equals(scope);
    if(same&&(end<cursor||(!baseline&&end==cursor)))return false;
    boolean contiguous=events.length==0?oldest>end:events[0]==oldest;
    long previous=oldest-1;
    for(long sequence:events){if(sequence<=0||sequence!=previous+1||sequence>end)contiguous=false;previous=sequence;}
    if(events.length>0&&previous!=end)contiguous=false;
    boolean fresh=same&&!baseline&&end>cursor&&oldest-1<=cursor&&contiguous;
    // Commit before dispatch. Failed physical dispatch must never replay this checkpoint.
    if(!persist.getAsBoolean())return false;
    scope=nextScope;cursor=end;baseline=false;
    return fresh&&enabled;
  }
}
