package com.n.watchremote;
import android.app.*;
import android.content.*;
import android.os.*;
import org.json.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.io.*;

/** Runs only against an owned synthetic fixture. Never submits model or PC tasks. */
final class CompletionProbe {
 static final String BASE="http://127.0.0.1:18907";
 static void run(Instrumentation runner,Context context,Bundle result,String token)throws Exception{
  if(!context.getPackageName().equals("com.n.watchremote.qacompletion"))throw new IllegalArgumentException();
  SharedPreferences prefs=context.getSharedPreferences("remote",Context.MODE_PRIVATE);Map<String,?> before=new HashMap<>(prefs.getAll());
  context.stopService(new Intent(context,AssistantMonitor.class));
  prefs.edit().putString("base",BASE).putString("token",token).putString("viewedConversation","complete-qa-1").putString("viewedTitle","完成提醒验收")
   .putBoolean("watchCompatibleVibration",true).putBoolean("completionVibrationEnabled",true).putBoolean("desktopLocalMute",true).putBoolean("attentionPaused",false).commit();
  AssistantActivity activity=(AssistantActivity)runner.startActivitySync(new Intent(context,AssistantActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  try{
   waitFor(()->batch().optBoolean("available")&&prefs.contains("completionCursor"),"first completion baseline");SystemClock.sleep(1700);
   long cursorStart=batch().optLong("cursor");int baseline=waveforms(runner,context);result.putInt("baseline_hardware_records",baseline);
   control(token,"running");waitFor(()->AssistantMonitor.latest.optString("state").equals("running"),"running native turn");
   control(token,"completed");waitFor(()->batch().optLong("cursor")==cursorStart+1&&prefs.getLong("completionCursor",-1)==cursorStart+1,"completion delivered/checkpointed");
   waitFor(()->waveforms(runner,context)==baseline+1,"one short hardware vibration");
   result.putBoolean("one_completion_one_vibration",true);result.putBoolean("confirmation_muted_completion_works",true);
   SystemClock.sleep(1400);result.putBoolean("duplicate_snapshot_no_replay",waveforms(runner,context)==baseline+1);
   activity.setCompletionVibration(false);
   control(token,"running");waitFor(()->AssistantMonitor.latest.optString("state").equals("running"),"disabled running");
   control(token,"completed");waitFor(()->batch().optLong("cursor")==cursorStart+2&&prefs.getLong("completionCursor",-1)==cursorStart+2,"disabled cursor advanced");
   result.putBoolean("disabled_no_vibration",waveforms(runner,context)==baseline+1);
   activity.setCompletionVibration(true);SystemClock.sleep(1600);
   result.putBoolean("reenabled_no_historical_replay",waveforms(runner,context)==baseline+1);
   activity.pauseActiveAlerts();SystemClock.sleep(900);
   control(token,"running");waitFor(()->AssistantMonitor.latest.optString("state").equals("running"),"paused running");
   control(token,"completed");waitFor(()->batch().optLong("cursor")==cursorStart+3&&prefs.getLong("completionCursor",-1)==cursorStart+3,"paused cursor advanced");
   result.putBoolean("pause_blocks_vibration",waveforms(runner,context)==baseline+1);
   activity.resumeActiveAlerts();SystemClock.sleep(1600);
   result.putBoolean("resume_no_historical_replay",waveforms(runner,context)==baseline+1);
   control(token,"running");waitFor(()->AssistantMonitor.latest.optString("state").equals("running"),"resumed running");
   control(token,"completed");waitFor(()->batch().optLong("cursor")==cursorStart+4&&prefs.getLong("completionCursor",-1)==cursorStart+4,"first fresh completion after resume");
   waitFor(()->waveforms(runner,context)==baseline+2,"new completion after resume vibrates");
   result.putBoolean("first_new_after_resume_vibrates",true);
   result.putString("scope","owned synthetic service via USB reverse; actual Android completion path and hardware log, no real desktop task");
  }finally{
   runner.runOnMainSync(activity::finish);context.stopService(new Intent(context,AssistantMonitor.class));
   SharedPreferences.Editor edit=prefs.edit().clear();for(Map.Entry<String,?> e:before.entrySet()){
    Object v=e.getValue();String k=e.getKey();if(v instanceof String)edit.putString(k,(String)v);else if(v instanceof Boolean)edit.putBoolean(k,(Boolean)v);else if(v instanceof Integer)edit.putInt(k,(Integer)v);else if(v instanceof Long)edit.putLong(k,(Long)v);else if(v instanceof Float)edit.putFloat(k,(Float)v);
   }edit.commit();
  }
  for(String key:result.keySet())if(result.get(key) instanceof Boolean&&!result.getBoolean(key))throw new AssertionError(key);
 }
 static JSONObject batch(){JSONObject value=AssistantMonitor.latest.optJSONObject("completion_notifications");return value==null?new JSONObject():value;}
 static void control(String token,String phase)throws Exception{
  String response=MainActivity.request(BASE+"/qa/completion","POST",new JSONObject().put("phase",phase).toString(),token);
  if(!response.startsWith("HTTP 200"))throw new AssertionError("Owned fixture control unavailable");
 }
 static int waveforms(Instrumentation runner,Context context){
  try(ParcelFileDescriptor p=runner.getUiAutomation().executeShellCommand("dumpsys vibrator");InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(p)){
   ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))>0)out.write(b,0,n);
   int count=0;for(String line:out.toString("UTF-8").split("\n"))if(line.contains("opPkg: "+context.getPackageName()+",")&&line.contains("effect:"))count++;return count;
  }catch(Exception e){return -1;}
 }
 static void waitFor(BooleanSupplier condition,String label)throws Exception{
  long end=SystemClock.elapsedRealtime()+16000;do{if(condition.getAsBoolean())return;SystemClock.sleep(90);}while(SystemClock.elapsedRealtime()<end);throw new AssertionError("Timed out: "+label);
 }
}
