package com.n.watchremote;
import android.app.*;
import android.content.*;
import android.os.*;
import org.json.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** One bounded long poll. No microphone, wake lock or periodic UI timer. */
public class AssistantMonitor extends Service {
  static final String UPDATE="com.n.watchremote.ASSISTANT_UPDATE";
  static final String ATTENTION="assistant-attention-v3",QUIET="assistant-quiet-v3";
  public static volatile JSONObject latest=new JSONObject();
  public static volatile boolean connected;
  public static volatile long lastContact;
  volatile boolean running;
  volatile HttpURLConnection connection;
  Thread listener;
  @Override public void onCreate(){super.onCreate();
    NotificationManager nm=getSystemService(NotificationManager.class);
    NotificationChannel quiet=new NotificationChannel(QUIET,"连接与安静结果",NotificationManager.IMPORTANCE_LOW);
    quiet.enableVibration(false);quiet.setSound(null,null);nm.createNotificationChannel(quiet);
    NotificationChannel alerts=new NotificationChannel(ATTENTION,"需要你处理",NotificationManager.IMPORTANCE_HIGH);
    alerts.enableVibration(true);alerts.setVibrationPattern(new long[]{0,180,100,180});alerts.setSound(null,null);
    nm.createNotificationChannel(alerts);
  }
  Notification notification(boolean attention,String title,String text){
    Intent open=new Intent(this,AssistantActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
    open.putExtra("attention",attention);
    open.putExtra("thread_id",latest.optString("thread_id"));
    PendingIntent pending=PendingIntent.getActivity(this,attention?3:2,open,
      PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    Notification.Builder b=new Notification.Builder(this,attention?ATTENTION:QUIET)
      .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(text)
      .setContentIntent(pending).setOnlyAlertOnce(true).setAutoCancel(false);
    if(attention)b.setCategory(Notification.CATEGORY_REMINDER).setFullScreenIntent(pending,true);
    return b.build();
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
      try{startActivity(new Intent(this,AssistantActivity.class).putExtra("attention",true).putExtra("thread_id",latest.optString("thread_id"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));
      }catch(RuntimeException ignored){}
    }catch(SecurityException ignored){} // The notification remains if the OEM blocks waking.
  }
  void listen(){
    android.content.SharedPreferences prefs=getSharedPreferences("remote",MODE_PRIVATE);
    long revision=-1;int failures=0;boolean hadContact=false;String outage=Long.toString(System.currentTimeMillis());
    while(running){
      try{
        String base=prefs.getString("base",""),token=prefs.getString("token","");
        if(base.isEmpty()||token.isEmpty())throw new IOException("尚未配置电脑");
        connection=(HttpURLConnection)new URL(base+"/assistant/events?compact=1&after="+revision+"&t="+URLEncoder.encode(token,"UTF-8")).openConnection();
        connection.setConnectTimeout(5000);connection.setReadTimeout(28000);
        if(connection.getResponseCode()!=200)throw new IOException("连接未成功");
        StringBuilder body=new StringBuilder();
        try(Reader r=new InputStreamReader(connection.getInputStream(),StandardCharsets.UTF_8)){
          char[] buffer=new char[2048];int n;while((n=r.read(buffer))!=-1){if(body.length()+n>70000)throw new IOException("状态过大");body.append(buffer,0,n);}
        }
        JSONObject state=new JSONObject(body.toString());long next=state.optLong("revision",-1);
        boolean changed=!connected||revision!=next;
        latest=state;revision=next;connected=true;lastContact=SystemClock.elapsedRealtime();
        hadContact=true;failures=0;outage=Long.toString(System.currentTimeMillis());
        JSONArray pending=state.optJSONArray("pending");
        NotificationManager nm=getSystemService(NotificationManager.class);
        if(pending!=null&&pending.length()>0){
          JSONObject item=pending.getJSONObject(0);
          alert(prefs,"pending:"+item.optString("id"),"需要你决定",item.optString("summary","有操作需要你确认，点开查看选项"));
        }else if(state.optBoolean("needs_attention",state.optString("state").equals("failed"))){
          alert(prefs,state.optString("turn_id")+":"+state.optString("request_id")+":"+state.optString("state"),
            "任务需要你处理",state.optString("message"));
        }else nm.cancel(41);
        if(changed){
          nm.notify(40,notification(false,"腕上助手 · 已连接",state.optString("message","准备接收任务")));
          broadcast();
        }
      }catch(Exception e){
        if(!running)break;
        boolean wasConnected=connected;connected=false;failures++;
        if(wasConnected||failures==1){
          getSystemService(NotificationManager.class).notify(40,notification(false,"电脑未连接","未发送的文字会保留，连接恢复后再试"));broadcast();
        }
        String status=latest.optString("state");
        if(hadContact&&failures>=3&&(status.equals("running")||status.equals("waiting")||status.equals("starting")))
          alert(prefs,"outage:"+outage,"暂时联系不上电脑","任务状态未知，请检查电脑服务和网络；不会重复发送任务");
        try{Thread.sleep(15000);}catch(InterruptedException ignored){}
      }finally{if(connection!=null){connection.disconnect();connection=null;}}
    }
  }
  @Override public void onDestroy(){running=false;connected=false;if(connection!=null)connection.disconnect();if(listener!=null)listener.interrupt();super.onDestroy();}
  @Override public IBinder onBind(Intent i){return null;}
}
