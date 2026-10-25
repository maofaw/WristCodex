package com.n.watchremote;
import java.lang.reflect.*;
import java.util.function.LongPredicate;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** Exercise the same cursor transaction API used by the background settings worker. */
public final class CompletionSettingBoundaryTest {
  static Method begin,end,rebase;
  static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
  static boolean start(CompletionCursor c)throws Exception{return (Boolean)begin.invoke(c);}
  static void finish(CompletionCursor c)throws Exception{end.invoke(c);}
  static boolean cut(CompletionCursor c,String scope,long cursor,LongPredicate store)throws Exception{return (Boolean)rebase.invoke(c,scope,cursor,store);}
  static boolean receive(CompletionCursor c,long cursor,boolean enabled,long... events){return c.receive("pc",cursor,events.length==0?cursor+1:events[0],events,enabled,true,()->true);}
  static void networkAtomicity(Path root)throws Exception{
    Path sources=root.resolve("app/src/main/java/com/n/watchremote");
    String monitor=new String(Files.readAllBytes(sources.resolve("AssistantMonitor.java")),StandardCharsets.UTF_8);
    String activity=new String(Files.readAllBytes(sources.resolve("AssistantActivity.java")),StandardCharsets.UTF_8);
    check(Pattern.compile("void completionReconnect\\(\\)\\s*\\{\\s*synchronized\\(alertGate\\)\\s*\\{\\s*completionBaseline=true;\\s*reconnect\\(\\);\\s*\\}").matcher(monitor).find(),
      "network reset and reconnect generation must be atomic under alertGate");
    check(monitor.split("completionReconnect\\(\\);",-1).length-1==3,"all three network callbacks must use the atomic reconnect boundary");
    check(Pattern.compile("void completionDisconnected\\(int generation\\)\\s*\\{\\s*synchronized\\(alertGate\\)\\s*\\{\\s*if\\(generation==reconnectVersion\\)completionBaseline=true;").matcher(monitor).find(),
      "network failure baseline must share alertGate and reject old generations");
    check(monitor.contains("completionDisconnected(generation);"),"network exception path must use guarded baseline reset");
    check(Pattern.compile("if\\(owner\\.completionTargetCurrent\\(prefs,generation,base,token,raw\\)\\)\\s*\\{\\s*owner\\.completionOwner=ConversationState\\.hash\\(base\\+\\\":\\\"\\+token\\);\\s*owner\\.completionBaseline=false;\\s*\\}").matcher(activity).find(),
      "successful setting commit must recheck current generation before clearing baseline inside its existing alertGate");
  }
  public static void main(String[] args)throws Exception{
    try{
      begin=CompletionCursor.class.getDeclaredMethod("beginRebase");
      end=CompletionCursor.class.getDeclaredMethod("finishRebase");
      rebase=CompletionCursor.class.getDeclaredMethod("rebase",String.class,long.class,LongPredicate.class);
    }catch(NoSuchMethodException missing){throw new AssertionError("missing explicit settings snapshot boundary transaction");}
    CompletionCursor c=new CompletionCursor();
    check(!receive(c,10,true,9,10),"initial baseline");
    // OFF does not reset a baseline; already-polled closed-period events advance silently.
    check(!receive(c,11,false,9,10,11),"disabled consumption");
    check(start(c),"enable starts snapshot transaction");
    check(!start(c),"concurrent setting transaction is rejected");
    check(!c.receive("pc",13,10,new long[]{10,11,12,13},false,true,()->{throw new AssertionError("in-flight poll consumed an event while enabling");}),"pending poll is deferred without checkpointing");
    long[] saved={-1};
    check(cut(c,"pc",12,value->{saved[0]=value;return true;}),"enable commits explicit PC cut point");
    check(saved[0]==12,"only snapshot history is consumed");finish(c);
    check(receive(c,13,true,10,11,12,13),"first poll after enable vibrates for completion AFTER snapshot, including event arriving before commit");
    check(!receive(c,13,true,10,11,12,13),"no duplicate after enable");

    // Paused but not polled: snapshot consumes 14..16, first new event 17 must survive.
    check(start(c),"resume begins transaction");
    check(cut(c,"pc",16,value->true),"resume consumes unpolled paused-period history");finish(c);
    check(!receive(c,16,true,13,14,15,16),"unpolled paused-period completion never replays");
    check(receive(c,17,true,14,15,16,17),"first completion after resume snapshot vibrates");

    // A snapshot older than the local cursor must persist the higher cursor.
    check(start(c),"stale snapshot transaction begins");
    check(cut(c,"pc",15,value->{saved[0]=value;return true;}),"old snapshot can retain higher checkpoint");finish(c);
    check(saved[0]==17,"durable cursor never regresses on older setting snapshot");
    check(!receive(c,17,true,14,15,16,17),"older snapshot did not permit replay");
    check(receive(c,18,true,15,16,17,18),"old snapshot does not swallow next new completion");

    // Snapshot or persistence failures leave old setting/cursor semantics intact.
    check(start(c),"failed save begins");
    check(!cut(c,"pc",20,value->false),"failed atomic setting/checkpoint persistence does not commit");finish(c);
    check(receive(c,19,true,16,17,18,19),"failed save did not move cursor");
    check(start(c),"failed request begins");finish(c);
    check(receive(c,20,true,17,18,19,20),"failed request finally releases deferred reception");

    // Explicit snapshot may establish a first-ever baseline, without discarding the next event.
    CompletionCursor initial=new CompletionCursor();check(start(initial),"uninitialized enable begins");
    check(cut(initial,"pc",5,value->true),"initial cut point commits");finish(initial);
    check(receive(initial,6,true,4,5,6),"no blanket silent first poll after setting change");
    networkAtomicity(Paths.get(args.length==0?".":args[0]));
    System.out.println("completion setting boundaries passed");
  }
}
