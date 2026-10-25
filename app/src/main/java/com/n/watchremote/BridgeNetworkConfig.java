package com.n.watchremote;
import java.util.*;
/** Immutable bridge transport settings. Never holds credentials or changes task semantics. */
final class BridgeNetworkConfig {
  private static final long[] DEFAULT_RETRY={1000,2000,4000,8000,15000};
  static final int SERVER_EVENT_WAIT_MS=20000;
  static final BridgeNetworkConfig DEFAULT=new BridgeNetworkConfig(Collections.emptyMap());
  final String address,transport;
  final int connectMs,readMs,sendReadMs,managementReadMs,snapshotConnectMs,snapshotReadMs;
  final int eventsConnectMs,eventsReadMs,audioConnectMs,audioReadMs;
  private final long[] retry;
  static BridgeNetworkConfig from(Map<String,?> values){return new BridgeNetworkConfig(values);}
  private BridgeNetworkConfig(Map<String,?> values){
    address=text(values,"base","");String mode=text(values,"network.transport","auto");
    transport=Arrays.asList("auto","lan","tailscale","usb").contains(mode)?mode:"auto";
    connectMs=timeout(values,"connect_ms",4000,250,120000);readMs=timeout(values,"read_ms",8000,1000,600000);
    sendReadMs=timeout(values,"send_read_ms",60000,1000,600000);
    managementReadMs=timeout(values,"management_read_ms",20000,1000,600000);
    snapshotConnectMs=timeout(values,"snapshot_connect_ms",5000,250,120000);
    snapshotReadMs=timeout(values,"snapshot_read_ms",7000,1000,600000);
    eventsConnectMs=timeout(values,"events_connect_ms",5000,250,120000);
    eventsReadMs=Math.max(SERVER_EVENT_WAIT_MS+1000,timeout(values,"events_read_ms",28000,1000,600000));
    audioConnectMs=timeout(values,"audio_connect_ms",5000,250,120000);
    audioReadMs=timeout(values,"audio_read_ms",120000,1000,600000);
    retry=schedule(text(values,"network.retry_ms",""));
  }
  private static String text(Map<String,?> values,String key,String fallback){Object value=values.get(key);return value instanceof String?(String)value:fallback;}
  private static int timeout(Map<String,?> values,String key,int fallback,int minimum,int maximum){
    Object value=values.get("network."+key);
    if(!(value instanceof String||value instanceof Integer||value instanceof Long))return fallback;
    try{long parsed=Long.parseLong(value.toString().trim());return parsed>=minimum&&parsed<=maximum?(int)parsed:fallback;}
    catch(NumberFormatException invalid){return fallback;}
  }
  private static long[] schedule(String value){
    String[] items=value.split(",");
    if(items.length==5)try{
      long[] parsed=new long[5];
      for(int i=0;i<5;i++){parsed[i]=Long.parseLong(items[i].trim());if(parsed[i]<250||parsed[i]>60000||i>0&&parsed[i]<parsed[i-1])return DEFAULT_RETRY.clone();}
      return parsed;
    }catch(NumberFormatException invalid){}
    return DEFAULT_RETRY.clone();
  }
  int readTimeout(String path){
    return path.equals("/assistant/send")||path.equals("/assistant/activate")?sendReadMs:
      path.equals("/assistant/conversations/manage")||path.equals("/assistant/conversations/settings")?managementReadMs:readMs;
  }
  long retryDelay(int failures){return retry[failures<=1?0:Math.min(retry.length-1,failures-1)];}
}
