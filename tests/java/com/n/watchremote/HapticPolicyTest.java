package com.n.watchremote;
import java.io.File;
import java.util.Arrays;
import java.lang.reflect.*;
import java.util.function.*;
public final class HapticPolicyTest {
  static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
  static HapticPolicy.Gates allowed(){
    HapticPolicy.Gates g=new HapticPolicy.Gates();
    g.enabled=g.appEnabled=g.desktopEnabled=g.channelHigh=g.channelVibrates=g.dndAllows=g.ringerAllows=g.permission=g.hardware=g.current=g.patternValid=g.interactive=true;
    return g;
  }
  // Reflection lets the pre-implementation run fail on the missing diagnostic contract,
  // rather than fail compilation. All exercised policy and trace state is production code.
  static Class<?> nested(String name){
    for(Class<?> type:HapticPolicy.class.getDeclaredClasses())if(type.getSimpleName().equals(name))return type;
    throw new AssertionError("missing per-attempt diagnostic contract: "+name);
  }
  @SuppressWarnings({"unchecked","rawtypes"}) static Object value(String type,String name){return Enum.valueOf((Class)nested(type),name);}
  static Object call(Object target,String name,Class<?>[] types,Object...args)throws Exception{
    Method method=(target==null?HapticPolicy.class:target.getClass()).getDeclaredMethod(name,types);method.setAccessible(true);
    try{return method.invoke(target,args);}catch(InvocationTargetException failure){
      if(failure.getCause() instanceof RuntimeException)throw (RuntimeException)failure.getCause();
      if(failure.getCause() instanceof Error)throw (Error)failure.getCause();
      throw failure;
    }
  }
  static Object trace(String scope,String correlation,int generation,long elapsed)throws Exception{
    return call(null,"begin",new Class<?>[]{nested("Source"),String.class,String.class,int.class,long.class},value("Source","DESKTOP"),scope,correlation,generation,elapsed);
  }
  static void mark(Object trace,String method,long elapsed,int generation)throws Exception{call(trace,method,new Class<?>[]{long.class,int.class},elapsed,generation);}
  static String summary(Object trace)throws Exception{return (String)call(trace,"summary",new Class<?>[]{});}
  static boolean attempt(HapticPolicy.Gates gates,Runnable run,Object trace,long elapsed,int generation)throws Exception{
    return (Boolean)call(null,"attempt",new Class<?>[]{boolean.class,HapticPolicy.Gates.class,Runnable.class,nested("Trace"),LongSupplier.class,IntSupplier.class},true,gates,run,trace,(LongSupplier)()->elapsed,(IntSupplier)()->generation);
  }
  static void diagnostics()throws Exception{
    Object phases=trace("phase-scope","phase-request",3,100);
    call(phases,"checkpoint",new Class<?>[]{nested("Stage"),long.class,long.class},value("Stage","ENTRY"),100L,50L);
    call(phases,"checkpoint",new Class<?>[]{nested("Stage"),long.class,long.class},value("Stage","DEADLINE"),120100L,150L);
    for(int i=0;i<200;i++)call(phases,"checkpoint",new Class<?>[]{nested("Stage"),long.class,long.class},value("Stage","ENTRY"),999999L,999999L);
    String measured=summary(phases);
    check(measured.contains("ENTRY@100/50")&&measured.contains("DEADLINE@120100/150"),"elapsed and uptime checkpoints distinguish a long suspend from awake blocking");
    check(!measured.contains("999999")&&measured.length()<1000,"each fixed phase is retained once with bounded metadata");
    // Losing the after-notify generation or treating posted as haptic returned must fail.
    Object changed=trace("raw-scope","raw-request-id",7,100);
    mark(changed,"posting",105,7);mark(changed,"posted",110,8);mark(changed,"recorded",112,8);
    HapticPolicy.Gates stale=allowed();stale.current=false;
    int[] calls={0};check(!attempt(stale,()->calls[0]++,changed,120,8),"stale generation remains blocked");
    call(changed,"wake",new Class<?>[]{nested("Wake"),long.class,int.class},value("Wake","SKIPPED_GENERATION"),125L,8);
    mark(changed,"finished",130,8);String blocked=summary(changed);
    check(calls[0]==0&&blocked.contains("post=returned@105..110:7>8")&&blocked.contains("ledger=returned@112"),"notification return and durable record stay distinct");
    check(blocked.contains("haptic=blocked(连接已变化)@120..120:8>8")&&blocked.contains("wake=SKIPPED_GENERATION@125..125:8>8"),"actual stale haptic and skipped wake reasons retained");
    check(blocked.contains("elapsed=100..130")&&blocked.contains("generation=7>8"),"elapsed times and start/end generations retained");
    check(!blocked.contains("raw-scope")&&!blocked.contains("raw-request-id")&&blocked.matches("(?s).*scope=[0-9a-f]{12} correlation=[0-9a-f]{12}.*"),"only hashed scope and correlation exposed");

    // A successful API return is only an attempted physical alert, never proof of feeling it.
    Object returned=trace("scope","request",10,200);mark(returned,"posting",201,10);mark(returned,"posted",202,10);
    int[] generation={10};long[] now={210};
    check((Boolean)call(null,"attempt",new Class<?>[]{boolean.class,HapticPolicy.Gates.class,Runnable.class,nested("Trace"),LongSupplier.class,IntSupplier.class},true,allowed(),(Runnable)()->{calls[0]++;generation[0]=11;now[0]=214;},returned,(LongSupplier)()->now[0],(IntSupplier)()->generation[0]),"allowed API still runs once");
    check(calls[0]==1&&summary(returned).contains("haptic=returned@210..214:10>11"),"haptic API boundary captures generation change during call");

    // Exceptions must remain exceptions while recording attempted versus returned accurately.
    Object failed=trace("scope","request-2",11,300);mark(failed,"posting",301,11);mark(failed,"posted",302,11);
    try{attempt(allowed(),()->{throw new SecurityException("secret exception detail");},failed,310,11);throw new AssertionError("vibration exception swallowed");}catch(SecurityException expected){}
    String failure=summary(failed);check(failure.contains("haptic=failed@310..310:11>11")&&!failure.contains("secret exception detail"),"failure records API attempt without exception content");
    mark(failed,"hapticUnavailable",311,11);mark(failed,"postFailed",312,11);
    check(summary(failed).contains("haptic=failed@310..310:11>11")&&summary(failed).contains("post=returned@301..302:11>11"),"later preparation or wake failures cannot relabel a returned notification or physical API attempt");

    Object unavailable=trace("scope","request-3",11,320);mark(unavailable,"hapticUnavailable",321,11);
    check(summary(unavailable).contains("haptic=failed_before_attempt@321..321:11>11"),"preparation failure does not claim a hardware API attempt");
    mark(unavailable,"recordFailed",322,11);
    check(summary(unavailable).contains("ledger=not_attempted"),"reconciliation failure before notification does not claim failed posted-ledger persistence");

    // A later attempt replaces the single compact retained record, with bounded output.
    Object last=trace("x".repeat(100000),"private-body-token".repeat(10000),12,400);
    check(((String)call(null,"lastSummary",new Class<?>[]{})).equals(summary(last)),"only latest attempt retained");
    check(summary(last).length()<800&&!summary(last).contains("private-body-token"),"untrusted correlation length cannot grow or leak diagnostic output");
  }
  static void afterPost(Runnable wake,Runnable haptic,Runnable page,boolean posted)throws Exception{
    call(null,"afterPost",new Class<?>[]{boolean.class,Runnable.class,Runnable.class,Runnable.class},posted,wake,haptic,page);
  }
  static void wakeBeforeHaptic()throws Exception{
    java.util.List<String> events=new java.util.ArrayList<>();
    boolean[] interactive={false};int[] calls={0};
    afterPost(()->{events.add("wake");interactive[0]=true;},()->{
      HapticPolicy.Gates gates=allowed();
      try{HapticPolicy.Gates.class.getDeclaredField("interactive").setBoolean(gates,interactive[0]);}catch(Exception failure){throw new AssertionError(failure);}
      HapticPolicy.attempt(true,gates,()->{check(interactive[0],"OEM cannot receive haptic before wake");events.add("haptic");calls[0]++;});
    },()->events.add("page"),true);
    check(events.equals(Arrays.asList("wake","haptic","page"))&&calls[0]==1,"one haptic after wake, before page");
    events.clear();afterPost(()->events.add("wake"),()->events.add("haptic"),()->events.add("page"),false);
    check(events.isEmpty(),"unposted or uncommitted notification cannot cause effects");

    // Gates must be obtained after waking, never reused from before a blocking wake call.
    boolean[] current={true};
    afterPost(()->current[0]=false,()->{HapticPolicy.Gates gates=allowed();gates.current=current[0];HapticPolicy.attempt(true,gates,()->calls[0]++);},()->{},true);
    check(calls[0]==1,"generation/configuration change while waking prevents haptic");
    HapticPolicy.Gates asleep=allowed();HapticPolicy.Gates.class.getDeclaredField("interactive").setBoolean(asleep,false);
    afterPost(()->{},()->HapticPolicy.attempt(true,asleep,()->calls[0]++),()->events.add("page"),true);
    check(calls[0]==1&&events.equals(Arrays.asList("page")),"wake return without interactive skips haptic, retains page fallback");
    check(HapticPolicy.blocked(asleep).contains("唤醒"),"blocked state explains unfinished wake");

    events.clear();
    try{afterPost(()->{throw new SecurityException();},()->events.add("fresh-gates"),()->events.add("page"),true);throw new AssertionError("wake error swallowed");}catch(SecurityException expected){}
    check(events.equals(Arrays.asList("fresh-gates","page")),"wake failure still lets fresh policy and independent page path run");
    events.clear();
    try{afterPost(()->events.add("wake"),()->{throw new SecurityException();},()->events.add("page"),true);throw new AssertionError("haptic error swallowed");}catch(SecurityException expected){}
    check(events.equals(Arrays.asList("wake","page")),"haptic failure does not suppress page fallback or retry");
  }
  static boolean screenReady(java.util.concurrent.CountDownLatch signal,BooleanSupplier current,
      BooleanSupplier interactive,long timeout)throws Exception{
    return (Boolean)call(null,"awaitScreenOn",new Class<?>[]{java.util.concurrent.CountDownLatch.class,
      BooleanSupplier.class,BooleanSupplier.class,long.class},signal,current,interactive,timeout);
  }
  static boolean dispatch(Object gate,BooleanSupplier current,Runnable action)throws Exception{
    return (Boolean)call(null,"guardedDispatch",new Class<?>[]{Object.class,BooleanSupplier.class,Runnable.class},gate,current,action);
  }
  static void serializedDispatch()throws Exception{
    Object gate=new Object();boolean[] enabled={true};int[] calls={0};
    java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
    java.util.concurrent.CountDownLatch attempting=new java.util.concurrent.CountDownLatch(1);
    Thread stale=new Thread(()->{try{attempting.countDown();check(!dispatch(gate,()->enabled[0],()->calls[0]++),"revoked policy cannot dispatch after waiting for gate");}catch(Throwable error){failure.set(error);}});
    synchronized(gate){stale.start();check(attempting.await(1,java.util.concurrent.TimeUnit.SECONDS),"worker attempted dispatch");enabled[0]=false;}
    stale.join(2000);check(!stale.isAlive()&&failure.get()==null&&calls[0]==0,"fresh policy checked within gate");

    enabled[0]=true;java.util.concurrent.CountDownLatch started=new java.util.concurrent.CountDownLatch(1),finish=new java.util.concurrent.CountDownLatch(1),saved=new java.util.concurrent.CountDownLatch(1);
    Thread effect=new Thread(()->{try{check(dispatch(gate,()->enabled[0],()->{started.countDown();try{finish.await();}catch(InterruptedException stop){Thread.currentThread().interrupt();throw new AssertionError(stop);}calls[0]++;}),"accepted dispatch");}catch(Throwable error){failure.set(error);}});
    Thread setting=new Thread(()->{synchronized(gate){enabled[0]=false;saved.countDown();}});
    effect.start();check(started.await(1,java.util.concurrent.TimeUnit.SECONDS),"dispatch started");setting.start();
    long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
    try{
      while(setting.getState()!=Thread.State.BLOCKED&&setting.isAlive()&&System.nanoTime()<deadline)Thread.yield();
      check(setting.getState()==Thread.State.BLOCKED&&saved.getCount()==1,"setting commit serialized behind in-flight dispatch");
    }finally{finish.countDown();}
    effect.join(2000);setting.join(2000);check(failure.get()==null&&calls[0]==1&&saved.getCount()==0,"one dispatch and then setting commit");

    java.util.concurrent.CountDownLatch signal=new java.util.concurrent.CountDownLatch(1),waiting=new java.util.concurrent.CountDownLatch(1);
    enabled[0]=true;
    Thread sleeper=new Thread(()->{try{dispatch(gate,()->true,()->{});check(!screenReady(signal,()->{waiting.countDown();return enabled[0];},()->true,1000),"revoked policy after screen wait");}catch(Throwable error){failure.set(error);}});
    sleeper.start();check(waiting.await(1,java.util.concurrent.TimeUnit.SECONDS),"screen wait started");
    synchronized(gate){enabled[0]=false;signal.countDown();}
    sleeper.join(2000);check(!sleeper.isAlive()&&failure.get()==null,"screen waiting does not retain dispatch gate");
  }
  static void asynchronousWake()throws Exception{
    java.util.concurrent.CountDownLatch signal=new java.util.concurrent.CountDownLatch(1);
    check(!screenReady(signal,()->true,()->true,0),"public interactive alone cannot prove a requested sleep wake completed");
    check(!screenReady(new java.util.concurrent.CountDownLatch(0),()->true,()->false,0),"screen event without interactive remains blocked");
    check(screenReady(new java.util.concurrent.CountDownLatch(0),()->true,()->true,0),"completed screen signal and live state permit first haptic");
    check(!screenReady(new java.util.concurrent.CountDownLatch(0),()->false,()->true,0),"stale generation or disabled policy cannot use screen signal");
    java.util.concurrent.atomic.AtomicBoolean current=new java.util.concurrent.atomic.AtomicBoolean(true);
    java.util.concurrent.CountDownLatch waiting=new java.util.concurrent.CountDownLatch(1),changed=new java.util.concurrent.CountDownLatch(1);
    Thread changes=new Thread(()->{try{waiting.await();current.set(false);changed.countDown();}catch(InterruptedException failure){throw new AssertionError(failure);}});
    changes.start();
    check(!screenReady(changed,()->{waiting.countDown();return current.get();},()->true,1000),"policy/generation rechecked after actual wake event");changes.join();
    Thread.currentThread().interrupt();
    check(!screenReady(new java.util.concurrent.CountDownLatch(1),()->true,()->true,10),"interrupted waiter cannot produce late vibration");
    check(Thread.interrupted(),"interruption restored for worker shutdown");
  }
  public static void main(String[] args)throws Exception{
    diagnostics();
    serializedDispatch();
    wakeBeforeHaptic();
    asynchronousWake();
    int[] calls={0};
    check(!HapticPolicy.attempt(false,allowed(),()->calls[0]++),"no posted notification cannot vibrate");
    for(String field:new String[]{"enabled","appEnabled","desktopEnabled","channelHigh","channelVibrates","dndAllows","ringerAllows","permission","hardware","current","patternValid","interactive"}){
      HapticPolicy.Gates g=allowed();HapticPolicy.Gates.class.getDeclaredField(field).setBoolean(g,false);
      check(!HapticPolicy.attempt(true,g,()->calls[0]++),"blocked gate: "+field);
    }
    for(String field:new String[]{"muted","paused"}){
      HapticPolicy.Gates g=allowed();HapticPolicy.Gates.class.getDeclaredField(field).setBoolean(g,true);
      check(!HapticPolicy.attempt(true,g,()->calls[0]++),"blocked gate: "+field);
    }
    check(calls[0]==0,"no blocked vibration");
    check(HapticPolicy.attempt(true,allowed(),()->calls[0]++)&&calls[0]==1,"successful post vibrates once");
    check(HapticPolicy.pattern(null)==null&&HapticPolicy.pattern(new long[]{0,-1})==null&&HapticPolicy.pattern(new long[]{0,6000})==null,"invalid or unbounded pattern blocked");
    long[] pattern={0,180,100,180};long[] safe=HapticPolicy.pattern(pattern);pattern[1]=999;
    check(Arrays.equals(safe,new long[]{0,180,100,180}),"channel pattern retained independently");
    File f=File.createTempFile("haptic-ledger-",".json");f.delete();
    try{
      AttentionLedger ledger=new AttentionLedger(f);ledger.receive("s",Arrays.asList("r"));
      int[] posts={0},vibrations={0};
      for(int i=0;i<2;i++){
        boolean posted=ledger.deliver("s",Arrays.asList("r"),()->true,()->posts[0]++);
        HapticPolicy.attempt(posted,allowed(),()->vibrations[0]++);
      }
      check(posts[0]==1&&vibrations[0]==1,"repeat snapshot cannot repost or vibrate");
    }finally{f.delete();}
  }
}
