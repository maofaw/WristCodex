package com.n.watchremote;
import android.app.*;
import android.content.*;
import android.os.*;
import org.json.JSONObject;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** One long-poll connection, no microphone and no wake lock in the background. */
public class AssistantMonitor extends Service {
  public static volatile JSONObject latest=new JSONObject();
  volatile boolean running;
  volatile HttpURLConnection connection;
  @Override public void onCreate(){super.onCreate();
    NotificationManager nm=getSystemService(NotificationManager.class);
    nm.createNotificationChannel(new NotificationChannel("monitor","助手连接",NotificationManager.IMPORTANCE_LOW));
    NotificationChannel alerts=new NotificationChannel("assistant-alerts","任务完成与确认",NotificationManager.IMPORTANCE_HIGH);
    alerts.enableVibration(true);alerts.setVibrationPattern(new long[]{0,180,100,180});nm.createNotificationChannel(alerts);
  }
  Notification notification(String channel,String title,String text){
    Intent open=new Intent(this,AssistantActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
    PendingIntent pending=PendingIntent.getActivity(this,2,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    return new Notification.Builder(this,channel).setSmallIcon(android.R.drawable.ic_dialog_info)
      .setContentTitle(title).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
      .setContentIntent(pending).setAutoCancel(!channel.equals("monitor")).build();
  }
  @Override public int onStartCommand(Intent intent,int flags,int startId){
    startForeground(40,notification("monitor","Codex 助手","正在监听任务结果，可在应用内关闭"));
    if(!running){running=true;new Thread(this::listen,"assistant-events").start();}return START_NOT_STICKY;
  }
  void listen(){
    android.content.SharedPreferences prefs=getSharedPreferences("remote",MODE_PRIVATE);
    long revision=-1;
    while(running){
      try{
        String base=prefs.getString("base",""),token=prefs.getString("token","");
        if(base.isEmpty()||token.isEmpty())throw new IOException("尚未配置电脑");
        connection=(HttpURLConnection)new URL(base+"/assistant/events?after="+revision+"&t="+URLEncoder.encode(token,"UTF-8")).openConnection();
        connection.setConnectTimeout(5000);connection.setReadTimeout(28000);
        if(connection.getResponseCode()!=200)throw new IOException("连接未成功");
        StringBuilder body=new StringBuilder();
        try(Reader r=new InputStreamReader(connection.getInputStream(),StandardCharsets.UTF_8)){
          char[] buffer=new char[4096];int n;while((n=r.read(buffer))!=-1&&body.length()<70000)body.append(buffer,0,n);
        }
        JSONObject state=new JSONObject(body.toString());latest=state;revision=state.optLong("revision",-1);
        prefs.edit().remove("monitorError").apply();
        String status=state.optString("state"),key="";
        if(status.equals("waiting")&&state.optJSONArray("pending")!=null&&state.optJSONArray("pending").length()>0)
          key="pending:"+state.optJSONArray("pending").getJSONObject(0).optString("id");
        else if(status.equals("completed")||status.equals("failed")||status.equals("interrupted"))
          key=state.optString("turn_id")+":"+state.optString("request_id")+":"+status;
        if(!key.isEmpty()&&!key.equals(prefs.getString("lastAlert",""))){
          prefs.edit().putString("lastAlert",key).apply();
          String detail=status.equals("waiting")?"点击查看选项并回答":state.optString("reply",state.optString("message"));
          getSystemService(NotificationManager.class).notify(41,notification("assistant-alerts",
            state.optString("message","Codex 消息"),detail.substring(0,Math.min(detail.length(),300))));
        }
        sendBroadcast(new Intent("com.n.watchremote.ASSISTANT_UPDATE").setPackage(getPackageName()));
      }catch(Exception e){
        prefs.edit().putString("monitorError","电脑连接中断，正在重试").apply();
        try{Thread.sleep(15000);}catch(InterruptedException ignored){}
      }finally{if(connection!=null)connection.disconnect();}
    }
  }
  @Override public void onDestroy(){running=false;if(connection!=null)connection.disconnect();super.onDestroy();}
  @Override public IBinder onBind(Intent i){return null;}
}
