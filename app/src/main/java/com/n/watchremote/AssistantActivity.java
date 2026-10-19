package com.n.watchremote;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.content.res.ColorStateList;
import android.os.*;
import android.text.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

/** A fixed native view tree; network/JSON parsing happens on the single worker. */
public class AssistantActivity extends MainActivity {
  EditText prompt;
  TextView connectionLabel,phase,taskLabel,reply;
  Button mic,sendButton,contextButton,resultButton;
  LinearLayout pendingPanel;
  ScrollView scroll;
  JSONObject current=new JSONObject();
  String pendingKey="",visibleTask="",visibleSummary="",visiblePhase="";
  boolean active,voiceWorking,discardCapture,continuePrevious;
  Handler ui=new Handler(Looper.getMainLooper());
  Runnable saveDraft=()->prefs.edit().putString("aiDraft",prompt.getText().toString()).apply();
  VoiceCapture capture=new VoiceCapture();
  BroadcastReceiver receiver=new BroadcastReceiver(){public void onReceive(Context c,Intent i){render(AssistantMonitor.latest);}};
  @Override void initialPage(){} // Avoid building the old home screen on every launch.
  @Override public void onCreate(Bundle state){
    super.onCreate(state);active=true;
    registerReceiver(receiver,new IntentFilter(AssistantMonitor.UPDATE));
    build();handleAttention(getIntent());
    startForegroundService(new Intent(this,AssistantMonitor.class));
  }
  Button action(LinearLayout parent,String title,boolean primary,Runnable run){
    Button b=new Button(this);b.setText(title);b.setTextSize(16);b.setAllCaps(false);
    b.setTextColor(Color.WHITE);b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));
    b.setPadding(dp(8),dp(8),dp(8),dp(8));
    b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x334D9FFF),
      surface(primary?0xFF2868DB:0xFF1B2431,14),null));
    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(4),0,dp(4));
    parent.addView(b,lp);b.setOnClickListener(v->run.run());return b;
  }
  void build(){
    page("腕上助手");atHome=false;scroll=(ScrollView)panel.getParent();
    panel.setFocusableInTouchMode(true);panel.requestFocus();
    connectionLabel=text("正在连接电脑…",12);
    phase=text("准备接收任务",18);phase.setTypeface(null,Typeface.BOLD);phase.setMaxLines(3);phase.setEllipsize(TextUtils.TruncateAt.END);
    taskLabel=text("",12);taskLabel.setMaxLines(2);taskLabel.setEllipsize(TextUtils.TruncateAt.END);taskLabel.setVisibility(View.GONE);
    pendingPanel=new LinearLayout(this);pendingPanel.setOrientation(LinearLayout.VERTICAL);panel.addView(pendingPanel);
    mic=action(panel,"点一下说话",true,this::beginSpeech);mic.setTextSize(20);mic.setMinimumHeight(dp(64));
    prompt=field("任务文字",get("aiDraft"),false,true);prompt.setHint("说话后检查文字，也可以打字");
    prompt.setMinLines(2);prompt.setMaxLines(4);
    prompt.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
    prompt.addTextChangedListener(new TextWatcher(){
      public void beforeTextChanged(CharSequence s,int start,int count,int after){}
      public void onTextChanged(CharSequence s,int start,int before,int count){ui.removeCallbacks(saveDraft);ui.postDelayed(saveDraft,500);}
      public void afterTextChanged(Editable e){}
    });
    sendButton=action(panel,"发送新任务",true,this::send);
    contextButton=action(panel,"接着上次聊",false,()->{
      if(isRunning()){hint("新任务请等当前结束；这一版还不排队");return;}
      if(current.optString("thread_id").isEmpty()){hint("还没有可继续的对话");return;}
      continuePrevious=!continuePrevious;prefs.edit().remove("pendingPayload").apply();render(current);
    });
    status=text("识别文字后检查，再点发送",12);
    reply=text("结果会安静显示在这里",15);
    resultButton=action(panel,"查看完整结果",false,this::fullResult);resultButton.setVisibility(View.GONE);
    action(panel,"更多操作",false,this::menu);
    render(AssistantMonitor.latest);
  }
  boolean isRunning(){String s=current.optString("state");return s.equals("running")||s.equals("starting")||s.equals("waiting");}
  void hint(String s){if(status!=null&&!status.getText().toString().equals(s))status.setText(s);}
  void render(JSONObject state){
    if(!active||phase==null)return;
    if(state.length()>0&&state.optLong("revision",-1)>=current.optLong("revision",-1))current=state;
    String conn=AssistantMonitor.fresh()?"电脑已连接":"电脑未连接 · 未发送文字会保留";
    if(!connectionLabel.getText().toString().equals(conn))connectionLabel.setText(conn);
    String stage=current.optString("message","准备接收任务");
    if(!stage.equals(visiblePhase)){visiblePhase=stage;phase.setText(stage);}
    String task=current.optString("task");
    if(!task.equals(visibleTask)){visibleTask=task;taskLabel.setText(task);taskLabel.setVisibility(task.isEmpty()?View.GONE:View.VISIBLE);}
    String summary=current.optString("summary");
    if(summary.isEmpty()&&!isRunning())summary=current.optString("reply");
    if(summary.length()>180)summary=summary.substring(0,180);
    if(!summary.equals(visibleSummary)){
      visibleSummary=summary;reply.setText(summary.isEmpty()?(isRunning()?"电脑正在处理，可以先离开。":"结果会安静显示在这里"):summary);
    }
    resultButton.setVisibility(summary.isEmpty()?View.GONE:View.VISIBLE);
    String sendTitle=isRunning()?"补充当前任务":continuePrevious?"继续上次对话":"发送新任务";
    if(!sendButton.getText().toString().equals(sendTitle))sendButton.setText(sendTitle);
    String contextTitle=isRunning()?"新任务 · 请等当前结束":continuePrevious?"改为独立新任务":"接着上次聊";
    if(!contextButton.getText().toString().equals(contextTitle))contextButton.setText(contextTitle);
    JSONArray pending=current.optJSONArray("pending");JSONObject item=pending==null?null:pending.optJSONObject(0);
    String key=item==null?"":item.optString("id");
    if(!key.equals(pendingKey)){pendingKey=key;pendingPanel.removeAllViews();if(item!=null)showPending(item);}
  }
  void showPending(JSONObject item){
    try{
      TextView title=new TextView(this);title.setText("需要你决定");title.setTextColor(0xFFFFD38B);title.setTextSize(18);
      title.setPadding(0,dp(8),0,dp(8));pendingPanel.addView(title);
      final String id=item.getString("id");
      if(item.optString("kind").equals("approval")){
        label(item.optString("summary","电脑请求执行一项操作，请查看详情后决定"));
        String detail=item.optString("detail");
        action(pendingPanel,"展开操作详情",false,()->showPages("操作详情",detail));
        action(pendingPanel,"仅本次允许",true,()->answer(id,"accept",null));
        action(pendingPanel,"拒绝",false,()->answer(id,"decline",null));
      }else{
        JSONArray questions=item.getJSONArray("questions");Map<String,EditText> values=new LinkedHashMap<>();
        for(int i=0;i<questions.length();i++){
          JSONObject q=questions.getJSONObject(i);label(q.optString("question"));
          EditText input=new EditText(this);input.setTextColor(Color.WHITE);input.setTextSize(16);
          input.setHint("点选项或输入回答");input.setHintTextColor(0xFFACB8C9);pendingPanel.addView(input);
          values.put(q.getString("id"),input);
          JSONArray options=q.optJSONArray("options");
          if(options!=null)for(int n=0;n<options.length();n++){
            JSONObject option=options.getJSONObject(n);String value=option.getString("label");
            action(pendingPanel,value,false,()->{input.setText(value);input.clearFocus();});
          }
        }
        action(pendingPanel,"提交回答",true,()->{
          try{JSONObject answers=new JSONObject();for(String name:values.keySet()){
            String value=values.get(name).getText().toString().trim();if(value.isEmpty()){hint("请先回答全部问题");return;}answers.put(name,value);
          }answer(id,null,answers);}catch(Exception e){hint("请检查答案");}
        });
      }
    }catch(Exception e){label("这种确认请在电脑处理，手表不会自动批准");}
  }
  void label(String value){
    TextView t=new TextView(this);t.setText(value);t.setTextColor(Color.WHITE);t.setTextSize(16);
    t.setPadding(0,dp(4),0,dp(8));pendingPanel.addView(t);
  }
  // Parse away from the UI thread, and never block back, scrolling or text entry.
  void exchange(Work work,Consumer<JSONObject> done){
    if(busy){hint("正在提交，请稍候");return;}busy=true;sendButton.setEnabled(false);sendButton.setAlpha(0.5f);
    worker.execute(()->{
      JSONObject value=null;String error=null;
      try{
        String raw=work.run();int end=raw.indexOf('\n');
        if(!(raw.startsWith("HTTP 200\n")||raw.startsWith("HTTP 202\n")))throw new IOException(end<0?raw:raw.substring(end+1));
        value=new JSONObject(raw.substring(end+1));
      }catch(Exception e){error=e.getMessage();if(error==null||error.isEmpty())error="连接失败";}
      final JSONObject result=value;final String failure=error;
      runOnUiThread(()->{
        busy=false;if(isDestroyed())return;sendButton.setEnabled(true);sendButton.setAlpha(1f);
        if(failure!=null){hint("未确认送达："+failure.substring(0,Math.min(failure.length(),180)));return;}
        if(!active)return;
        done.accept(result);
      });
    });
  }
  String stateUrl()throws Exception{return endpoint("/assistant/state")+"?compact=1&t="+URLEncoder.encode(get("token"),"UTF-8");}
  void refresh(){
    if(voiceWorking){hint("请先结束录音并等待识别完成");return;}
    hint("正在检查连接…");
    exchange(()->request(stateUrl(),"GET","",""),state->{
      AssistantMonitor.connected=true;AssistantMonitor.lastContact=SystemClock.elapsedRealtime();
      render(state);hint("连接已恢复，可以输入或说话");
    });
  }
  void send(){
    if(voiceWorking){hint("请先结束录音并检查识别文字");return;}
    if(!AssistantMonitor.fresh()){hint("电脑未连接 · 任务未发送");refresh();return;}
    String value=prompt.getText().toString().trim();
    if(value.isEmpty()||value.length()>4000){hint("请输入 1–4000 字符，或点麦克风说话");return;}
    if(!pendingKey.isEmpty()){hint("请先处理上方确认选项，再补充任务");scroll.smoothScrollTo(0,0);return;}
    String proposedKind=isRunning()?"steer":continuePrevious?"continue":"new";
    try{
      JSONObject payload=null;
      try{JSONObject saved=new JSONObject(get("pendingPayload"));
        // A lost send response must never turn a retry into a new supplement.
        if(value.equals(saved.optString("prompt")))payload=saved;
      }catch(Exception ignored){}
      if(payload==null){
        if(proposedKind.equals("steer")&&!current.optString("state").equals("running")){hint("正在连接，稍后再补充");return;}
        payload=new JSONObject();payload.put("prompt",value);payload.put("request_id",UUID.randomUUID().toString());
        payload.put("new",proposedKind.equals("new"));payload.put("kind",proposedKind);
        payload.put("thread_id",current.optString("thread_id"));payload.put("turn_id",current.optString("turn_id"));
      }
      final String kind=payload.getString("kind");
      final String body=payload.toString(),path=kind.equals("steer")?"/assistant/steer":"/assistant/send";
      prefs.edit().putString("pendingPayload",body).apply();hint("正在提交…");
      exchange(()->request(endpoint(path),"POST",body,get("token")),state->{
        if(prompt.getText().toString().trim().equals(value))prompt.setText("");
        prefs.edit().remove("pendingPayload").apply();continuePrevious=false;
        prompt.clearFocus();((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(prompt.getWindowToken(),0);
        render(state);hint(kind.equals("steer")?"补充已送达当前任务":"电脑已接收任务");
      });
    }catch(Exception e){hint("任务未发送，请检查连接配置");}
  }
  void answer(String id,String decision,JSONObject answers){
    if(voiceWorking){hint("请先结束录音并等待识别完成");return;}
    try{JSONObject body=new JSONObject();body.put("id",id);if(decision!=null)body.put("decision",decision);if(answers!=null)body.put("answers",answers);
      exchange(()->request(endpoint("/assistant/answer"),"POST",body.toString(),get("token")),state->{render(state);hint("已收到，继续执行");});
    }catch(Exception e){hint("回答未发送，请重试");}
  }
  void menu(){
    if(voiceWorking){hint("请先结束录音并等待识别完成，返回键仍可退出");return;}
    new AlertDialog.Builder(this).setTitle("更多操作").setItems(new String[]{"检查电脑连接","停止当前任务","电脑控制与设置","关闭主动提醒"},(d,n)->{
      if(n==0)refresh();
      if(n==1)confirm("停止当前任务？已经完成的操作不会撤销。",()->exchange(
        ()->request(endpoint("/assistant/stop"),"POST","{}",get("token")),state->{render(state);hint("已请求停止，保留已完成结果");}));
      if(n==2)startActivity(new Intent(this,MainActivity.class).putExtra("controls",true));
      if(n==3){stopService(new Intent(this,AssistantMonitor.class));render(current);hint("主动提醒已关闭；下次打开助手时恢复");}
    }).show();
  }
  void fullResult(){
    if(voiceWorking){hint("请先结束录音并等待识别完成");return;}
    if(!AssistantMonitor.fresh()){hint("电脑未连接，恢复后可查看全文");return;}
    final String target=current.optString("request_id");
    exchange(()->request(endpoint("/assistant/state")+"?t="+URLEncoder.encode(get("token"),"UTF-8"),"GET","",""),state->{
      if(!target.equals(state.optString("request_id"))){render(state);hint("任务已变化，请查看当前结果");return;}
      showPages("完整结果",state.optString("reply","暂无结果"));
    });
  }
  void showPages(String title,String value){
    // Only one short page is laid out at a time; even long diffs remain cheap to scroll.
    final int size=1000,total=Math.max(1,(value.length()+size-1)/size);final int[] index={0};
    ScrollView container=new ScrollView(this){
      @Override protected void onMeasure(int width,int height){
        // Watch dialog themes do not constrain a custom ScrollView automatically.
        super.onMeasure(width,View.MeasureSpec.makeMeasureSpec(getResources().getDisplayMetrics().heightPixels/2,View.MeasureSpec.AT_MOST));
      }
    };TextView text=new TextView(this);
    text.setTextSize(16);text.setTextColor(Color.WHITE);text.setTextIsSelectable(true);text.setPadding(dp(12),dp(8),dp(12),dp(8));container.addView(text);
    AlertDialog dialog=new AlertDialog.Builder(this).setTitle(title).setView(container)
      .setPositiveButton("关闭",null).setNegativeButton("上一页",null).setNeutralButton("下一页",null).create();
    dialog.show();
    Runnable update=()->{
      int start=index[0]*size,end=Math.min(value.length(),start+size);
      // Keep UTF-16 surrogate pairs together at page boundaries.
      if(start>0&&start<value.length()&&Character.isLowSurrogate(value.charAt(start)))start--;
      if(end<value.length()&&end>0&&Character.isHighSurrogate(value.charAt(end-1)))end--;
      text.setText(value.substring(start,end));dialog.setTitle(title+" "+(index[0]+1)+"/"+total);
      dialog.getButton(-2).setEnabled(index[0]>0);dialog.getButton(-3).setEnabled(index[0]+1<total);container.scrollTo(0,0);
    };
    dialog.getButton(-2).setOnClickListener(v->{index[0]--;update.run();});
    dialog.getButton(-3).setOnClickListener(v->{index[0]++;update.run();});update.run();
  }
  void beginSpeech(){
    if(capture.recording){capture.stop();mic.setText("正在识别…");hint("正在电脑上识别，返回键和滑动仍可使用");return;}
    if(voiceWorking||busy){hint("正在处理，请稍候");return;}
    if(!AssistantMonitor.fresh()){hint("电脑未连接 · 没有开始录音");refresh();return;}
    if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
      requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},7);return;
    }
    discardCapture=false;voiceWorking=true;mic.setText("正在听 · 点此结束");hint("说完点结束，最长 30 秒");
    capture.start(audio->runOnUiThread(()->{
      if(!active||discardCapture||isDestroyed()){voiceWorking=false;return;}
      mic.setText("正在识别…");
      if(audio.length<1000){voiceWorking=false;mic.setText("点一下说话");hint("录音太短，请再说一次");return;}
      exchange(()->upload(audio),result->{
        if(!prompt.getText().toString().trim().isEmpty()){
          new AlertDialog.Builder(this).setMessage("用识别文字替换当前草稿？")
            .setPositiveButton("替换",(d,w)->prompt.setText(result.optString("text")))
            .setNegativeButton("保留草稿",null).show();
        }else prompt.setText(result.optString("text"));
        hint("已识别，请检查文字再发送");
      });
      // exchange completion restores the microphone for both success and failure.
    }),error->runOnUiThread(()->{if(!isDestroyed()){voiceWorking=false;mic.setText("点一下说话");if(active)hint("录音失败，请重试");}}));
  }
  String upload(byte[] audio)throws Exception{
    try{
      HttpURLConnection c=(HttpURLConnection)new URL(endpoint("/assistant/voice")).openConnection();
      c.setRequestMethod("POST");c.setConnectTimeout(5000);c.setReadTimeout(120000);c.setDoOutput(true);
      c.setRequestProperty("Authorization","Bearer "+get("token"));c.setRequestProperty("Content-Type","audio/wav");c.setFixedLengthStreamingMode(audio.length);
      try{
        try(OutputStream out=c.getOutputStream()){out.write(audio);}
        int code=c.getResponseCode();InputStream in=code<400?c.getInputStream():c.getErrorStream();StringBuilder body=new StringBuilder();
        if(in!=null)try(Reader r=new InputStreamReader(in,StandardCharsets.UTF_8)){
          char[] b=new char[2048];int n;while((n=r.read(b))!=-1){if(body.length()+n>12000)throw new IOException("识别结果过大");body.append(b,0,n);}
        }
        return "HTTP "+code+"\n"+body;
      }finally{c.disconnect();}
    }finally{runOnUiThread(()->{voiceWorking=false;if(!isDestroyed())mic.setText("点一下说话");});}
  }
  void handleAttention(Intent intent){
    boolean attention=intent.getBooleanExtra("attention",false);
    if(Build.VERSION.SDK_INT>=27){setShowWhenLocked(attention);setTurnScreenOn(attention);}
    else if(attention)getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED|WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED|WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
    if(attention&&scroll!=null)scroll.post(()->scroll.scrollTo(0,0));
  }
  @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);handleAttention(intent);render(AssistantMonitor.latest);}
  @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){
    super.onRequestPermissionsResult(code,permissions,results);
    if(code==7)hint(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED?"录音权限已允许，点麦克风开始":"没有录音权限，可以打字");
  }
  @Override protected void onResume(){super.onResume();active=true;if(mic!=null&&!voiceWorking)mic.setText("点一下说话");render(AssistantMonitor.latest);}
  @Override protected void onPause(){
    active=false;discardCapture=true;capture.stop();ui.removeCallbacks(saveDraft);
    if(prompt!=null)prefs.edit().putString("aiDraft",prompt.getText().toString()).apply();super.onPause();
  }
  @Override protected void onStop(){
    if(Build.VERSION.SDK_INT>=27)setTurnScreenOn(false);
    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
    super.onStop();
  }
  @Override public void onBackPressed(){finish();}
  @Override protected void onDestroy(){active=false;discardCapture=true;capture.stop();ui.removeCallbacksAndMessages(null);unregisterReceiver(receiver);super.onDestroy();}
}
