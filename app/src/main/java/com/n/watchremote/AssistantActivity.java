package com.n.watchremote;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.text.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class AssistantActivity extends MainActivity {
  EditText prompt;
  TextView reply;
  Button mic;
  LinearLayout pendingPanel;
  String pendingKey="";
  boolean active;
  VoiceCapture capture=new VoiceCapture();
  BroadcastReceiver receiver=new BroadcastReceiver(){public void onReceive(Context c,Intent i){render(AssistantMonitor.latest);}};
  @Override public void onCreate(Bundle state){
    super.onCreate(state);active=true;
    registerReceiver(receiver,new IntentFilter("com.n.watchremote.ASSISTANT_UPDATE"));
    aiPage();
  }
  @Override void aiPage(){
    page("Codex 助手");atHome=false;
    prompt=field("说一句，或输入文字",get("aiDraft"),false,true);
    prompt.setMinLines(2);prompt.setHint("继续 / 帮我修改一下…");
    prompt.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
    prompt.addTextChangedListener(new TextWatcher(){
      public void beforeTextChanged(CharSequence s,int start,int count,int after){}
      public void onTextChanged(CharSequence s,int start,int before,int count){prefs.edit().putString("aiDraft",s.toString()).apply();}
      public void afterTextChanged(Editable e){}
    });
    mic=new Button(this);mic.setText("麦克风 · 点一下说话");panel.addView(mic);mic.setOnClickListener(v->{if(!busy)beginSpeech();});
    button("发送任务",this::send);
    status=text("正在连接…",13);
    pendingPanel=new LinearLayout(this);pendingPanel.setOrientation(LinearLayout.VERTICAL);panel.addView(pendingPanel);
    reply=text("结果会自动显示",15);reply.setTextIsSelectable(true);
    button("刷新状态",()->task(()->request(endpoint("/assistant/state")+"?t="+URLEncoder.encode(get("token"),"UTF-8"),"GET","",""),this::parse));
    button("停止任务",()->confirm("停止任务？已完成的修改不会撤销。",()->task(()->request(endpoint("/assistant/stop"),"POST","{}",get("token")),this::parse)));
    button("开始新对话",()->confirm("下一条消息将开始新对话，不沿用当前上下文。",()->{
      prefs.edit().putBoolean("newConversation",true).apply();status.setText("下一条消息开始新对话");
    }));
    button("关闭主动提醒",()->{stopService(new Intent(this,AssistantMonitor.class));status.setText("已关闭提醒；重新进入助手页可开启");});
    button("返回",this::finish);
    startForegroundService(new Intent(this,AssistantMonitor.class));
    render(AssistantMonitor.latest);
  }
  void send(){
    if(capture.recording){status.setText("请先结束录音并检查识别文字");return;}
    String value=prompt.getText().toString().trim();
    if(value.isEmpty()||value.length()>4000){status.setText("请输入任务，或先说话");return;}
    String id=get("aiRequestId");
    if(!value.equals(get("aiPendingPrompt"))||id.isEmpty())id=UUID.randomUUID().toString();
    prefs.edit().putString("aiPendingPrompt",value).putString("aiRequestId",id).apply();final String requestId=id;
    task(()->{JSONObject body=new JSONObject();body.put("prompt",value);body.put("request_id",requestId);
      body.put("new",prefs.getBoolean("newConversation",false));
      return request(endpoint("/assistant/send"),"POST",body.toString(),get("token"));
    },message->{if(message.startsWith("HTTP 202\n")){
      if(prompt.getText().toString().trim().equals(value))prompt.setText("");
      prefs.edit().remove("aiRequestId").remove("aiPendingPrompt").putBoolean("newConversation",false).apply();
      prompt.clearFocus();((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(prompt.getWindowToken(),0);parse(message);
    }});
  }
  void parse(String response){try{render(new JSONObject(response.substring(response.indexOf('\n')+1)));}catch(Exception ignored){}}
  void render(JSONObject state){
    if(!active||state.length()==0||reply==null)return;
    status.setText(state.optString("message","准备就绪"));
    String value=state.optString("reply");reply.setText(value.isEmpty()?"等待回答…":value.substring(0,Math.min(value.length(),6000)));
    JSONArray pending=state.optJSONArray("pending");String key=pending==null?"":pending.toString();
    if(key.equals(pendingKey))return;pendingKey=key;pendingPanel.removeAllViews();
    if(pending==null||pending.length()==0)return;
    try{
      JSONObject item=pending.getJSONObject(0);String id=item.getString("id");label(item.optString("title"));
      if(item.optString("kind").equals("approval")){
        label(item.optString("detail"));choice("仅本次允许",()->answer(id,"accept",null));choice("拒绝",()->answer(id,"decline",null));
      }else{
        JSONArray questions=item.getJSONArray("questions");Map<String,EditText> values=new LinkedHashMap<>();
        for(int i=0;i<questions.length();i++){
          JSONObject q=questions.getJSONObject(i);label(q.optString("question"));
          EditText input=new EditText(this);input.setTextColor(Color.WHITE);input.setTextSize(14);pendingPanel.addView(input);values.put(q.getString("id"),input);
          JSONArray options=q.optJSONArray("options");if(options!=null)for(int n=0;n<options.length();n++){
            String option=options.getJSONObject(n).getString("label");choice(option,()->input.setText(option));
          }
        }
        choice("提交回答",()->{try{JSONObject answers=new JSONObject();for(String name:values.keySet())answers.put(name,values.get(name).getText().toString());answer(id,null,answers);}catch(Exception e){status.setText("请检查答案");}});
      }
    }catch(Exception e){status.setText("此确认暂不支持，请在电脑处理");}
  }
  void label(String value){TextView t=new TextView(this);t.setText(value);t.setTextColor(Color.WHITE);t.setTextSize(14);pendingPanel.addView(t);}
  void choice(String label,Runnable action){Button b=new Button(this);b.setText(label);pendingPanel.addView(b);b.setOnClickListener(v->{if(!busy)action.run();});}
  void answer(String id,String decision,JSONObject answers){task(()->{
    JSONObject payload=new JSONObject();payload.put("id",id);if(decision!=null)payload.put("decision",decision);if(answers!=null)payload.put("answers",answers);
    return request(endpoint("/assistant/answer"),"POST",payload.toString(),get("token"));
  },this::parse);}
  void beginSpeech(){
    if(capture.recording){capture.stop();mic.setText("正在识别…");return;}
    if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},7);return;}
    mic.setText("正在听 · 点此结束");status.setText("说完点结束，最长 30 秒");
    capture.start(audio->runOnUiThread(()->{
      if(!active)return;mic.setText("麦克风 · 点一下说话");
      task(()->upload(audio),response->{if(response.startsWith("HTTP 200\n"))try{
        prompt.setText(new JSONObject(response.substring(9)).getString("text"));status.setText("已识别，请检查文字后发送");
      }catch(Exception e){status.setText("识别结果无效");}});
    }),error->runOnUiThread(()->{if(active){mic.setText("麦克风 · 点一下说话");status.setText("录音失败："+error);}}));
  }
  String upload(byte[] audio)throws Exception{
    HttpURLConnection c=(HttpURLConnection)new URL(endpoint("/assistant/voice")).openConnection();
    c.setRequestMethod("POST");c.setConnectTimeout(5000);c.setReadTimeout(120000);c.setDoOutput(true);
    c.setRequestProperty("Authorization","Bearer "+get("token"));c.setRequestProperty("Content-Type","audio/wav");c.setFixedLengthStreamingMode(audio.length);
    try{try(OutputStream out=c.getOutputStream()){out.write(audio);}int code=c.getResponseCode();InputStream in=code<400?c.getInputStream():c.getErrorStream();
      StringBuilder body=new StringBuilder();if(in!=null)try(Reader r=new InputStreamReader(in,StandardCharsets.UTF_8)){char[] b=new char[2048];int n;while((n=r.read(b))!=-1&&body.length()<12000)body.append(b,0,n);}return "HTTP "+code+"\n"+body;
    }finally{c.disconnect();}
  }
  @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){super.onRequestPermissionsResult(code,permissions,results);if(code==7&&results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED&&active)beginSpeech();}
  @Override protected void onResume(){super.onResume();active=true;}
  @Override protected void onPause(){active=false;capture.stop();super.onPause();}
  @Override public void onBackPressed(){if(!busy)finish();}
  @Override protected void onDestroy(){active=false;capture.stop();unregisterReceiver(receiver);super.onDestroy();}
}
