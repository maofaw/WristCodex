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

/** One bounded long poll. Metadata reconciliation is sequential; no periodic UI timer. */
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
  volatile JSONObject attentionRequest=new JSONObject();
  AttentionLedger ledger;AttentionLedger.Validation validation;String validationOwner=""; String ledgerError="",notificationScope="";
  static final Object alertGate=new Object();
  static boolean localTestPending;
  CompletionCursor completionCursor=new CompletionCursor();
  volatile boolean completionBaseline=true;
  String completionOwner="";
  long attentionPosts;
  final ConcurrentLinkedQueue<String[]> completions=new ConcurrentLinkedQueue<>();
  static void processed(String scope,String id){AssistantMonitor monitor=instance;if(monitor!=null){monitor.completions.add(new String[]{scope,id});monitor.reconnect();}}
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
  void completionReconnect(){
    synchronized(alertGate){completionBaseline=true;reconnect();}
  }
  void completionDisconnected(int generation){
    synchronized(alertGate){if(generation==reconnectVersion)completionBaseline=true;}
  }
  @Override public void onCreate(){super.onCreate();instance=this;
    android.content.SharedPreferences completionPrefs=getSharedPreferences("remote",MODE_PRIVATE);
    completionCursor=new CompletionCursor(completionPrefs.getString("completionCursorScope",""),completionPrefs.getLong("completionCursor",0));
    String target=getSharedPreferences("remote",MODE_PRIVATE).getString("viewedConversation","");
    selectedThread=target.startsWith("new:")?"":target;
    NotificationManager nm=getSystemService(NotificationManager.class);
    NotificationChannel quiet=new NotificationChannel(QUIET,"连接与安静结果",NotificationManager.IMPORTANCE_LOW);
    quiet.enableVibration(false);quiet.setSound(null,null);nm.createNotificationChannel(quiet);
    NotificationChannel alerts=new NotificationChannel(ATTENTION,"需要你处理",NotificationManager.IMPORTANCE_HIGH);
    alerts.enableVibration(true);alerts.setVibrationPattern(new long[]{0,180,100,180});alerts.setSound(null,null);
    nm.createNotificationChannel(alerts);
    networks=new ConnectivityManager.NetworkCallback(){
      public void onAvailable(Network network){activeNetwork=network;networkValidated=false;completionReconnect();}
      public void onCapabilitiesChanged(Network network,NetworkCapabilities caps){
        if(network.equals(activeNetwork)&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)&&!networkValidated){networkValidated=true;completionReconnect();}
      }
      public void onLost(Network network){if(network.equals(activeNetwork)){activeNetwork=null;networkValidated=false;completionReconnect();}}
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
    if(attention&&wakeAllowed(this))b.setCategory(Notification.CATEGORY_REMINDER).setFullScreenIntent(pending,true);
    if(attention)compatibilityNotification(this,b);
    return b.build();
  }
  Intent openIntent(boolean attention){
    Intent intent=new Intent(this,AssistantActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP)
      .putExtra("attention",attention).putExtra("inbox",attention&&attentionInbox)
      .putExtra("thread_id",attention&&!attentionTarget.isEmpty()?attentionTarget:latest.optString("thread_id"));
    if(attention)intent.setData(Uri.parse("wristcodex://attention/"+ConversationState.hash(latest.optString("connection_scope")+attentionRequest.toString()+attentionInbox))).putExtra("request",attentionRequest.toString()).putExtra("connection_scope",latest.optString("connection_scope"));
    return intent;
  }
  @Override public int onStartCommand(Intent intent,int flags,int startId){
    if(running)return START_NOT_STICKY;
    startForeground(40,notification(false,"腕上助手","正在连接电脑"));
    if(!running){running=true;listener=new Thread(this::listen,"assistant-events");listener.start();}
    return START_NOT_STICKY;
  }
  static boolean fresh(){return connected&&SystemClock.elapsedRealtime()-lastContact<35000;}
  void broadcast(){sendBroadcast(new Intent(UPDATE).setPackage(getPackageName()));}
  static boolean deliveryAllowed(Context context){
    android.content.SharedPreferences p=context.getSharedPreferences("remote",MODE_PRIVATE);
    NotificationManager nm=context.getSystemService(NotificationManager.class);
    NotificationChannel ch=nm.getNotificationChannel(ATTENTION);
    return !p.getBoolean("desktopLocalMute",false)&&!p.getBoolean("attentionPaused",false)&&latest.optBoolean("desktop_attention_enabled",true)
      &&nm.areNotificationsEnabled()&&ch!=null&&ch.getImportance()!=NotificationManager.IMPORTANCE_NONE;
  }
  static boolean wakeAllowed(Context context){
    NotificationManager nm=context.getSystemService(NotificationManager.class);NotificationChannel ch=nm.getNotificationChannel(ATTENTION);
    return deliveryAllowed(context)&&nm.getCurrentInterruptionFilter()==NotificationManager.INTERRUPTION_FILTER_ALL&&ch!=null&&ch.getImportance()>=NotificationManager.IMPORTANCE_HIGH&&ch.shouldVibrate();
  }
  static boolean compatibilityEnabled(Context c){return c.getSharedPreferences("remote",MODE_PRIVATE).getBoolean("watchCompatibleVibration",false);}
  static boolean completionEnabled(Context c){return c.getSharedPreferences("remote",MODE_PRIVATE).getBoolean("completionVibrationEnabled",true);}
  boolean completionTargetCurrent(android.content.SharedPreferences prefs,int generation,String base,String token,String raw){
    return running&&instance==this&&generation==reconnectVersion&&raw.equals(latest.optString("connection_scope"))
      &&base.equals(prefs.getString("base",""))&&token.equals(prefs.getString("token",""));
  }
  void completionAlerts(JSONObject state,android.content.SharedPreferences prefs,int generation,String base,String token,boolean confirmationPosted){
    JSONObject batch=state.optJSONObject("completion_notifications");
    synchronized(alertGate){
      String raw=state.optString("connection_scope");
      if(raw.isEmpty()||!completionTargetCurrent(prefs,generation,base,token,raw))return;
      if(completionCursor.rebasePending())return;
      String owner=ConversationState.hash(base+":"+token);
      if(!owner.equals(completionOwner)){completionCursor.resetBaseline();completionOwner=owner;}
      if(completionBaseline){completionCursor.resetBaseline();completionBaseline=false;}
      if(batch==null||!batch.optBoolean("available")){completionCursor.resetBaseline();return;}
      if(!raw.equals(batch.optString("scope")))return;
      JSONArray events=batch.optJSONArray("events");if(events==null||events.length()>64)return;
      long end=batch.optLong("cursor",-1),oldest=batch.optLong("oldest",-1);
      long[] sequences=new long[events.length()];
      for(int i=0;i<events.length();i++){JSONObject event=events.optJSONObject(i);if(event==null)return;sequences[i]=event.optLong("sequence",-1);}
      // Persist only a hashed connection namespace and cursor, never event titles or credentials.
      String namespace=ConversationState.hash(base+":"+raw);
      boolean fresh=completionCursor.receive(namespace,end,oldest,sequences,completionEnabled(this),true,()->
        completionTargetCurrent(prefs,generation,base,token,raw)&&prefs.edit().putString("completionCursorScope",namespace).putLong("completionCursor",end).commit());
      if(!fresh||confirmationPosted||!completionTargetCurrent(prefs,generation,base,token,raw))return;
      HapticPolicy.Gates gates=hapticGates(this,this,generation,raw);gates.enabled=completionEnabled(this);
      if(!HapticPolicy.blockedCompletion(gates).isEmpty())return;
      try{getSystemService(Vibrator.class).vibrate(VibrationEffect.createOneShot(180,VibrationEffect.DEFAULT_AMPLITUDE),
        new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
          .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build());}
      catch(RuntimeException ignored){} // The checkpoint is consumed even if the hardware rejects it.
    }
  }
  static void compatibilityNotification(Context c,Notification.Builder builder){
    if(compatibilityEnabled(c))builder.setGroup(c.getPackageName()+".compat."+SystemClock.elapsedRealtimeNanos())
      .setGroupSummary(false).setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY);
  }
  static HapticPolicy.Gates hapticGates(Context c,AssistantMonitor owner,int generation,String scope){
    android.content.SharedPreferences p=c.getSharedPreferences("remote",MODE_PRIVATE);
    NotificationManager nm=c.getSystemService(NotificationManager.class);NotificationChannel ch=nm.getNotificationChannel(ATTENTION);
    android.media.AudioManager audio=c.getSystemService(android.media.AudioManager.class);Vibrator vibrator=c.getSystemService(Vibrator.class);
    HapticPolicy.Gates g=new HapticPolicy.Gates();g.enabled=compatibilityEnabled(c);
    g.appEnabled=nm.areNotificationsEnabled();g.desktopEnabled=latest.optBoolean("desktop_attention_enabled",true);
    g.muted=p.getBoolean("desktopLocalMute",false);g.paused=p.getBoolean("attentionPaused",false);
    g.channelHigh=ch!=null&&ch.getImportance()>=NotificationManager.IMPORTANCE_HIGH;g.channelVibrates=ch!=null&&ch.shouldVibrate();
    g.dndAllows=nm.getCurrentInterruptionFilter()==NotificationManager.INTERRUPTION_FILTER_ALL;
    g.ringerAllows=audio!=null&&audio.getRingerMode()!=android.media.AudioManager.RINGER_MODE_SILENT;
    g.permission=c.checkSelfPermission(android.Manifest.permission.VIBRATE)==android.content.pm.PackageManager.PERMISSION_GRANTED;
    g.hardware=vibrator!=null&&vibrator.hasVibrator();g.patternValid=ch!=null&&HapticPolicy.pattern(ch.getVibrationPattern())!=null;
    PowerManager power=c.getSystemService(PowerManager.class);g.interactive=power!=null&&power.isInteractive();
    g.current=owner==instance&&(owner==null||generation==owner.reconnectVersion)&&scope.equals(latest.optString("connection_scope"));
    return g;
  }
  static String vibrationStatus(Context c){
    if(!compatibilityEnabled(c))return "振动模式：系统通知";
    AssistantMonitor owner=instance;String reason=HapticPolicy.blocked(hapticGates(c,owner,owner==null?0:owner.reconnectVersion,latest.optString("connection_scope")));
    return "振动模式：手表兼容振动\n"+(reason.isEmpty()?"可在新提醒时尝试振动":"未振动原因："+reason);
  }
  static void compatibleHaptic(Context c,AssistantMonitor owner,int generation,String scope){
    compatibleHaptic(c,owner,generation,scope,null);
  }
  static int diagnosticGeneration(AssistantMonitor owner){return owner==null?0:owner.reconnectVersion;}
  static void finishAttempt(HapticPolicy.Trace trace,AssistantMonitor owner){
    if(trace==null)return;trace.finished(SystemClock.elapsedRealtime(),diagnosticGeneration(owner));
    try{android.util.Log.i("WristCodexAlert",trace.summary());}catch(RuntimeException ignored){}
  }
  static void compatibleHaptic(Context c,AssistantMonitor owner,int generation,String scope,HapticPolicy.Trace trace){
    compatibleHaptic(c,owner,generation,scope,trace,true);
  }
  static void compatibleHaptic(Context c,AssistantMonitor owner,int generation,String scope,HapticPolicy.Trace trace,boolean wakeReady){
    // Caller owns alertGate and has already posted one new notification successfully.
    if(!compatibilityEnabled(c)){if(trace!=null)trace.haptic("blocked","系统通知模式",SystemClock.elapsedRealtime(),diagnosticGeneration(owner));return;}
    try{
      NotificationChannel ch=c.getSystemService(NotificationManager.class).getNotificationChannel(ATTENTION);
      long[] pattern=ch==null?null:HapticPolicy.pattern(ch.getVibrationPattern());
      if(pattern==null){if(trace!=null)trace.haptic("blocked","通道振动节奏不可用",SystemClock.elapsedRealtime(),diagnosticGeneration(owner));return;}
      HapticPolicy.Gates gates=hapticGates(c,owner,generation,scope);gates.interactive&=wakeReady;
      HapticPolicy.attempt(true,gates,()->c.getSystemService(Vibrator.class).vibrate(
        VibrationEffect.createWaveform(pattern,-1),new android.media.AudioAttributes.Builder()
          .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build()),
        trace,SystemClock::elapsedRealtime,()->diagnosticGeneration(owner));
    }catch(RuntimeException ignored){if(trace!=null)trace.hapticUnavailable(SystemClock.elapsedRealtime(),diagnosticGeneration(owner));} // Submission remains valid; never retry a physical alert.
  }
  static void silence(Context c,boolean pause){synchronized(alertGate){c.getSharedPreferences("remote",MODE_PRIVATE).edit().putBoolean(pause?"attentionPaused":"desktopLocalMute",true).apply();c.getSystemService(NotificationManager.class).cancel(41);if(instance!=null)instance.reconnect();}}
  void alert(android.content.SharedPreferences prefs,String key,String title,String detail){
    final int generation;final String scope;final HapticPolicy.Trace trace;final Intent open;
    synchronized(alertGate){
      if(!deliveryAllowed(this)||key.equals(prefs.getString("lastAttentionV3","")))return;
      generation=reconnectVersion;scope=latest.optString("connection_scope");
      trace=HapticPolicy.begin(HapticPolicy.Source.LEGACY,scope,key,generation,SystemClock.elapsedRealtime());
      try{
        trace.posting(SystemClock.elapsedRealtime(),reconnectVersion);
        getSystemService(NotificationManager.class).cancel(41);getSystemService(NotificationManager.class).notify(41,notification(true,title,detail));
        attentionPosts++;
        trace.posted(SystemClock.elapsedRealtime(),reconnectVersion);
        prefs.edit().putString("lastAttentionV3",key).apply();trace.recorded(SystemClock.elapsedRealtime(),reconnectVersion);
        open=openIntent(true);
      }catch(RuntimeException e){trace.postFailed(SystemClock.elapsedRealtime(),reconnectVersion);ledgerError="系统未提交提醒";finishAttempt(trace,this);return;}
    }
    try{attentionEffects(open,generation,scope,trace,()->true);}finally{finishAttempt(trace,this);}
  }
  static String diagnostics(Context c){
    NotificationManager nm=c.getSystemService(NotificationManager.class);NotificationChannel ch=nm.getNotificationChannel(ATTENTION);
    String attempt=HapticPolicy.lastSummary();
    return vibrationStatus(c)+"\n应用通知："+nm.areNotificationsEnabled()+"\n通道重要级别："+(ch==null?"未建立":ch.getImportance())+"\n通道振动："+(ch!=null&&ch.shouldVibrate())+"\n勿扰允许主动亮屏："+(nm.getCurrentInterruptionFilter()==NotificationManager.INTERRUPTION_FILTER_ALL)+"\n暂停："+c.getSharedPreferences("remote",MODE_PRIVATE).getBoolean("attentionPaused",false)+"\n跨会话发现未验证完整；重连身份未验证，不能承诺跨电脑重启不重复。\n系统提交不等于实际振动或亮屏。"+(instance==null?"": "\n"+instance.ledgerError)+(attempt.isEmpty()?"":"\n最近提醒尝试："+attempt);
  }
  static boolean testAlert(Context c){synchronized(alertGate){if(localTestPending||!deliveryAllowed(c))return false;
    Intent open=new Intent(c,AssistantActivity.class).setAction("attention-test").setData(Uri.parse("wristcodex://test")).putExtra("test_alert",true);
    PendingIntent pi=PendingIntent.getActivity(c,42,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    Notification.Builder b=new Notification.Builder(c,ATTENTION).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("提醒测试").setContentText("请实测振动和亮屏；不影响真实待处理").setContentIntent(pi).setAutoCancel(true);
    if(wakeAllowed(c))b.setFullScreenIntent(pi,true);
    compatibilityNotification(c,b);AssistantMonitor owner=instance;int generation=owner==null?0:owner.reconnectVersion;String scope=latest.optString("connection_scope");
    HapticPolicy.Trace trace=HapticPolicy.begin(HapticPolicy.Source.TEST,scope,"local-test",generation,SystemClock.elapsedRealtime());
    try{
      trace.posting(SystemClock.elapsedRealtime(),diagnosticGeneration(owner));
      c.getSystemService(NotificationManager.class).cancel(42);c.getSystemService(NotificationManager.class).notify(42,b.build());
      trace.posted(SystemClock.elapsedRealtime(),diagnosticGeneration(owner));
      // The local test may originate on the UI thread. Never wait for the screen there.
      if(owner==null){trace.wake(HapticPolicy.Wake.SKIPPED_OWNER,SystemClock.elapsedRealtime(),0);finishAttempt(trace,null);return true;}
      localTestPending=true;
      new Thread(()->{try{owner.attentionEffects(open,generation,scope,trace,()->true);}
        finally{finishAttempt(trace,owner);synchronized(alertGate){localTestPending=false;}}},"assistant-test-alert").start();
      return true;
    }catch(RuntimeException e){localTestPending=false;trace.postFailed(SystemClock.elapsedRealtime(),diagnosticGeneration(owner));finishAttempt(trace,owner);return false;}
  }}  @SuppressWarnings("deprecation")
  void wakeOnce(){wakeIntent(openIntent(true),reconnectVersion,latest.optString("connection_scope"));}
  void wakeOnce(HapticPolicy.Trace trace){wakeIntent(openIntent(true),reconnectVersion,latest.optString("connection_scope"),trace);}
  void wakeIntent(Intent open,int generation,String scope){
    wakeIntent(open,generation,scope,null);
  }
  void wakeIntent(Intent open,int generation,String scope,HapticPolicy.Trace trace){
    wakeScreen(generation,scope,trace);openPage(open,generation,scope,trace);
  }
  boolean wakeTargetAllowed(int generation,String scope,HapticPolicy.Trace trace){
    HapticPolicy.Wake blocked=this!=instance?HapticPolicy.Wake.SKIPPED_OWNER:
      generation!=reconnectVersion?HapticPolicy.Wake.SKIPPED_GENERATION:
      !scope.equals(latest.optString("connection_scope"))?HapticPolicy.Wake.SKIPPED_SCOPE:
      !wakeAllowed(this)?HapticPolicy.Wake.SKIPPED_POLICY:null;
    if(blocked==null)return true;
    if(trace!=null)trace.wake(blocked,SystemClock.elapsedRealtime(),reconnectVersion);return false;
  }
  void attentionEffects(Intent open,int generation,String scope,HapticPolicy.Trace trace,java.util.function.BooleanSupplier sameConfig){
    boolean[] ready={false};
    HapticPolicy.afterPost(true,()->{
      ready[0]=wakeScreen(generation,scope,trace,sameConfig);
    },()->{synchronized(alertGate){
      if(sameConfig.getAsBoolean())compatibleHaptic(this,this,generation,scope,trace,ready[0]);
      else if(trace!=null)trace.haptic("blocked","连接配置已变化",SystemClock.elapsedRealtime(),reconnectVersion);
    }},()->{
      openPage(open,generation,scope,trace,sameConfig);
    });
  }
  boolean effectTargetAllowed(int generation,String scope,HapticPolicy.Trace trace,java.util.function.BooleanSupplier sameConfig){
    if(sameConfig.getAsBoolean())return wakeTargetAllowed(generation,scope,trace);
    if(trace!=null)trace.wake(HapticPolicy.Wake.SKIPPED_CONFIG,SystemClock.elapsedRealtime(),reconnectVersion);return false;
  }
  boolean wakeScreen(int generation,String scope,HapticPolicy.Trace trace){
    return wakeScreen(generation,scope,trace,()->true);
  }
  boolean wakeScreen(int generation,String scope,HapticPolicy.Trace trace,java.util.function.BooleanSupplier sameConfig){
    PowerManager power=getSystemService(PowerManager.class);
    CountDownLatch screenOn=new CountDownLatch(1);
    BroadcastReceiver ready=new BroadcastReceiver(){public void onReceive(Context c,Intent event){if(Intent.ACTION_SCREEN_ON.equals(event.getAction()))screenOn.countDown();}};
    boolean registered=false;
    try{
    registerReceiver(ready,new IntentFilter(Intent.ACTION_SCREEN_ON));registered=true;
    boolean[] needsWait={false};
    // Serialize policy commits with dispatch, not with screen readiness. Binder may be slow;
    // UI policy writers run on existing background workers rather than waiting on this gate.
    boolean dispatched=HapticPolicy.guardedDispatch(alertGate,()->effectTargetAllowed(generation,scope,trace,sameConfig),()->{
    if(!power.isInteractive()){
      needsWait[0]=true;
      try{
        if(trace!=null)trace.lock(HapticPolicy.Lock.ATTEMPTED,SystemClock.elapsedRealtime(),reconnectVersion);
        PowerManager.WakeLock wake=power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK|
          PowerManager.ACQUIRE_CAUSES_WAKEUP|PowerManager.ON_AFTER_RELEASE,"WristCodex:attention");
        try{wake.acquire(1000);}finally{if(wake.isHeld())wake.release();}
        if(trace!=null)trace.lock(HapticPolicy.Lock.RETURNED,SystemClock.elapsedRealtime(),reconnectVersion);
      }catch(RuntimeException ignored){if(trace!=null)trace.lock(HapticPolicy.Lock.DENIED,SystemClock.elapsedRealtime(),reconnectVersion);}
    }else if(trace!=null)trace.lock(HapticPolicy.Lock.SKIPPED_INTERACTIVE,SystemClock.elapsedRealtime(),reconnectVersion);
    });
    if(!dispatched)return false;
    if(needsWait[0]){
      // One bounded wait on a worker, outside alertGate. No permanent wake lock or polling.
      if(Looper.myLooper()==Looper.getMainLooper())return false;
      if(trace!=null)trace.ready("WAITING",SystemClock.elapsedRealtime(),reconnectVersion);
      boolean completed=HapticPolicy.awaitScreenOn(screenOn,()->effectTargetAllowed(generation,scope,trace,sameConfig),power::isInteractive,2000);
      if(trace!=null)trace.ready(completed?"RETURNED":"BLOCKED",SystemClock.elapsedRealtime(),reconnectVersion);
      return completed;
    }else return true;
    }catch(RuntimeException unavailable){if(trace!=null)trace.ready("UNAVAILABLE",SystemClock.elapsedRealtime(),reconnectVersion);return false;}
    finally{if(registered)try{unregisterReceiver(ready);}catch(IllegalArgumentException ignored){}}
  }
  void openPage(Intent open,int generation,String scope,HapticPolicy.Trace trace){
    openPage(open,generation,scope,trace,()->true);
  }
  void openPage(Intent open,int generation,String scope,HapticPolicy.Trace trace,java.util.function.BooleanSupplier sameConfig){
    // Some watch builds ignore full-screen notifications, including while awake.
    // Try once independently of waking; a rejected launch leaves the notification.
    try{
      HapticPolicy.guardedDispatch(alertGate,()->effectTargetAllowed(generation,scope,trace,sameConfig),()->{
        if(trace!=null)trace.wake(HapticPolicy.Wake.ATTEMPTED,SystemClock.elapsedRealtime(),reconnectVersion);
        startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        if(trace!=null)trace.wake(HapticPolicy.Wake.RETURNED,SystemClock.elapsedRealtime(),reconnectVersion);
      });
    }catch(RuntimeException ignored){if(trace!=null)trace.wake(HapticPolicy.Wake.FAILED,SystemClock.elapsedRealtime(),reconnectVersion);}
  }
  JSONObject getState(String base,String token,String path)throws Exception{
    return getState(base,token,path,null);
  }
  static void checkpoint(HapticPolicy.Trace trace,HapticPolicy.Stage stage){if(trace!=null)trace.checkpoint(stage,SystemClock.elapsedRealtime(),SystemClock.uptimeMillis());}
  JSONObject getState(String base,String token,String path,HapticPolicy.Trace trace)throws Exception{
    BridgeNetworkConfig network=BridgeNetworkConfig.from(getSharedPreferences("remote",MODE_PRIVATE).getAll());
    connection=(HttpURLConnection)new URL(base+path+(path.contains("?")?"&":"?")+"t="+URLEncoder.encode(token,"UTF-8")).openConnection();
    connection.setConnectTimeout(network.snapshotConnectMs);connection.setReadTimeout(network.snapshotReadMs);connection.setRequestProperty("X-WristCodex-Protocol","6");
    try{checkpoint(trace,HapticPolicy.Stage.STATUS_CONNECT);int code=connection.getResponseCode();checkpoint(trace,HapticPolicy.Stage.STATUS_HEADERS);if(code!=200)throw new IOException("待处理快照需要重新核对");StringBuilder b=new StringBuilder();try(Reader r=new InputStreamReader(connection.getInputStream(),StandardCharsets.UTF_8)){char[] buf=new char[2048];int n;while((n=r.read(buf))!=-1){if(b.length()+n>70000)throw new IOException("提醒快照过大");b.append(buf,0,n);}}checkpoint(trace,HapticPolicy.Stage.STATUS_BODY);return new JSONObject(b.toString());}finally{connection.disconnect();connection=null;}
  }
  void desktopAlerts(JSONObject state,android.content.SharedPreferences prefs,int generation,String base,String token)throws Exception{
    long attemptStarted=SystemClock.elapsedRealtime(),attemptUptime=SystemClock.uptimeMillis();
    NotificationManager nm=getSystemService(NotificationManager.class);
    if(!state.has("attention_requests")){ledgerError="电脑服务不支持请求级补提醒，请更新服务";return;}
    if(!state.optString("connection_scope").equals(notificationScope)){synchronized(alertGate){nm.cancel(41);notificationScope=state.optString("connection_scope");}}
    if(!state.optBoolean("attention_available"))return;
    long deadlineStarted=SystemClock.elapsedRealtime(),deadlineUptime=SystemClock.uptimeMillis(),deadline=deadlineStarted+20000;
    String raw=state.optString("connection_scope"),identity=state.optBoolean("connection_identity_verified")?state.optString("connection_identity",""):"";
    String scope=ConversationState.scope(base,identity,raw);
    if(!raw.equals(notificationScope)){synchronized(alertGate){nm.cancel(41);notificationScope=raw;}}
    AttentionLedger.Batch batch=new AttentionLedger.Batch(raw,state.optString("attention_revision"));
    Map<String,JSONObject> requests=new LinkedHashMap<>();JSONObject page=state;
    do{
      if(SystemClock.elapsedRealtime()>deadline)throw new IOException("提醒核对超时");
      if(generation!=reconnectVersion||!base.equals(prefs.getString("base",""))||!token.equals(prefs.getString("token","")))return;
      List<String> ids=new ArrayList<>();JSONArray array=page.optJSONArray("attention_requests");if(array==null||!page.optBoolean("attention_available"))throw new IOException("提醒快照不可用");
      for(int i=0;i<array.length();i++){JSONObject item=array.getJSONObject(i);String id=item.getString("id");ids.add(id);requests.put(id,item);}
      String cursor=page.isNull("attention_next_cursor")?"":page.optString("attention_next_cursor");batch.add(page.optString("connection_scope"),page.optString("attention_revision"),ids,cursor);
      if(batch.ready())break;page=getState(base,token,"/assistant/attention?request_cursor="+URLEncoder.encode(cursor,"UTF-8"));
    }while(!batch.ready());
    long discovered=SystemClock.elapsedRealtime(),discoveredUptime=SystemClock.uptimeMillis();
    if(ledger==null)ledger=new AttentionLedger(new File(getFilesDir(),"attention-ledger-v1.bin"));
    String[] completed;while((completed=completions.poll())!=null)ledger.completed(completed[0],completed[1]);
    ledger.receive(scope,batch.ids);List<String> fresh=ledger.eligible(scope,batch.ids);
    HapticPolicy.Trace trace=fresh.isEmpty()?null:HapticPolicy.begin(HapticPolicy.Source.DESKTOP,raw,String.join("\n",fresh),generation,attemptStarted);
    if(trace!=null){trace.checkpoint(HapticPolicy.Stage.ENTRY,attemptStarted,attemptUptime);trace.checkpoint(HapticPolicy.Stage.DEADLINE,deadlineStarted,deadlineUptime);trace.checkpoint(HapticPolicy.Stage.DISCOVERED,discovered,discoveredUptime);checkpoint(trace,HapticPolicy.Stage.DURABLE);}
    try{
    checkpoint(trace,HapticPolicy.Stage.POLICY_START);boolean allowed=deliveryAllowed(this);checkpoint(trace,HapticPolicy.Stage.POLICY_DONE);
    if(!allowed){if(trace!=null)trace.skipped(HapticPolicy.Skip.POLICY,SystemClock.elapsedRealtime(),reconnectVersion);nm.cancel(41);return;}
    String owner=ConversationState.hash(base+":"+token);
    if(validation==null||!owner.equals(validationOwner)||!validation.matches(scope,batch.revision,batch.ids)){
      validation=new AttentionLedger.Validation(scope,batch.revision,batch.ids,fresh);validationOwner=owner;
    }
    boolean complete=validation.advance(deadline,SystemClock::elapsedRealtime,id->{
      checkpoint(trace,HapticPolicy.Stage.STATUS_CALL);
      JSONObject item=requests.get(id);JSONObject status=getState(base,token,"/assistant/answer/status?id="+URLEncoder.encode(id,"UTF-8")+"&thread_id="+URLEncoder.encode(item.optString("thread_id"),"UTF-8")+"&turn_id="+URLEncoder.encode(item.optString("turn_id"),"UTF-8")+"&expected_version="+URLEncoder.encode(item.optString("version"),"UTF-8")+"&connection_scope="+URLEncoder.encode(raw,"UTF-8"),trace).optJSONObject("decision_result");
      checkpoint(trace,HapticPolicy.Stage.STATUS_RETURN);
      if(generation!=reconnectVersion)return false;
      if(status==null||!raw.equals(status.optString("connection_scope")))throw new IOException("请求状态无法核实");
      if(status.optString("request_status").equals("processed"))ledger.completed(scope,id);
      return AttentionLedger.canAct(status.optString("request_status"),status.optString("delivery_status"),true);
    },()->generation==reconnectVersion&&base.equals(prefs.getString("base",""))&&token.equals(prefs.getString("token",""))&&raw.equals(latest.optString("connection_scope")));
    checkpoint(trace,HapticPolicy.Stage.VALIDATED);
    if(!complete){
      if(trace!=null)trace.skipped(generation!=reconnectVersion?HapticPolicy.Skip.GENERATION:!raw.equals(latest.optString("connection_scope"))?HapticPolicy.Skip.SCOPE:!base.equals(prefs.getString("base",""))||!token.equals(prefs.getString("token",""))?HapticPolicy.Skip.CONFIG:HapticPolicy.Skip.RECONCILING,SystemClock.elapsedRealtime(),reconnectVersion);
      ledgerError="提醒核对分批继续，完整核实后统一提醒";return;
    }
    List<String> valid=ledger.eligible(scope,validation.valid);final Intent effectIntent;
    checkpoint(trace,HapticPolicy.Stage.GATE_WAIT);
    synchronized(alertGate){
      checkpoint(trace,HapticPolicy.Stage.GATE_ACQUIRED);
      if(generation!=reconnectVersion){if(trace!=null)trace.skipped(HapticPolicy.Skip.GENERATION,SystemClock.elapsedRealtime(),reconnectVersion);return;}
      if(!raw.equals(latest.optString("connection_scope"))){if(trace!=null)trace.skipped(HapticPolicy.Skip.SCOPE,SystemClock.elapsedRealtime(),reconnectVersion);return;}
      if(!base.equals(prefs.getString("base",""))||!token.equals(prefs.getString("token",""))){if(trace!=null)trace.skipped(HapticPolicy.Skip.CONFIG,SystemClock.elapsedRealtime(),reconnectVersion);return;}
      if(!deliveryAllowed(this)){if(trace!=null)trace.skipped(HapticPolicy.Skip.POLICY,SystemClock.elapsedRealtime(),reconnectVersion);nm.cancel(41);return;}
      if(valid.isEmpty()){if(trace!=null)trace.skipped(HapticPolicy.Skip.NO_LONGER_ELIGIBLE,SystemClock.elapsedRealtime(),reconnectVersion);validation=null;if(batch.ids.isEmpty())nm.cancel(41);return;}
      attentionRequest=requests.get(valid.get(0));attentionTarget=attentionRequest.optString("thread_id");attentionInbox=batch.ids.size()>1;
      checkpoint(trace,HapticPolicy.Stage.POST_READY);
      boolean posted=ledger.deliver(scope,valid,()->{
        if(generation!=reconnectVersion){if(trace!=null)trace.skipped(HapticPolicy.Skip.GENERATION,SystemClock.elapsedRealtime(),reconnectVersion);return false;}
        if(!raw.equals(latest.optString("connection_scope"))){if(trace!=null)trace.skipped(HapticPolicy.Skip.SCOPE,SystemClock.elapsedRealtime(),reconnectVersion);return false;}
        if(!base.equals(prefs.getString("base",""))||!token.equals(prefs.getString("token",""))){if(trace!=null)trace.skipped(HapticPolicy.Skip.CONFIG,SystemClock.elapsedRealtime(),reconnectVersion);return false;}
        if(!deliveryAllowed(this)){if(trace!=null)trace.skipped(HapticPolicy.Skip.POLICY,SystemClock.elapsedRealtime(),reconnectVersion);return false;}return true;
      },()->{
        if(trace!=null)trace.posting(SystemClock.elapsedRealtime(),reconnectVersion);
        try{nm.cancel(41);nm.notify(41,notification(true,"电脑需要你决定",batch.ids.size()+" 项已发现请求；点开重新核实"));attentionPosts++;}
        catch(RuntimeException failure){if(trace!=null)trace.postFailed(SystemClock.elapsedRealtime(),reconnectVersion);throw failure;}
        if(trace!=null)trace.posted(SystemClock.elapsedRealtime(),reconnectVersion);
      });
      if(!posted)return;validation=null;ledgerError="";
      if(trace!=null)trace.recorded(SystemClock.elapsedRealtime(),reconnectVersion);
      effectIntent=openIntent(true);
    }
    // Screen readiness is asynchronous; never hold the UI's reminder-settings lock while waiting.
    attentionEffects(effectIntent,generation,raw,trace,()->base.equals(prefs.getString("base",""))&&token.equals(prefs.getString("token","")));
    }catch(IOException failure){if(trace!=null){trace.skipped(HapticPolicy.Skip.RECONCILING,SystemClock.elapsedRealtime(),reconnectVersion);trace.recordFailed(SystemClock.elapsedRealtime(),reconnectVersion);}throw failure;}
    finally{finishAttempt(trace,this);}
  }  void listen(){
    android.content.SharedPreferences prefs=getSharedPreferences("remote",MODE_PRIVATE);
    long revision=-1;int failures=0;boolean hadContact=false;String outage=Long.toString(System.currentTimeMillis()),polledThread=null,quietState="";
    while(running){
      final String target=selectedThread;
      final int generation;
      synchronized(retryLock){generation=reconnectVersion;if(forceSnapshot){revision=-1;failures=0;forceSnapshot=false;}}
      if(!target.equals(polledThread)){revision=-1;polledThread=target;}
      try{
        BridgeNetworkConfig network=BridgeNetworkConfig.from(prefs.getAll());
        String base=network.address,token=prefs.getString("token","");
        if(base.isEmpty()||token.isEmpty())throw new IOException("尚未配置电脑");
        connection=(HttpURLConnection)new URL(base+"/assistant/events?compact=1&after="+revision+
          (target.isEmpty()?"":"&thread_id="+URLEncoder.encode(target,"UTF-8"))+"&t="+URLEncoder.encode(token,"UTF-8")).openConnection();
        connection.setConnectTimeout(network.eventsConnectMs);connection.setReadTimeout(network.eventsReadMs);
        connection.setRequestProperty("X-WristCodex-Protocol","6");
        if(connection.getResponseCode()!=200)throw new IOException("连接未成功");
        StringBuilder body=new StringBuilder();
        try(Reader r=new InputStreamReader(connection.getInputStream(),StandardCharsets.UTF_8)){
          char[] buffer=new char[2048];int n;while((n=r.read(buffer))!=-1){if(body.length()+n>70000)throw new IOException("状态过大");body.append(buffer,0,n);}
        }
        JSONObject state=new JSONObject(body.toString());long next=state.optLong("revision",-1);
        if(generation!=reconnectVersion||!target.equals(selectedThread)||!base.equals(prefs.getString("base",""))||!token.equals(prefs.getString("token","")))continue;
        boolean changed=!connected||revision!=next||!state.optString("thread_id").equals(latest.optString("thread_id"));
        latest=state;revision=next;connected=true;lastContact=SystemClock.elapsedRealtime();
        hadContact=true;failures=0;outage=Long.toString(System.currentTimeMillis());
        JSONArray pending=state.optJSONArray("pending");
        NotificationManager nm=getSystemService(NotificationManager.class);
        String nextQuiet=target+":"+state.optString("state")+":"+state.optString("message");
        final boolean updateQuiet=changed&&!nextQuiet.equals(quietState);
        final long postsBefore=attentionPosts;
        if(changed)quietState=nextQuiet;
        AttentionLedger.acceptThenReconcile(()->{
          if(changed){
            if(updateQuiet)nm.notify(40,notification(false,"腕上助手 · 已连接",state.optString("message","准备接收任务")));
            broadcast();
          }
        },()->{
        if(state.optInt("protocol_version")>=5){try{desktopAlerts(state,prefs,generation,base,token);}catch(Exception reminderFailure){ledgerError="提醒记录已满，保留待核对记录".equals(reminderFailure.getMessage())?"提醒记录已满，保留待核对记录":"提醒核对未完成，保留补提醒资格";}}
        else if(pending!=null&&pending.length()>0){
          attentionTarget=state.optString("thread_id");attentionInbox=false;
          JSONObject item=pending.getJSONObject(0);
          alert(prefs,"pending:"+item.optString("id"),"需要你决定",item.optString("summary","有操作需要你确认，点开查看选项"));
        }else if(state.optBoolean("needs_attention",state.optString("state").equals("failed"))){
          alert(prefs,state.optString("turn_id")+":"+state.optString("request_id")+":"+state.optString("state"),
            "任务需要你处理",state.optString("message"));
        }else nm.cancel(41);
        });
        completionAlerts(state,prefs,generation,base,token,attentionPosts!=postsBefore);
        // A completed response can race a conversation switch without throwing.
        // Read the newly selected conversation immediately, even at the same revision.
        if(!target.equals(selectedThread))revision=-1;
      }catch(Exception e){
        if(!running)break;
        if(generation!=reconnectVersion||!target.equals(selectedThread)){revision=-1;continue;}
        boolean wasConnected=connected;connected=false;failures++;
        completionDisconnected(generation);
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
          if(running&&generation==reconnectVersion)try{retryLock.wait(BridgeNetworkConfig.from(prefs.getAll()).retryDelay(failures));}catch(InterruptedException ignored){}
        }
      }finally{if(connection!=null){connection.disconnect();connection=null;}}
    }
  }
  @Override public void onDestroy(){running=false;connected=false;if(instance==this)instance=null;
    if(networks!=null)try{getSystemService(ConnectivityManager.class).unregisterNetworkCallback(networks);}catch(IllegalArgumentException ignored){}
    reconnect();if(listener!=null)listener.interrupt();canceller.shutdown();super.onDestroy();}
  @Override public IBinder onBind(Intent i){return null;}
}
