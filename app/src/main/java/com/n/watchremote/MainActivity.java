package com.n.watchremote;

import android.app.*;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.widget.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.json.JSONObject;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextWatcher;
import android.text.Editable;
import android.view.inputmethod.InputMethodManager;
import java.util.function.Consumer;

/** Standalone HTTP controller without Google services or native libraries. */
public class MainActivity extends Activity {
  static final int GREEN=0xFFA7F06B,INK=0xFF10150F,LINE=0xFF35462A,WHITE=0xFFE9EEE7,MUTED=0xFF929D8D;
  SharedPreferences prefs;
  LinearLayout panel;
  TextView status;
  final ExecutorService worker = Executors.newSingleThreadExecutor();
  boolean busy;
  boolean atHome;
  int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
  GradientDrawable surface(int color,int radius) {
    GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;
  }
  @Override public void onCreate(Bundle state) {
    super.onCreate(state); prefs = getSharedPreferences("remote", MODE_PRIVATE); initialPage();
  }
  void initialPage(){
    if("connection".equals(getIntent().getStringExtra("screen")))settings();
    else if("custom".equals(getIntent().getStringExtra("screen")))custom();
    else if(getIntent().getBooleanExtra("controls",false))home();
    else {aiPage();finish();}
  }
  String get(String key) { return prefs.getString(key, ""); }
  void page(String title) {
    ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
    scroll.setBackgroundColor(Color.BLACK);
    panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL);
    panel.setPadding(dp(12), dp(12), dp(12), dp(20)); scroll.addView(panel); setContentView(scroll);
    TextView heading=text(title, 20);heading.setTypeface(null,Typeface.BOLD);
  }
  TextView text(String value, int size) {
    TextView t = new TextView(this); t.setText(value); t.setTextSize(size);
    t.setTextColor(size<=12?Color.rgb(156,169,187):Color.rgb(232,238,246));
    t.setPadding(0,dp(6),0,dp(6)); panel.addView(t); return t;
  }
  void button(String title, Runnable action) {
    Button b = new Button(this); b.setText(title); b.setTextSize(16); b.setAllCaps(false);
    b.setTextColor(GREEN);b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));
    GradientDrawable shape=surface(INK,8);shape.setStroke(dp(1),LINE);b.setBackground(shape);
    LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(-1,-2);layout.setMargins(0,dp(4),0,dp(4));
    panel.addView(b,layout); b.setOnClickListener(v -> { if (!busy) action.run(); });
    if(title.equals("Codex 任务")||title.equals("发送任务")) {
      b.setBackground(surface(GREEN,8));b.setTextColor(Color.BLACK);b.setTypeface(null,Typeface.BOLD);
    }
  }
  void confirm(String title, Runnable action) {
    showDialog(new AlertDialog.Builder(this).setMessage(title).setPositiveButton("执行",(d,w)->action.run())
      .setNegativeButton("取消",null).create());
  }
  // OEM dialog themes can fix action buttons below the minimum touch size.
  AlertDialog showDialog(AlertDialog dialog) {
    dialog.show();
    int minimum=dp(48);
    for(int action:new int[]{AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_NEUTRAL}) {
      Button button=dialog.getButton(action);if(button==null)continue;
      button.setMinHeight(Math.max(button.getMinHeight(),minimum));
      button.setMinimumHeight(Math.max(button.getMinimumHeight(),minimum));
      button.setMinWidth(Math.max(button.getMinWidth(),minimum));
      button.setMinimumWidth(Math.max(button.getMinimumWidth(),minimum));
      android.view.ViewGroup.LayoutParams layout=button.getLayoutParams();
      if(layout!=null) {
        boolean resized=false;
        if(layout.height>=0&&layout.height<minimum){layout.height=minimum;resized=true;}
        if(layout.width>=0&&layout.width<minimum){layout.width=minimum;resized=true;}
        if(resized)button.setLayoutParams(layout);
      }
    }
    return dialog;
  }
  void home() {
    atHome = true; page("腕上遥控");
    status = text(get("base").isEmpty() ? "尚未配置电脑" : "电脑已配置 · 点击查询连接",12);
    button("Codex 任务",this::aiPage);
    button("查询电脑状态",()->pc("status"));
    button("锁定电脑",()->confirm("锁定电脑？",()->pc("lock")));
    button("电脑睡眠",()->confirm("让电脑进入睡眠？",()->pc("sleep")));
    button("唤醒电脑",()->task(()->wake(get("mac"))));
    button("连接设置",this::settings);
    for(int i=1;i<=3;i++) {
      final int n=i; final String name=get("name"+n).isEmpty()?"自定义 "+n:get("name"+n);
      if(!get("url"+n).isEmpty()) button(name,()->confirm("执行「"+name+"」？",
        ()->task(()->request(get("url"+n),get("method"+n),get("body"+n),get("bearer"+n)))));
    }
    button("自定义接口按钮",this::custom);
    text("Codex 在电脑执行，手表发送任务。\n家居功能需连接相应服务。",12);
  }
  String endpoint(String path) throws Exception {
    if(get("base").isEmpty()||get("token").isEmpty())throw new IllegalStateException("请先配置电脑连接");
    return get("base")+path;
  }
  void aiPage() {
    startActivity(new android.content.Intent(this,AssistantActivity.class)
      .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP|android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP));
    overridePendingTransition(0,0);
  }
  EditText field(String label,String value,boolean secret,boolean multiline) {
    text(label,14); EditText e=new EditText(this); e.setTextSize(14); e.setText(value);
    e.setTextColor(Color.WHITE);e.setHintTextColor(Color.rgb(124,139,158));
    e.setPadding(dp(10),dp(8),dp(10),dp(8));e.setBackground(surface(Color.rgb(17,24,34),12));
    e.setSingleLine(!multiline);
    e.setInputType(InputType.TYPE_CLASS_TEXT | (secret?InputType.TYPE_TEXT_VARIATION_PASSWORD:
      multiline?InputType.TYPE_TEXT_FLAG_MULTI_LINE:InputType.TYPE_TEXT_VARIATION_URI));
    panel.addView(e,new LinearLayout.LayoutParams(-1,-2)); return e;
  }
  void settings() {
    atHome=false; page("电脑连接设置");
    EditText base=field("电脑地址，例如 http://192.168.1.5:8877",get("base"),false,false);
    EditText token=field("电脑服务 TOKEN",get("token"),true,false);
    EditText mac=field("网卡 MAC（仅唤醒需要）",get("mac"),false,false);
    TextView error=text("地址和 TOKEN 需与电脑服务一致。",12);
    button("保存",()->{
      String b=base.getText().toString().trim().replaceAll("/+$","");
      try { if(!b.isEmpty()) validate(b); } catch(Exception ex) { error.setText("地址格式错误"); return; }
      prefs.edit().putString("base",b).putString("token",token.getText().toString().trim())
        .putString("mac",mac.getText().toString().trim()).apply(); home();
    }); button("返回",this::home);
  }
  void custom() {
    atHome=false; page("自定义接口");
    text("填写家居 Webhook 或自建 AI 服务接口。\n留空地址可隐藏按钮。",12);
    EditText[][] fields=new EditText[3][4]; Spinner[] methods=new Spinner[3];
    for(int i=0;i<3;i++) {
      int n=i+1; text("按钮 "+n,18);
      fields[i][0]=field("按钮名称",get("name"+n),false,false);
      fields[i][1]=field("完整 HTTP / HTTPS 地址",get("url"+n),false,false);
      Spinner s=new Spinner(this); s.setAdapter(new ArrayAdapter<String>(this,
        android.R.layout.simple_spinner_dropdown_item,new String[]{"GET","POST"}));
      s.setSelection("POST".equals(get("method"+n))?1:0); panel.addView(s); methods[i]=s;
      fields[i][2]=field("POST 内容（JSON，可留空）",get("body"+n),false,true);
      fields[i][3]=field("Bearer Token（可留空）",get("bearer"+n),true,false);
    }
    TextView error=text("仅连接你信任的服务。",12);
    button("保存按钮",()->{
      SharedPreferences.Editor edit=prefs.edit();
      for(int i=0;i<3;i++) {
        int n=i+1; String u=fields[i][1].getText().toString().trim();
        try { if(!u.isEmpty()) validate(u); } catch(Exception ex) { error.setText("按钮 "+n+" 地址格式错误"); return; }
        edit.putString("name"+n,fields[i][0].getText().toString().trim()).putString("url"+n,u)
          .putString("body"+n,fields[i][2].getText().toString())
          .putString("bearer"+n,fields[i][3].getText().toString().trim())
          .putString("method"+n,methods[i].getSelectedItem().toString());
      } edit.apply(); home();
    }); button("返回",this::home);
  }
  void pc(String endpoint) {
    if(get("base").isEmpty()||get("token").isEmpty()) { status.setText("请先保存电脑地址和 TOKEN。"); return; }
    task(()->request(get("base")+"/"+endpoint+"?t="+URLEncoder.encode(get("token"),"UTF-8"),"GET","",""));
  }
  interface Work { String run() throws Exception; }
  void task(Work work) {
    task(work,null);
  }
  void task(Work work,Consumer<String> after) {
    if(busy)return; busy=true; status.setText("正在发送…");
    worker.execute(()->{
      String result;
      try { result=work.run(); }
      catch(Exception ex) { result="连接失败："+ex.getClass().getSimpleName()+"\n检查 Wi-Fi、地址、电脑服务与防火墙。"; }
      final String message=result;
      runOnUiThread(()->{busy=false;if(!isDestroyed()){
        status.setText(message.startsWith("HTTP 200\n")?message.substring(9):message);
        if(after!=null)after.accept(message);
      }});
    });
  }
  static URL validate(String value)throws Exception {
    URL u=new URL(value);
    if(!(u.getProtocol().equals("http")||u.getProtocol().equals("https"))||u.getHost().isEmpty())
      throw new IllegalArgumentException("需要完整 HTTP 地址");
    return u;
  }
  static String request(String value,String method,String body,String bearer)throws Exception {
    return request(value,method,body,bearer,null);
  }
  static String request(String value,String method,String body,String bearer,Consumer<HttpURLConnection> opened)throws Exception {
    HttpURLConnection c=(HttpURLConnection)validate(value).openConnection();
    if(opened!=null)opened.accept(c);
    c.setInstanceFollowRedirects(false); c.setConnectTimeout(4000);
    c.setRequestProperty("X-WristCodex-Protocol","6");
    String path=c.getURL().getPath();
    c.setReadTimeout(path.equals("/assistant/send")||path.equals("/assistant/activate")?60000:
      path.equals("/assistant/conversations/manage")||path.equals("/assistant/conversations/settings")?20000:8000);
    c.setRequestMethod("POST".equals(method)?"POST":"GET");
    if(!bearer.isEmpty())c.setRequestProperty("Authorization","Bearer "+bearer);
    try {
      if("POST".equals(method)) {
        c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json; charset=utf-8");
        try(OutputStream out=c.getOutputStream()){out.write(body.getBytes(StandardCharsets.UTF_8));}
      }
      int code=c.getResponseCode();InputStream in=code<400?c.getInputStream():c.getErrorStream();String response="";
      if(in!=null)try(Reader r=new InputStreamReader(in,StandardCharsets.UTF_8)){
        char[] buffer=new char[65536];StringBuilder b=new StringBuilder();int n;
        while(b.length()<65536&&(n=r.read(buffer,0,Math.min(buffer.length,65536-b.length())))>0)b.append(buffer,0,n);
        response=b.toString();
      }return "HTTP "+code+"\n"+response;
    }finally{c.disconnect();}
  }
  static String wake(String mac)throws Exception {
    if(!mac.matches("(?i)[0-9a-f]{2}([:-][0-9a-f]{2}){5}"))return "请先填写正确的网卡 MAC 地址。";
    String[] parts=mac.split("[:-]");byte[] packet=new byte[102];java.util.Arrays.fill(packet,0,6,(byte)255);
    for(int i=6;i<packet.length;i++)packet[i]=(byte)Integer.parseInt(parts[(i-6)%6],16);
    try(DatagramSocket s=new DatagramSocket()){s.setBroadcast(true);s.send(new DatagramPacket(packet,
      packet.length,InetAddress.getByName("255.255.255.255"),9));}
    return "已发送唤醒包，不代表电脑已开机。\n电脑网卡需支持并开启网络唤醒。";
  }
  @Override public void onBackPressed(){if(!busy){if(atHome)super.onBackPressed();else home();}}
  @Override protected void onDestroy(){worker.shutdown();super.onDestroy();}
}
