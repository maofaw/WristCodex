package com.n.watchremote;
import android.app.*;
import android.os.Bundle;
import android.content.Context;
/** Device-only keystore probe, with an isolated alias and disposable fake value. */
public class CloudVoiceInstrumentation extends Instrumentation {
  private String referenceDigest;
  private String completionToken;
  private String enrollmentSocket;
  private boolean enrollmentProbe;
  private boolean navigationProbe;
  private boolean hapticProbe;
  private boolean policyProbe;
  private String hapticMode;
  @Override public void onCreate(Bundle args){super.onCreate(args);completionToken=args==null?null:args.getString("completion_probe_token");referenceDigest=args==null?null:args.getString("reference_digest");enrollmentSocket=args==null?null:args.getString("enrollment_socket");enrollmentProbe=args!=null&&"true".equals(args.getString("enrollment_probe"));navigationProbe=args!=null&&"true".equals(args.getString("navigation_probe"));hapticProbe=args!=null&&"true".equals(args.getString("haptic_probe"));policyProbe=args!=null&&"true".equals(args.getString("policy_probe"));hapticMode=args==null?null:args.getString("haptic_mode");start();}
  @Override public void onStart(){
    Bundle result=new Bundle();CloudVoiceKey key=null;
    try{
      Context context=getTargetContext();
      if(completionToken!=null){CompletionProbe.run(this,context,result,completionToken);finish(Activity.RESULT_OK,result);return;}
      // Keep this branch before any credential test setup or cleanup.
      if(policyProbe){policyProbe(context,result);finish(Activity.RESULT_OK,result);return;}
      if(hapticProbe){hapticProbe(context,result);finish(Activity.RESULT_OK,result);return;}
      key=new CloudVoiceKey(context,"instrumentation_probe");
      if(navigationProbe){
        if(!context.getPackageName().equals("com.n.watchremote.qapublish"))throw new IllegalArgumentException();
        key=null;navigationProbe(context);result.putBoolean("recording_draft_navigation_passed",true);finish(Activity.RESULT_OK,result);return;
      }
      if(enrollmentSocket!=null){
        key=null;enroll(context,result);return;
      }
      if(referenceDigest!=null){
        if(!referenceDigest.matches("[0-9a-f]{64}"))throw new IllegalArgumentException();
        // Read-only diagnosis inside the target app: never export the key or its digest.
        String personal=new CloudVoiceKey(context).load();
        byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(personal.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder hex=new StringBuilder();for(byte b:digest)hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        result.putBoolean("key_present",!personal.isEmpty());
        result.putBoolean("matches_reference",hex.toString().equals(referenceDigest));
        key=null;finish(Activity.RESULT_OK,result);return;
      }
      key.save("disposable-test-value-not-a-real-service-key");
      if(!key.load().equals("disposable-test-value-not-a-real-service-key"))throw new AssertionError("Roundtrip failed");
      String saved=context.getSharedPreferences("cloud_voice_instrumentation_probe",Context.MODE_PRIVATE).getString("sealed","");
      if(saved.isEmpty()||saved.contains("disposable-test-value"))throw new AssertionError("Plaintext credential");
      key.clear();if(!key.load().isEmpty())throw new AssertionError("Clear failed");
      result.putString("result","Android Keystore encryption, reload and clear passed");finish(Activity.RESULT_OK,result);
    }catch(Throwable e){if(completionToken!=null)result.putString("probe_failure",e.getMessage());result.putString("error",e.getClass().getSimpleName());finish(Activity.RESULT_CANCELED,result);}
    finally{if(key!=null)try{key.clear();}catch(Exception ignored){}}
  }
  private void policyProbe(Context context,Bundle result)throws Exception{
    if(!"com.n.watchremote.qareliability".equals(context.getPackageName()))throw new IllegalArgumentException("QA package required");
    android.content.SharedPreferences prefs=context.getSharedPreferences("remote",Context.MODE_PRIVATE);
    boolean existed=prefs.contains("attentionPaused"),original=prefs.getBoolean("attentionPaused",false);
    AssistantActivity activity=(AssistantActivity)startActivitySync(new android.content.Intent(context,AssistantActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
    java.util.concurrent.CountDownLatch held=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1),completed=new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.atomic.AtomicBoolean syntheticCommit=new java.util.concurrent.atomic.AtomicBoolean();
    boolean[] busyTouched={false},previousBusy={false};
    Thread blocker=new Thread(()->{synchronized(AssistantMonitor.alertGate){held.countDown();try{release.await(10,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException stopped){Thread.currentThread().interrupt();}}},"qa-held-dispatch");
    try{
      blocker.start();if(!held.await(2,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Dispatch gate not held");
      long start=android.os.SystemClock.elapsedRealtime();
      runOnMainSync(()->activity.localAlertSetting("QA policy check",()->{HapticPolicy.guardedDispatch(AssistantMonitor.alertGate,()->true,()->syntheticCommit.set(true));return "QA saved";},ignored->completed.countDown()));
      runOnMainSync(()->{}); // A second main-thread message must execute while dispatch is blocked.
      long elapsed=android.os.SystemClock.elapsedRealtime()-start;
      boolean waited=!completed.await(100,java.util.concurrent.TimeUnit.MILLISECONDS);
      result.putLong("ui_submission_ms",elapsed);result.putBoolean("ui_completed_while_dispatch_blocked",elapsed<1000);
      result.putBoolean("policy_commit_waited_for_dispatch",waited);
      release.countDown();
      boolean saved=completed.await(3,java.util.concurrent.TimeUnit.SECONDS)&&syntheticCommit.get();
      result.putBoolean("synthetic_commit_after_release",saved);
      if(elapsed>=1000||!waited||!saved)throw new AssertionError("Policy update blocked UI or failed serialization");
      java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
      java.util.concurrent.CountDownLatch syncDone=new java.util.concurrent.CountDownLatch(1);
      runOnMainSync(()->{
        if(activity.busy)throw new AssertionError("Unexpected live control operation");
        previousBusy[0]=activity.busy;busyTouched[0]=true;activity.busy=true;
        activity.preferenceExchange(()->{calls.incrementAndGet();return "HTTP 200\n{}";},value->syncDone.countDown(),failure->syncDone.countDown());
      });
      boolean synchronizedOnce=syncDone.await(3,java.util.concurrent.TimeUnit.SECONDS)&&calls.get()==1;
      boolean[] stillBusy={false};runOnMainSync(()->stillBusy[0]=activity.busy);
      result.putBoolean("preference_not_dropped_while_busy",synchronizedOnce);result.putBoolean("unrelated_busy_preserved",stillBusy[0]);
      if(!synchronizedOnce||!stillBusy[0])throw new AssertionError("Preference dropped or unrelated busy cleared");
    }finally{
      release.countDown();blocker.join(3000);
      // Only synthetic in-memory commits are used; no late task can overwrite preferences.
      result.putBoolean("persistent_pause_preference_unchanged",prefs.contains("attentionPaused")==existed&&prefs.getBoolean("attentionPaused",false)==original);
      runOnMainSync(()->{if(busyTouched[0])activity.busy=previousBusy[0];activity.finish();});
    }
  }
  private void hapticProbe(Context context,Bundle result)throws Exception{
    if(!"com.n.watchremote.qareliability".equals(context.getPackageName()))throw new IllegalArgumentException("QA package required");
    if(!"notification".equals(hapticMode)&&!"direct".equals(hapticMode)&&!"notification_attrs".equals(hapticMode)&&!"compatible".equals(hapticMode))throw new IllegalArgumentException("Unknown haptic mode");
    result.putString("haptic_mode",hapticMode);
    result.putInt("app_uid",android.os.Process.myUid());
    result.putBoolean("attempted",false);
    result.putString("physical_outcome","requires human observation");
    synchronized(AssistantMonitor.alertGate){
      NotificationManager nm=context.getSystemService(NotificationManager.class);
      NotificationChannel original=nm.getNotificationChannel(AssistantMonitor.ATTENTION);
      android.content.SharedPreferences prefs=context.getSharedPreferences("remote",Context.MODE_PRIVATE);
      android.os.Vibrator vibrator=context.getSystemService(android.os.Vibrator.class);
      boolean hasVibrator=vibrator!=null&&vibrator.hasVibrator();
      boolean appEnabled=nm.areNotificationsEnabled();
      boolean high=original!=null&&original.getImportance()>=NotificationManager.IMPORTANCE_HIGH;
      boolean vibrate=original!=null&&original.shouldVibrate();
      boolean muted=prefs.getBoolean("desktopLocalMute",false),paused=prefs.getBoolean("attentionPaused",false);
      boolean desktopEnabled=AssistantMonitor.latest.optBoolean("desktop_attention_enabled",true);
      boolean dndAllows=nm.getCurrentInterruptionFilter()==NotificationManager.INTERRUPTION_FILTER_ALL;
      result.putBoolean("has_vibrator",hasVibrator);result.putBoolean("app_notifications_enabled",appEnabled);
      result.putBoolean("channel_exists",original!=null);result.putBoolean("channel_high",high);result.putBoolean("channel_vibrates",vibrate);
      result.putBoolean("local_mute",muted);result.putBoolean("paused",paused);result.putBoolean("desktop_enabled",desktopEnabled);result.putBoolean("dnd_allows",dndAllows);
      result.putBoolean("original_audio_attributes_present",original!=null&&original.getAudioAttributes()!=null);
      if(!hasVibrator||!appEnabled||!high||!vibrate||muted||paused||!desktopEnabled||!dndAllows){result.putString("submission_outcome","blocked_by_existing_gates");return;}
      android.media.AudioAttributes attrs=new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
      if("compatible".equals(hapticMode)&&!AssistantMonitor.compatibilityEnabled(context)){result.putString("submission_outcome","compatibility_not_explicitly_enabled");return;}
      if("notification".equals(hapticMode)||"compatible".equals(hapticMode)){
        result.putBoolean("compatibility_enabled",AssistantMonitor.compatibilityEnabled(context));
        result.putString("compatibility_blocked_reason",HapticPolicy.blocked(AssistantMonitor.hapticGates(context,AssistantMonitor.instance,AssistantMonitor.instance==null?0:AssistantMonitor.instance.reconnectVersion,AssistantMonitor.latest.optString("connection_scope"))));
        result.putBoolean("attempted",true);
        result.putString("submission_outcome",AssistantMonitor.testAlert(context)?"notification_submitted":"notification_not_submitted");
        android.os.SystemClock.sleep(800);
        if("compatible".equals(hapticMode)){
          boolean silentTest=false;
          for(android.service.notification.StatusBarNotification posted:nm.getActiveNotifications())if(posted.getId()==42){
            Notification n=posted.getNotification();silentTest=n.getGroup()!=null&&n.getGroupAlertBehavior()==Notification.GROUP_ALERT_SUMMARY&&(n.flags&Notification.FLAG_GROUP_SUMMARY)==0;
          }
          result.putBoolean("test_silent_child",silentTest);
          if(!silentTest)throw new AssertionError("Compatible test must be a silent child");
          AssistantMonitor owner=AssistantMonitor.instance;
          if(owner!=null){
            Notification n=owner.notification(true,"QA policy","metadata only; never posted");
            boolean silentReal=n.getGroup()!=null&&n.getGroupAlertBehavior()==Notification.GROUP_ALERT_SUMMARY&&(n.flags&Notification.FLAG_GROUP_SUMMARY)==0;
            result.putBoolean("real_silent_child",silentReal);
            if(!silentReal)throw new AssertionError("Compatible real alert must be a silent child");
          }
        }
      }else if("direct".equals(hapticMode)){
        result.putBoolean("attempted",true);
        vibrator.vibrate(android.os.VibrationEffect.createWaveform(new long[]{0,180,100,180},-1),attrs);
        result.putString("submission_outcome","vibrator_api_returned");
        android.os.SystemClock.sleep(800);
      }else{
        String channelId="qa-haptic-attrs-"+android.os.SystemClock.elapsedRealtimeNanos();
        try{
          NotificationChannel probe=new NotificationChannel(channelId,"QA 提醒属性探针",NotificationManager.IMPORTANCE_HIGH);
          probe.enableVibration(true);probe.setVibrationPattern(new long[]{0,180,100,180});probe.setSound(null,attrs);
          nm.createNotificationChannel(probe);
          NotificationChannel effective=nm.getNotificationChannel(channelId);
          result.putBoolean("probe_audio_attributes_present",effective!=null&&effective.getAudioAttributes()!=null);
          if(effective==null||!effective.shouldVibrate()||effective.getImportance()<NotificationManager.IMPORTANCE_HIGH){result.putString("submission_outcome","probe_channel_blocked");return;}
          result.putBoolean("attempted",true);
          nm.notify(44,new Notification.Builder(context,channelId).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("提醒属性测试").setContentText("需人工确认振动").setAutoCancel(true).build());
          result.putString("submission_outcome","notification_submitted");
          android.os.SystemClock.sleep(800);
        }finally{nm.cancel(44);nm.deleteNotificationChannel(channelId);}
      }
    }
  }
  private void navigationProbe(Context context)throws Exception{
    AssistantActivity activity=(AssistantActivity)startActivitySync(new android.content.Intent(context,AssistantActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
    java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
    runOnMainSync(()->{
      try{
        activity.viewed="new:probe-original";activity.draftOwner=activity.viewed;
        activity.prompt.setText("original draft");activity.cloudDraft=new VoiceDraft("original draft",activity.viewed);
        activity.cloudPending="recognized words";activity.voiceWorking=true;
        activity.current=new org.json.JSONObject().put("thread_id","new:probe-active").put("title","Other conversation");
        activity.returnToActive();
        if(!activity.viewed.equals("new:probe-original")||!activity.prompt.getText().toString().equals("original draft"))throw new AssertionError("Recording changed conversation or draft");
      }catch(Throwable error){failure.set(error);}
      finally{activity.voiceWorking=false;activity.cloudDraft=null;activity.cloudPending="";activity.finish();}
    });
    if(failure.get()!=null)throw new AssertionError(failure.get());
  }
  private void enroll(Context context,Bundle result)throws Exception{
    if(!enrollmentSocket.matches("wrist_voice_[0-9a-f]{32}"))throw new IllegalArgumentException();
    // One-use ADB tunnel: the credential is never a command argument or a disk file.
    try(android.net.LocalServerSocket server=new android.net.LocalServerSocket(enrollmentSocket)){
      Thread expiry=new Thread(()->{try{Thread.sleep(45000);server.close();}catch(Exception ignored){}});expiry.setDaemon(true);expiry.start();
      try(android.net.LocalSocket socket=server.accept()){
        socket.setSoTimeout(10000);
        java.io.DataInputStream input=new java.io.DataInputStream(socket.getInputStream());
        int size=input.readInt();if(size<16||size>1024)throw new IllegalArgumentException();
        byte[] bytes=new byte[size];input.readFully(bytes);
        String value;
        try{for(byte b:bytes)if(b<33||b>126)throw new IllegalArgumentException();value=new String(bytes,java.nio.charset.StandardCharsets.US_ASCII);}
        finally{java.util.Arrays.fill(bytes,(byte)0);}
        CloudVoiceKey destination=new CloudVoiceKey(context,enrollmentProbe?"enrollment_probe":"personal");
        try{
          destination.save(value);
          if(!value.equals(destination.load()))throw new IllegalStateException();
          if(!enrollmentProbe&&!context.getSharedPreferences("remote",Context.MODE_PRIVATE).edit().putString("voiceProvider","doubao").commit())throw new IllegalStateException();
          socket.getOutputStream().write(1);socket.getOutputStream().flush();
        }finally{if(enrollmentProbe)destination.clear();}
        result.putBoolean("saved",true);result.putBoolean("isolated_probe",enrollmentProbe);finish(Activity.RESULT_OK,result);
      }finally{expiry.interrupt();}
    }
  }
}
