package com.n.watchremote;
import java.lang.reflect.*;
import java.util.*;
public final class BridgeNetworkConfigTest {
  static Class<?> type;
  static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
  static Object config(Map<String,Object> values)throws Exception{return type.getDeclaredMethod("from",Map.class).invoke(null,values);}
  static Object field(Object value,String name)throws Exception{return type.getDeclaredField(name).get(value);}
  static int number(Object value,String name)throws Exception{return ((Number)field(value,name)).intValue();}
  static long retry(Object value,int count)throws Exception{return ((Number)type.getDeclaredMethod("retryDelay",int.class).invoke(value,count)).longValue();}
  static int read(Object value,String path)throws Exception{return (Integer)type.getDeclaredMethod("readTimeout",String.class).invoke(value,path);}
  public static void main(String[] args)throws Exception{
    try{type=Class.forName("com.n.watchremote.BridgeNetworkConfig");}catch(ClassNotFoundException missing){throw new AssertionError("missing independent bridge-network configuration",missing);}
    Map<String,Object> values=new HashMap<>();values.put("base","http://192.168.137.1:18882");
    Object defaults=config(values);
    check(field(defaults,"address").equals(values.get("base"))&&field(defaults,"transport").equals("auto"),"existing address retained, transport independent");
    String[] fields={"connectMs","readMs","sendReadMs","managementReadMs","snapshotConnectMs","snapshotReadMs","eventsConnectMs","eventsReadMs","audioConnectMs","audioReadMs"};
    int[] expected={4000,8000,60000,20000,5000,7000,5000,28000,5000,120000};
    for(int i=0;i<fields.length;i++)check(number(defaults,fields[i])==expected[i],"preserve default "+fields[i]);
    check(read(defaults,"/assistant/send")==60000&&read(defaults,"/assistant/activate")==60000,"send/activation timeout category");
    check(read(defaults,"/assistant/conversations/settings")==20000&&read(defaults,"/assistant/conversations/manage")==20000,"management timeout category");
    check(read(defaults,"/assistant/answer")==8000&&read(defaults,"/assistant/steer")==8000,"existing control semantics unchanged");
    long[] schedule={1000,1000,2000,4000,8000,15000,15000};
    for(int i=0;i<schedule.length;i++)check(retry(defaults,i)==schedule[i],"bounded default retry "+i);
    check(retry(defaults,Integer.MIN_VALUE)==1000,"negative failure count retains original lower bound without overflow");
    values.put("network.connect_ms",9000);values.put("network.read_ms","25000");values.put("network.transport","tailscale");
    values.put("network.events_read_ms",1000);values.put("network.retry_ms","1500,3000,6000,12000,20000");
    Object configured=config(values);
    check(number(configured,"connectMs")==9000&&read(configured,"/assistant/answer")==25000,"external configuration applies without business changes");
    check(field(configured,"transport").equals("tailscale"),"transport label does not choose another business implementation");
    check(number(configured,"eventsReadMs")>20000,"client long-poll read timeout covers server hold");
    check(retry(configured,1)==1500&&retry(configured,99)==20000,"configured bounded retry schedule");
    values.put("base","http://changed.test");values.put("network.retry_ms","1,1,1,1,1");
    check(field(configured,"address").equals("http://192.168.137.1:18882")&&retry(configured,2)==3000,"configuration snapshot cannot change under a request");
    values.put("network.connect_ms",-1);values.put("network.read_ms",true);values.put("network.events_read_ms",Integer.MAX_VALUE);values.put("network.transport","unknown");
    Object invalid=config(values);
    check(number(invalid,"connectMs")==4000&&number(invalid,"readMs")==8000&&number(invalid,"eventsReadMs")==28000,"invalid and unbounded values safely retain defaults");
    check(field(invalid,"transport").equals("auto")&&retry(invalid,5)==15000,"invalid mode and schedule retain defaults");
  }
}
