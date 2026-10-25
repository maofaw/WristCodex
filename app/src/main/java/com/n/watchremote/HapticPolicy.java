package com.n.watchremote;
/** Shared policy for the opt-in notification haptic. No Android state or polling. */
final class HapticPolicy {
  static final class Gates {
    boolean enabled,appEnabled,desktopEnabled,channelHigh,channelVibrates,dndAllows,ringerAllows,permission,hardware,current,patternValid,interactive;
    boolean muted,paused;
  }
  static String blocked(Gates g){
    return blocked(g,false);
  }
  static String blockedCompletion(Gates g){return blocked(g,true);}
  private static String blocked(Gates g,boolean completion){
    if(!g.enabled)return "系统通知模式";
    if(!completion&&(g.muted||!g.desktopEnabled))return "提醒已关闭";
    if(g.paused)return "提醒已暂停";
    if(!g.appEnabled)return "系统通知未允许";
    if(!g.channelHigh)return "通知通道重要级别不足";
    if(!g.channelVibrates)return "通知通道振动已关闭";
    if(!g.dndAllows)return "勿扰模式已开启";
    if(!g.ringerAllows)return "系统处于静音模式";
    if(!g.permission)return "系统未允许振动权限";
    if(!g.hardware)return "系统未提供振动设备";
    if(!g.current)return "连接已变化";
    if(!completion&&!g.patternValid)return "通道振动节奏不可用";
    if(!completion&&!g.interactive)return "手表尚未完成唤醒";
    return "";
  }
  /** Only after notification and ledger success; each callback rechecks its own live gates. */
  static void afterPost(boolean posted,Runnable wakeScreen,Runnable haptic,Runnable openPage){
    if(!posted)return;
    try{wakeScreen.run();}
    finally{try{haptic.run();}finally{openPage.run();}}
  }
  /** A wake API return is asynchronous. Wait for one real screen event, never retry vibration. */
    static boolean guardedDispatch(Object gate,java.util.function.BooleanSupplier current,Runnable dispatch){
      synchronized(gate){if(!current.getAsBoolean())return false;dispatch.run();return true;}
    }
    static boolean awaitScreenOn(java.util.concurrent.CountDownLatch signal,
      java.util.function.BooleanSupplier current,java.util.function.BooleanSupplier interactive,long timeoutMs){
    if(!current.getAsBoolean())return false;
    try{if(!signal.await(Math.max(0,timeoutMs),java.util.concurrent.TimeUnit.MILLISECONDS))return false;}
    catch(InterruptedException stopped){Thread.currentThread().interrupt();return false;}
    return current.getAsBoolean()&&interactive.getAsBoolean();
  }
  static boolean attempt(boolean posted,Gates gates,Runnable vibrate){
    return attempt(posted,gates,vibrate,null,()->0,()->0);
  }
  static boolean attempt(boolean posted,Gates gates,Runnable vibrate,Trace trace,
      java.util.function.LongSupplier now,java.util.function.IntSupplier generation){
    String reason=posted?blocked(gates):"未提交通知";
    if(!reason.isEmpty()){if(trace!=null)trace.haptic("blocked",reason,now.getAsLong(),generation.getAsInt());return false;}
    if(trace!=null)trace.haptic("attempted","",now.getAsLong(),generation.getAsInt());
    try{
      vibrate.run();
      if(trace!=null)trace.haptic("returned","",now.getAsLong(),generation.getAsInt());
      return true;
    }catch(RuntimeException failure){
      if(trace!=null)trace.haptic("failed","",now.getAsLong(),generation.getAsInt());
      throw failure;
    }
  }
  enum Source { DESKTOP, LEGACY, TEST }
  enum Wake { SKIPPED_GENERATION, SKIPPED_SCOPE, SKIPPED_CONFIG, SKIPPED_POLICY, SKIPPED_OWNER, ATTEMPTED, RETURNED, FAILED }
  enum Lock { SKIPPED_INTERACTIVE, ATTEMPTED, RETURNED, DENIED }
  enum Skip { GENERATION, SCOPE, CONFIG, POLICY, RECONCILING, NO_LONGER_ELIGIBLE }
  enum Stage { ENTRY, DEADLINE, DISCOVERED, DURABLE, POLICY_START, POLICY_DONE, STATUS_CALL, STATUS_CONNECT, STATUS_HEADERS, STATUS_BODY, STATUS_RETURN, VALIDATED, GATE_WAIT, GATE_ACQUIRED, POST_READY }
  private static volatile Trace lastAttempt;
  static Trace begin(Source source,String scope,String correlation,int generation,long elapsed){
    Trace trace=new Trace(source,scope,correlation,generation,elapsed);lastAttempt=trace;return trace;
  }
  static String lastSummary(){Trace trace=lastAttempt;return trace==null?"":trace.summary();}
  /** One in-memory metadata record. Raw identifiers and exception messages are never retained. */
  static final class Trace {
    private final Source source;private final String scope,correlation;private final int generation;
    private final long started;private long ended;private int endGeneration;
    private final Event post=new Event(),haptic=new Event(),wake=new Event(),lock=new Event(),ready=new Event();
    private final java.util.EnumMap<Stage,long[]> checkpoints=new java.util.EnumMap<>(Stage.class);
    private String ledger="not_attempted";private long ledgerAt=-1;
    Trace(Source source,String scope,String correlation,int generation,long elapsed){
      this.source=source;this.scope=ConversationState.hash(scope).substring(0,12);
      this.correlation=ConversationState.hash(correlation).substring(0,12);this.generation=generation;
      started=ended=elapsed;endGeneration=generation;
    }
    synchronized void posting(long elapsed,int current){post.set("attempted","",elapsed,current);}
    synchronized void posted(long elapsed,int current){post.set("returned","",elapsed,current);}
    synchronized void postFailed(long elapsed,int current){if(!post.state.equals("returned"))post.set("failed","",elapsed,current);}
    synchronized void skipped(Skip reason,long elapsed,int current){if(post.start<0)post.set("skipped",reason.name(),elapsed,current);}
    synchronized void recorded(long elapsed,int current){ledger="returned";ledgerAt=elapsed;}
    synchronized void recordFailed(long elapsed,int current){if(post.state.equals("returned")&&!ledger.equals("returned")){ledger="failed";ledgerAt=elapsed;}}
    synchronized void haptic(String state,String reason,long elapsed,int current){haptic.set(state,reason,elapsed,current);}
    synchronized void hapticUnavailable(long elapsed,int current){if(haptic.start<0)haptic.set("failed_before_attempt","",elapsed,current);}
    synchronized void wake(Wake state,long elapsed,int current){wake.set(state.name(),"",elapsed,current);}
    synchronized void lock(Lock state,long elapsed,int current){lock.set(state.name(),"",elapsed,current);}
    synchronized void ready(String state,long elapsed,int current){ready.set(state,"",elapsed,current);}
    synchronized void finished(long elapsed,int current){ended=elapsed;endGeneration=current;}
    synchronized void checkpoint(Stage stage,long elapsed,long uptime){if(!checkpoints.containsKey(stage))checkpoints.put(stage,new long[]{elapsed,uptime});}
    synchronized String summary(){
      StringBuilder stages=new StringBuilder();
      for(java.util.Map.Entry<Stage,long[]> point:checkpoints.entrySet()){if(stages.length()>0)stages.append(',');stages.append(point.getKey()).append('@').append(point.getValue()[0]).append('/').append(point.getValue()[1]);}
      return "type="+source+" scope="+scope+" correlation="+correlation+" elapsed="+started+".."+ended+
        " generation="+generation+">"+endGeneration+" post="+post+" ledger="+ledger+(ledgerAt<0?"":"@"+ledgerAt)+
        " haptic="+haptic+" wake="+wake+" lock="+lock+" ready="+ready+(stages.length()==0?"":" stages="+stages);
    }
    private static final class Event {
      String state="not_attempted",reason="";long start=-1,end=-1;int before,after;
      void set(String state,String reason,long elapsed,int generation){
        if(start<0){start=elapsed;before=generation;}end=elapsed;after=generation;this.state=state;this.reason=reason;
      }
      @Override public String toString(){return state+(reason.isEmpty()?"":"("+reason+")")+(start<0?"":"@"+start+".."+end+":"+before+">"+after);}
    }
  }
  static long[] pattern(long[] source){
    if(source==null||source.length<2||source.length>32)return null;
    long total=0,on=0;
    for(int i=0;i<source.length;i++){
      if(source[i]<0||source[i]>5000)return null;
      total+=source[i];if((i&1)==1)on+=source[i];
      if(total>5000)return null;
    }
    return on>0?source.clone():null;
  }
}
