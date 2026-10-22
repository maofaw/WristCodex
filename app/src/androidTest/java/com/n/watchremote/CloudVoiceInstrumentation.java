package com.n.watchremote;
import android.app.*;
import android.os.Bundle;
import android.content.Context;
/** Device-only keystore probe, with an isolated alias and disposable fake value. */
public class CloudVoiceInstrumentation extends Instrumentation {
  private String referenceDigest;
  private String enrollmentSocket;
  private boolean enrollmentProbe;
  private boolean navigationProbe;
  @Override public void onCreate(Bundle args){super.onCreate(args);referenceDigest=args==null?null:args.getString("reference_digest");enrollmentSocket=args==null?null:args.getString("enrollment_socket");enrollmentProbe=args!=null&&"true".equals(args.getString("enrollment_probe"));navigationProbe=args!=null&&"true".equals(args.getString("navigation_probe"));start();}
  @Override public void onStart(){
    Bundle result=new Bundle();CloudVoiceKey key=null;
    try{
      Context context=getTargetContext();key=new CloudVoiceKey(context,"instrumentation_probe");
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
    }catch(Throwable e){result.putString("error",e.getClass().getSimpleName());finish(Activity.RESULT_CANCELED,result);}
    finally{if(key!=null)try{key.clear();}catch(Exception ignored){}}
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
