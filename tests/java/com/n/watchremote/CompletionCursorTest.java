package com.n.watchremote;
import java.lang.reflect.*;
import java.util.function.BooleanSupplier;
public final class CompletionCursorTest {
  static Class<?> type;
  static Object fresh() throws Exception {return type.getDeclaredConstructor().newInstance();}
  static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
  static boolean receive(Object cursor,String scope,long end,long oldest,long[] events,boolean enabled,boolean current,BooleanSupplier save)throws Exception{
    Method method=type.getDeclaredMethod("receive",String.class,long.class,long.class,long[].class,boolean.class,boolean.class,BooleanSupplier.class);
    return (Boolean)method.invoke(cursor,scope,end,oldest,events,enabled,current,save);
  }
  static boolean batch(Object c,String s,long end,long oldest,long... events)throws Exception{return receive(c,s,end,oldest,events,true,true,()->true);}
  public static void main(String[] args)throws Exception{
    try{type=Class.forName("com.n.watchremote.CompletionCursor");}catch(ClassNotFoundException missing){throw new AssertionError("missing completion cursor policy: first batch must establish a silent baseline");}
    Object c=fresh();
    check(!batch(c,"a",2,1,1,2),"initial history is silent");
    check(batch(c,"a",4,1,1,2,3,4),"several new completions merge to one alert");
    check(!batch(c,"a",4,1,1,2,3,4),"duplicate response is silent");
    check(!batch(c,"a",3,1,1,2,3),"old response must not regress cursor");
    check(!batch(c,"a",4,1,1,2,3,4),"old response cannot replay newer history");
    check(!batch(c,"b",6,5,5,6),"scope change establishes baseline");
    check(!receive(c,"b",7,5,new long[]{5,6,7},false,true,()->true),"disabled consumes cursor silently");
    check(!batch(c,"b",7,5,5,6,7),"enabling does not replay");
    check(batch(c,"b",8,5,5,6,7,8),"new event after enabling alerts");
    check(!batch(c,"b",12,11,11,12),"bounded buffer gap is silent baseline");
    check(batch(c,"b",13,11,11,12,13),"contiguous event after gap alerts");
    check(!receive(c,"b",14,11,new long[]{11,12,13,14},true,false,()->{throw new AssertionError("stale connection persisted");}),"old connection is ignored");
    check(batch(c,"b",14,11,11,12,13,14),"stale connection did not consume new event");
    int[] writes={0};
    check(!receive(c,"b",15,11,new long[]{11,12,13,14,15},true,true,()->{writes[0]++;return false;}),"failed persistence forbids vibration");
    check(writes[0]==1,"persistence attempted exactly once");
    check(batch(c,"b",15,11,11,12,13,14,15),"retry persists before its single alert");
    check(!batch(c,"b",15,11,11,12,13,14,15),"committed event never retries vibration");
    type.getDeclaredMethod("resetBaseline").invoke(c);
    check(!batch(c,"b",16,11,11,12,13,14,15,16),"reconnect first response consumes history silently");
    check(!batch(c,"b",18,11,11,12,13,14,15,16,18),"internal sequence gap is silent");
    check(batch(c,"b",19,18,18,19),"gap rebaseline recovers");
    check(!batch(c,"b",20,18,18,19,19,20),"duplicate sequence is malformed and silent");
    check(!batch(c,"b",-1,1),"negative cursor is ignored");
    Object restored;
    try{restored=type.getDeclaredConstructor(String.class,long.class).newInstance("saved",20L);}catch(NoSuchMethodException missing){throw new AssertionError("missing durable cursor restore fence");}
    check(!batch(restored,"saved",18,17,17,18),"restart must not regress durable cursor on an older response");
    check(!batch(restored,"saved",20,17,17,18,19,20),"restart first current response is still a silent baseline");
    check(batch(restored,"saved",21,18,18,19,20,21),"new event after restored baseline alerts");
    HapticPolicy.Gates g=new HapticPolicy.Gates();
    g.enabled=g.appEnabled=g.channelHigh=g.channelVibrates=g.dndAllows=g.ringerAllows=g.permission=g.hardware=g.current=true;
    g.desktopEnabled=false;g.muted=true;g.interactive=false;
    Method blocked;
    try{blocked=HapticPolicy.class.getDeclaredMethod("blockedCompletion",HapticPolicy.Gates.class);}catch(NoSuchMethodException missing){throw new AssertionError("missing independent completion haptic gates");}
    check(blocked.invoke(null,g).equals(""),"confirmation disabled and screen off must not block completion");
    g.paused=true;check(!blocked.invoke(null,g).equals(""),"global pause blocks completion");g.paused=false;
    g.dndAllows=false;check(!blocked.invoke(null,g).equals(""),"DND blocks completion");g.dndAllows=true;
    g.enabled=false;check(!blocked.invoke(null,g).equals(""),"completion switch blocks completion");g.enabled=true;
    g.appEnabled=false;check(!blocked.invoke(null,g).equals(""),"notification permission blocks completion");g.appEnabled=true;
    g.current=false;check(!blocked.invoke(null,g).equals(""),"stale scope blocks completion");
    System.out.println("completion cursor policy passed");
  }
}
