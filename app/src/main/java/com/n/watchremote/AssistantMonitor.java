package com.n.watchremote;
import android.app.*;
import android.content.*;
import android.os.*;
import android.net.*;
import org.json.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** One bounded long poll. No microphone, wake lock or periodic UI timer. */
public class AssistantMonitor extends Service {
  static final String UPDATE="com.n.watchremote.ASSISTANT_UPDATE";
  static final String ATTENTION="assistant-attention-v3",QUIET="assistant-quiet-v3";
  public static volatile JSONObject latest=new JSONObject();
  public static volatile boolean connected;
  public static volatile long lastContact;
  static volatile AssistantMonitor instance;
  static volatile String selectedThread="";
  final ExecutorService canceller=Executors.newSingleThreadExecutor();
  volatile String attentionTarget="";
  volatile boolean attentionInbox;
  volatile boolean running;
  volatile HttpURLConnection connection;
  Thread listener;
  final Object retryLock=new Object();
  volatile int reconnectVersion;
  volatile boolean forceSnapshot;
  ConnectivityManager.NetworkCallback networks;
  volatile Network activeNetwork;
  volatile boolean networkValidated;
  static void selectView(String thread){
    String target=thread.startsWith("new:")?"":thread;
    if(target.equals(selectedThread))return;selectedThread=target;
    AssistantMonitor live=instance;
    if(live!=null)live.reconnect();
  }
  static void reconnectSoon(){AssistantMonitor live=instance;if(live!=null)live.reconnect();}
  void reconnect(){
    synchronized(retryLock){forceSnapshot=true;reconnectVersion++;retryLock.notifyAll();}
    final HttpURLConnection previous=connection;
    if(previous!=null&&!canceller.isShutdown())canceller.execute(previous::disconnect);
  }
  @Override public void onCreate(){super.onCreate();instance=this;
    String target=getSharedPreferences("remote",MODE_PRIVATE).getString("viewedConversation","");
    selectedThread=target.startsWith("new:")?"":target;
    NotificationManager nm=getSystemService(NotificationManager.class);
    NotificationChannel quiet=new NotificationChannel(QUIET,"连接与安静结果",NotificationManager.IMPORTANCE_LOW);
    quiet.enableVibration(false);quiet.setSound(null,null);nm.createNotificationChannel(quiet);
    NotificationChannel alerts=new NotificationChannel(ATTENTION,"需要你处理",NotificationManager.IMPORTANCE_HIGH);
    alerts.enableVibration(true);alerts.setVibrationPattern(new long[]{0,180,100,180});alerts.setSound(null,null);
    nm.createNotificationChannel(alerts);
    networks=new ConnectivityManager.NetworkCallback(){
      public void onAvailable(Network network){activeNetwork=network;networkValidated=false;reconnect();}
      public void onCapabilitiesChanged(Network network,NetworkCapabilities caps){
        if(network.equals(activeNetwork)&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)&&!networkValidated){networkValidated=true;reconnect();}
      }
      public void onLost(Network network){if(network.equals(activeNetwork)){activeNetwork=null;networkValidated=false;reconnect();}}
    };
    try{getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(networks);}catch(SecurityException ignored){networks=null;}
  }
  Notification notification(boolean attention,String title,String text){
    Intent open=openIntent(attention);
    PendingIntent pending=PendingIntent.getActivity(this,attention?3:2,open,
      PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    Notification.Builder b=new Notification.Builder(this,attention?ATTENTION:QUIET)
      .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(text)
      .setContentIntent(pending).setOnlyAlertOnce(true).setAutoCancel(false);
    if(attention)b.setCategory(Notification.CATEGORY_REMINDER).setFullScreenIntent(pending,true);
    return b.build();
  }
  Intent openIntent(boolean attention){
    return new Intent(this,AssistantActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP)
      .putExtra("attention",attention).putExtra("inbox",attention&&attentionInbox)
      .putExtra("thread_id",attention&&!attentionTarget.isEmpty()?attentionTarget:latest.optString("thread_id"));
  }
  @Override public int onStartCommand(Intent intent,int flags,int startId){
    if(running)return START_NOT_STICKY;
    startForeground(40,notification(false,"腕上助手","正在连接电脑"));
    if(!running){running=true;listener=new Thread(this::listen,"assistant-events");listener.start();}
    return START_NOT_STICKY;
  }
  static boolean fresh(){return connected&&SystemClock.elapsedRealtime()-lastContact<35000;}
  void broadcast(){sendBroadcast(new Intent(UPDATE).setPackage(getPackageName()));}
  void alert(android.content.SharedPreferences prefs,String key,String title,String detail){
    if(key.equals(prefs.getString("lastAttentionV3","")))return;
    prefs.edit().putString("lastAttentionV3",key).apply();
    NotificationManager nm=getSystemService(NotificationManager.class);
    nm.cancel(41);
    nm.notify(41,notification(true,title,detail));
    wakeOnce();
  }
  @SuppressWarnings("deprecation")
  void wakeOnce(){
    PowerManager power=getSystemService(PowerManager.class);
    if(power.isInteractive())return;
    // Wake only for a new actionable event. Release immediately: the system timeout wins.
    try{
      PowerManager.WakeLock wake=power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK|
        PowerManager.ACQUIRE_CAUSES_WAKEUP|PowerManager.ON_AFTER_RELEASE,"WristCodex:attention");
      wake.acquire(1000);wake.release();
      // Some watch builds ignore full-screen notifications. Try the normal activity
      // API once; Android may reject a background launch, leaving the notification.
      try{startActivity(openIntent(true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      }catch(RuntimeException ignored){}
    }catch(SecurityException ignored){} // The notification remains if the OEM blocks waking.
  }
  void desktopAlerts(JSONObject state,android.content.SharedPreferences prefs){
    NotificationManager nm=getSystemService(NotificationManager.class);
    boolean enabled=state.optBoolean("desktop_attention_enabled",true);
    boolean localMute=prefs.getBoolean("desktopLocalMute",false);
    if(!enabled)prefs.edit().remove("desktopLocalMute").apply();
    if(!enabled||localMute){nm.cancel(41);return;}
    JSONArray array=state.optJSONArray("attention_keys"),previews=state.optJSONArray("attention_preview");
    Set<String> current=new HashSet<>();if(array!=null)for(int i=0;i<array.length();i++)current.add(array.optString(i));
    Set<String> seen=new HashSet<>(prefs.getStringSet("desktopSeenRequestsV5",Collections.emptySet()));
    Set<String> fresh=new HashSet<>(current);fresh.removeAll(seen);
    if(state.optInt("attention_count")==0){
      if(state.optBoolean("needs_attention")&&state.optString("state").equals("failed")){
        attentionTarget=state.optString("thread_id");attentionInbox=false;
        alert(prefs,"desktop-failed:"+attentionTarget+":"+state.optString("turn_id"),"任务需要你处理",state.optString("message"));
      }else nm.cancel(41);
      return;
    }
    JSONObject first=previews==null?null:previews.optJSONObject(0);
    if(previews!=null)for(int i=0;i<previews.length();i++){JSONObject entry=previews.optJSONObject(i);if(entry!=null&&fresh.contains(entry.optString("id"))){first=entry;break;}}
    attentionInbox=state.optInt("attention_conversations")>1;
    attentionTarget=first==null?"":first.optString("thread_id");
    String detail=attentionInbox?state.optInt("attention_count")+" 项请求等待你决定":first==null?"点开查看待处理请求":first.optString("summary");
    if(fresh.isEmpty()){
      // Refresh an existing card after the computer resolves part of a batch.
      // Never recreate a dismissed notification or wake the screen again.
      for(android.service.notification.StatusBarNotification active:nm.getActiveNotifications())
        if(active.getId()==41){nm.notify(41,notification(true,"电脑需要你决定",detail));break;}
      return;
    }
    seen.addAll(fresh);
    if(seen.size()>256){Iterator<String> old=seen.iterator();while(old.hasNext()&&seen.size()>256){String key=old.next();if(!current.contains(key))old.remove();}}
    prefs.edit().putStringSet("desktopSeenRequestsV5",seen).apply();
    alert(prefs,"desktop:"+Integer.toHexString(fresh.hashCode())+":"+state.optLong("revision"),"电脑需要你决定",detail);
  }
  void listen(){
    android.content.SharedPreferences prefs=getSharedPreferences("remote",MODE_PRIVATE);
    long revision=-1;int failures=0;boolean hadContact=false;String outage=Long.toString(System.currentTimeMillis()),polledThread=null,quietState="";
    while(running){
      final String target=selectedThread;
      final int generation;
      synchronized(retryLock){generation=reconnectVersion;if(forceSnapshot){revision=-1;failures=0;forceSnapshot=false;}}
      if(!target.equals(polledThread)){revision=-1;polledThread=target;}
      try{
        String base=prefs.getString("base",""),token=prefs.getString("token","");
        if(base.isEmpty()||token.isEmpty())throw new IOException("尚未配置电脑");
        connection=(HttpURLConnection)new URL(base+"/assistant/events?compact=1&after="+revision+
          (target.isEmpty()?"":"&thread_id="+URLEncoder.encode(target,"UTF-8"))+"&t="+URLEncoder.encode(token,"UTF-8")).openConnection();
        connection.setConnectTimeout(5000);connection.setReadTimeout(28000);
        if(connection.getResponseCode()!=200)throw new IOException("连接未成功");
        StringBuilder body=new StringBuilder();
        try(Reader r=new InputStreamReader(connection.getInputStream(),StandardCharsets.UTF_8)){
          char[] buffer=new char[2048];int n;while((n=r.read(buffer))!=-1){if(body.length()+n>70000)throw new IOException("状态过大");body.append(buffer,0,n);}
        }
        JSONObject state=new JSONObject(body.toString());long next=state.optLong("revision",-1);
        if(generation!=reconnectVersion||!target.equals(selectedThread))continue;
        boolean changed=!connected||revision!=next||!state.optString("thread_id").equals(latest.optString("thread_id"));
        latest=state;revision=next;connected=true;lastContact=SystemClock.elapsedRealtime();
        hadContact=true;failures=0;outage=Long.toString(System.currentTimeMillis());
        JSONArray pending=state.optJSONArray("pending");
        NotificationManager nm=getSystemService(NotificationManager.class);
        if(state.optInt("protocol_version")>=5){desktopAlerts(state,prefs);}
        else if(pending!=null&&pending.length()>0){
          attentionTarget=state.optString("thread_id");attentionInbox=false;
          JSONObject item=pending.getJSONObject(0);
          alert(prefs,"pending:"+item.optString("id"),"需要你决定",item.optString("summary","有操作需要你确认，点开查看选项"));
        }else if(state.optBoolean("needs_attention",state.optString("state").equals("failed"))){
          alert(prefs,state.optString("turn_id")+":"+state.optString("request_id")+":"+state.optString("state"),
            "任务需要你处理",state.optString("message"));
        }else nm.cancel(41);
        if(changed){
          String nextQuiet=target+":"+state.optString("state")+":"+state.optString("message");
          if(!nextQuiet.equals(quietState)){quietState=nextQuiet;nm.notify(40,notification(false,"腕上助手 · 已连接",state.optString("message","准备接收任务")));}
          broadcast();
        }
        // A completed response can race a conversation switch without throwing.
        // Read the newly selected conversation immediately, even at the same revision.
        if(!target.equals(selectedThread))revision=-1;
      }catch(Exception e){
        if(!running)break;
        if(generation!=reconnectVersion||!target.equals(selectedThread)){revision=-1;continue;}
        boolean wasConnected=connected;connected=false;failures++;
        quietState="";
        if(wasConnected||failures==1){
          getSystemService(NotificationManager.class).notify(40,notification(false,"电脑未连接","未发送的文字会保留，连接恢复后再试"));broadcast();
        }
        String status=latest.optString("state");
        boolean alertsAllowed=latest.optInt("protocol_version")<5||
          (latest.optBoolean("desktop_attention_enabled",true)&&!prefs.getBoolean("desktopLocalMute",false));
        if(alertsAllowed&&hadContact&&failures>=3&&(status.equals("running")||status.equals("waiting")||status.equals("starting")))
          alert(prefs,"outage:"+outage,"暂时联系不上电脑","任务状态未知，请检查电脑服务和网络；不会重复发送任务");
        if(connection!=null){connection.disconnect();connection=null;}
        synchronized(retryLock){
          if(running&&generation==reconnectVersion)try{retryLock.wait(UiState.retryDelay(failures));}catch(InterruptedException ignored){}
        }
      }finally{if(connection!=null){connection.disconnect();connection=null;}}
    }
  }
  @Override public void onDestroy(){running=false;connected=false;if(instance==this)instance=null;
    if(networks!=null)try{getSystemService(ConnectivityManager.class).unregisterNetworkCallback(networks);}catch(IllegalArgumentException ignored){}
    reconnect();if(listener!=null)listener.interrupt();canceller.shutdown();super.onDestroy();}
  @Override public IBinder onBind(Intent i){return null;}
}
