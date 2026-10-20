package com.n.watchremote;
import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.*;
import android.text.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import org.json.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import java.util.concurrent.*;

/** One native activity, recycled text rows, one bounded history page at a time. */
public class AssistantActivity extends MainActivity {
  static final String NEW="new:";
  LinearLayout root,composer,pendingPanel;
  ListView list;
  EditText prompt;
  Button titleBar,mic,sendButton,olderButton,listMore,attentionButton,moreButton;
  TextView phase;
  JSONObject current=new JSONObject();
  JSONObject connectionState=new JSONObject();
  ArrayList<JSONObject> rows=new ArrayList<>();
  Rows adapter;
  String viewed="",draftOwner="",viewTitle="日常助手",pendingKey="",historyCursor="",listCursor="",historyStamp="";
  boolean active,listMode,attentionMode,readOnly,voiceWorking,discardCapture,loadingHistory;
  String attentionStamp="";
  String attentionPage="";
  String automaticHint="";
  volatile int navigation;
  Handler ui=new Handler(Looper.getMainLooper());
  final ExecutorService controls=Executors.newSingleThreadExecutor();
  final ExecutorService speech=Executors.newSingleThreadExecutor();
  final ExecutorService readCanceller=Executors.newSingleThreadExecutor();
  final UiState.Preview liveReply=new UiState.Preview();
  final UiState.History historyWindow=new UiState.History();
  volatile HttpURLConnection readConnection;
  long submittedAt,firstShownAt;
  String measuredRequest="",measuredTurn="";
  boolean dragging;
  VoiceCapture capture=new VoiceCapture();
  Runnable saveDraft=()->saveDraft();
  BroadcastReceiver receiver=new BroadcastReceiver(){public void onReceive(Context c,Intent i){applyState(AssistantMonitor.latest);}};
  @Override void initialPage(){}
  @Override public void onCreate(Bundle state){
    super.onCreate(state);active=true;viewed=get("viewedConversation");viewTitle=get("viewedTitle");
    if(viewTitle.isEmpty())viewTitle="日常助手";readOnly=prefs.getBoolean("viewedReadonly",false);
    registerReceiver(receiver,new IntentFilter(AssistantMonitor.UPDATE));
    showChat(false);applyState(AssistantMonitor.latest);handleAttention(getIntent());
    startForegroundService(new Intent(this,AssistantMonitor.class));
  }
  GradientDrawable tile(int fill,int stroke){
    GradientDrawable d=surface(fill,8);d.setStroke(dp(1),stroke);return d;
  }
  Button control(String label,boolean primary,Runnable action){
    Button b=new Button(this);b.setText(label);b.setTextSize(16);b.setAllCaps(false);
    b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));b.setPadding(dp(8),dp(6),dp(8),dp(6));
    b.setTextColor(primary?Color.BLACK:GREEN);
    b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x2288BB66),tile(primary?GREEN:Color.BLACK,primary?GREEN:LINE),null));
    b.setOnClickListener(v->action.run());return b;
  }
  void addControl(LinearLayout parent,Button b){
    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(4),0,dp(4));parent.addView(b,lp);
  }
  TextView label(String value,int size){
    TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(WHITE);
    t.setPadding(dp(4),dp(4),dp(4),dp(4));return t;
  }
  void shell(String title){
    navigation++;liveReply.clear();cancelRead();root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(12),dp(8),dp(12),dp(8));root.setBackgroundColor(Color.BLACK);setContentView(root);
    root.setFocusableInTouchMode(true);root.requestFocus();
    titleBar=control(title,false,()->{if(listMode)showChat(true);else openList();});
    titleBar.setSingleLine(true);titleBar.setEllipsize(TextUtils.TruncateAt.END);
    titleBar.setContentDescription(title+"，点按切换对话，长按更多操作");
    LinearLayout heading=new LinearLayout(this);heading.setGravity(Gravity.CENTER_VERTICAL);
    heading.addView(titleBar,new LinearLayout.LayoutParams(0,dp(48),1));
    moreButton=control("更多",false,this::menu);moreButton.setContentDescription("更多操作");
    LinearLayout.LayoutParams moreLp=new LinearLayout.LayoutParams(dp(64),dp(48));moreLp.setMargins(dp(6),0,0,0);
    heading.addView(moreButton,moreLp);root.addView(heading);
    LinearLayout stateRow=new LinearLayout(this);stateRow.setGravity(Gravity.CENTER_VERTICAL);
    phase=label("",12);phase.setTextColor(MUTED);phase.setMaxLines(2);
    stateRow.addView(phase,new LinearLayout.LayoutParams(0,dp(48),1));
    attentionButton=control("待处理",false,()->openAttention(""));attentionButton.setVisibility(View.GONE);
    stateRow.addView(attentionButton,new LinearLayout.LayoutParams(dp(94),dp(48)));root.addView(stateRow);
    status=label("",12);
    list=new ListView(this);list.setBackgroundColor(Color.BLACK);list.setDivider(null);
    list.setCacheColorHint(Color.BLACK);list.setScrollingCacheEnabled(false);list.setClipToPadding(true);
    list.setOnScrollListener(new AbsListView.OnScrollListener(){
      public void onScrollStateChanged(AbsListView v,int state){dragging=state!=SCROLL_STATE_IDLE;if(!dragging&&!listMode&&historyWindow.deferred)updateChat();}
      public void onScroll(AbsListView v,int first,int count,int total){}
    });
    root.addView(list,new LinearLayout.LayoutParams(-1,0,1));
  }
  String draftKey(){return "draft:"+viewed;}
  void saveDraft(){
    ui.removeCallbacks(saveDraft);
    if(prompt!=null&&!listMode)prefs.edit().putString("draft:"+draftOwner,prompt.getText().toString()).apply();
  }
  void persistView(){
    prefs.edit().putString("viewedConversation",viewed).putString("viewedTitle",viewTitle).putBoolean("viewedReadonly",readOnly).apply();
    AssistantMonitor.selectView(viewed);
  }
  void showChat(boolean load){
    saveDraft();historyWindow.loaded(false);listMode=false;attentionMode=false;loadingHistory=false;pendingKey="";historyStamp="";historyCursor="";rows.clear();
    if(shared()&&!viewed.equals(current.optString("thread_id")))current=new JSONObject();
    shell(viewTitle+" ▾");
    olderButton=control("加载更早消息",false,()->loadHistory(true));
    LinearLayout headers=new LinearLayout(this);headers.setOrientation(LinearLayout.VERTICAL);
    pendingPanel=new LinearLayout(this);pendingPanel.setOrientation(LinearLayout.VERTICAL);headers.addView(pendingPanel);
    addControl(headers,olderButton);
    list.addHeaderView(headers,null,false);
    adapter=new Rows();list.setAdapter(adapter);
    list.setOnItemClickListener((p,v,pos,id)->{
      int at=pos-list.getHeaderViewsCount();if(at>=0&&at<rows.size())openMessage(rows.get(at));
    });
    composer=new LinearLayout(this);composer.setOrientation(LinearLayout.VERTICAL);
    prompt=new EditText(this);prompt.setTextColor(WHITE);prompt.setHintTextColor(MUTED);prompt.setTextSize(15);
    prompt.setPadding(dp(8),dp(6),dp(8),dp(6));prompt.setBackground(tile(INK,LINE));prompt.setMaxLines(3);prompt.setMinLines(1);prompt.setMinHeight(dp(48));
    prompt.setHint("继续聊…");prompt.setContentDescription("当前对话的消息");
    prompt.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
    String draft=get(draftKey());if(draft.isEmpty()&&!prefs.getBoolean("draftV4Migrated",false))draft=get("aiDraft");
    draftOwner=viewed;prompt.setText(draft);
    prompt.addTextChangedListener(new TextWatcher(){
      public void beforeTextChanged(CharSequence s,int start,int count,int after){}
      public void onTextChanged(CharSequence s,int start,int before,int count){ui.removeCallbacks(saveDraft);ui.postDelayed(saveDraft,500);}
      public void afterTextChanged(Editable e){}
    });
    composer.addView(prompt,new LinearLayout.LayoutParams(-1,-2));
    LinearLayout inputRow=new LinearLayout(this);inputRow.setGravity(Gravity.CENTER_VERTICAL);inputRow.setPadding(0,dp(6),0,0);
    mic=control("点一下说话",false,this::beginSpeech);mic.setTextSize(17);
    LinearLayout.LayoutParams micLp=new LinearLayout.LayoutParams(0,dp(48),1);inputRow.addView(mic,micLp);
    sendButton=control("发送",true,this::send);
    LinearLayout.LayoutParams sendLp=new LinearLayout.LayoutParams(dp(76),dp(48));sendLp.setMargins(dp(8),0,0,0);inputRow.addView(sendButton,sendLp);
    composer.addView(inputRow);root.addView(composer);
    root.addView(status);status.setMaxLines(2);
    titleBar.setOnLongClickListener(v->{menu();return true;});
    updateChat();if(load&&!isNew()&&!viewed.isEmpty())loadHistory(false);
  }
  boolean isNew(){return viewed.startsWith(NEW);}
  boolean shared(){return connectionState.optInt("protocol_version")>=5;}
  boolean running(){String s=current.optString("state");return s.equals("starting")||s.equals("running")||s.equals("waiting");}
  boolean viewingActive(){
    boolean creating=!get("followRequestId").isEmpty()&&get("followRequestId").equals(current.optString("request_id"))&&viewed.equals(get("followDraft"));
    return (!viewed.isEmpty()&&viewed.equals(current.optString("thread_id")))||creating;
  }
  void hint(String value){if(status!=null&&!status.getText().toString().equals(value))status.setText(value);}
  void applyState(JSONObject state){
    if(state.length()==0||state.optLong("revision",-1)<connectionState.optLong("revision",-1))return;
    connectionState=state;
    if(!active)return;
    String target=state.optString("thread_id");
    String follow=get("followRequestId"),followView=get("followDraft");
    if(!target.isEmpty()&&(!follow.isEmpty()&&follow.equals(state.optString("request_id")))){
      try{
        JSONObject sent=new JSONObject(get("pending:"+followView));
        if(follow.equals(sent.optString("request_id")))acknowledgeDraft(followView,target,sent.optString("prompt"),follow);
      }catch(JSONException ignored){}
      if(viewed.equals(followView)||viewed.isEmpty())bindCreated(target,state.optString("title","手表对话"));
      prefs.edit().remove("followRequestId").remove("followDraft").apply();
    }else if(viewed.isEmpty()&&!target.isEmpty()){
      bindCreated(target,state.optString("title","手表对话"));
    }
    if(!shared()||(!isNew()&&viewed.equals(target))){
      current=state;if(shared())readOnly=state.optBoolean("readonly",true);
    }
    if(!listMode)updateChat();
    else if(attentionMode){
      String stamp=state.optString("attention_keys");
      if(!stamp.equals(attentionStamp)){attentionStamp=stamp;loadAttention(attentionPage);}
    }
  }
  void acknowledgeDraft(String origin,String accepted,String value,String requestId){
    boolean here=viewed.equals(origin)||(!accepted.isEmpty()&&viewed.equals(accepted));
    if(here&&!listMode)saveDraft();
    for(String target:new String[]{origin,accepted}){
      if(!target.isEmpty()&&get("draft:"+target).trim().equals(value))prefs.edit().putString("draft:"+target,"").apply();
    }
    try{if(requestId.equals(new JSONObject(get("pending:"+origin)).optString("request_id")))
      prefs.edit().remove("pending:"+origin).apply();}catch(JSONException ignored){}
    if(here&&!listMode&&prompt!=null&&prompt.getText().toString().trim().equals(value)){
      prompt.setText("");prompt.clearFocus();
      ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(prompt.getWindowToken(),0);
    }
  }
  void bindCreated(String target,String title){
    if(!listMode)saveDraft();
    String draft=get("draft:"+viewed);
    String old=viewed;
    viewed=target;viewTitle=title;readOnly=false;
    if(!draft.isEmpty()&&get(draftKey()).isEmpty())prefs.edit().putString(draftKey(),draft).apply();
    if(!prefs.getBoolean("draftV4Migrated",false)){
      if(get("pending:"+target).isEmpty()&&!get("pendingPayload").isEmpty())prefs.edit().putString("pending:"+target,get("pendingPayload")).apply();
      prefs.edit().putBoolean("draftV4Migrated",true).remove("pendingPayload").remove("aiDraft").apply();
    }
    if(old.equals(get("newDraft")))prefs.edit().remove("newDraft").apply();
    persistView();
    if(!listMode){draftOwner=viewed;prompt.setText(get(draftKey()));titleBar.setText(viewTitle+" ▾");}
  }
  void updateChat(){
    if(listMode||phase==null)return;
    String connection=AssistantMonitor.fresh()?"ONLINE":"OFFLINE";
    String stage=isNew()?"新对话":shared()?current.optString("message","正在读取对话"):readOnly?"电脑记录 · 只读":viewingActive()?current.optString("message","就绪"):"就绪";
    if(shared()&&!connectionState.optBoolean("desktop_connected"))stage="电脑客户端未连接";
    String text=connection+" · "+stage;
    if(!phase.getText().toString().equals(text))phase.setText(text);
    JSONArray pending=viewingActive()?current.optJSONArray("pending"):null;
    JSONObject item=pending==null?null:pending.optJSONObject(0);
    String key=item==null?"":viewed+":"+item.optString("id");
    if(!key.equals(pendingKey)){
      pendingKey=key;pendingPanel.removeAllViews();if(item!=null){showPending(item);list.setSelection(0);}
    }
    int count=connectionState.optInt("attention_count");
    attentionButton.setVisibility(shared()&&count>0?View.VISIBLE:View.INVISIBLE);attentionButton.setText("待办 "+count);
    boolean blocked=!shared()&&running()&&!viewingActive();
    boolean cannotSteer=shared()&&running()&&!current.optBoolean("can_steer");
    composer.setVisibility(readOnly||item!=null||blocked?View.GONE:View.VISIBLE);
    sendButton.setEnabled(!busy&&!blocked&&!cannotSteer);sendButton.setAlpha(busy||blocked||cannotSteer?0.45f:1f);
    mic.setEnabled(!blocked);
    boolean activation=shared()&&readOnly&&!viewed.isEmpty()&&!isNew()&&connectionState.optBoolean("desktop_connected");
    boolean returnLink=!shared()&&(blocked||readOnly)&&!current.optString("thread_id").isEmpty();
    status.setClickable(returnLink||activation);status.setMinimumHeight(returnLink||activation?dp(48):0);status.setTextColor(returnLink||activation?GREEN:MUTED);
    status.setOnClickListener(v->{if(activation)activateViewed();else if(returnLink)returnToActive();});
    status.setClickable(returnLink||activation);
    String nextHint=blocked?"另一段对话正在执行 · 点此返回":activation?"点此连接电脑中的这段对话":readOnly?
      (shared()?"请先打开电脑客户端；草稿已保留":returnLink?"电脑记录只读 · 点此返回手表对话":"电脑记录当前只读"):
      cannotSteer&&item==null?current.optString("steer_unavailable_reason","请等本轮结束后发送；草稿会保留"):"";
    if(!nextHint.isEmpty())hint(nextHint);
    else if(status.getText().toString().equals(automaticHint))hint("");
    automaticHint=nextHint;
    updatePreview();
    olderButton.setVisibility(historyCursor.isEmpty()||item!=null?View.GONE:View.VISIBLE);
    String stamp=current.optString("thread_id")+":"+current.optString("turn_id")+":"+current.optString("state");
    if(viewingActive()&&!running()&&!stamp.equals(historyStamp)&&!loadingHistory){
      if(historyWindow.shouldRefresh(rows.isEmpty(),atBottom())){historyStamp=stamp;loadHistory(false,true);}
      else if(pendingKey.isEmpty())hint("新回复已完成 · 更多可返回最新消息");
    }
  }
  void query(Work work,int expected,Consumer<JSONObject> done){
    worker.execute(()->{
      if(expected!=navigation)return;
      try{
        JSONObject result=decode(work.run());
        runOnUiThread(()->{if(!isDestroyed()&&active&&expected==navigation)done.accept(result);});
      }catch(Exception e){runOnUiThread(()->{if(!isDestroyed()&&active&&expected==navigation){loadingHistory=false;hint("读取失败，可在更多中刷新");}});}
    });
  }
  void cancelRead(){
    final HttpURLConnection old=readConnection;readConnection=null;
    if(old!=null&&!readCanceller.isShutdown())readCanceller.execute(old::disconnect);
  }
  String readRequest(String address)throws Exception{
    try{return request(address,"GET","","",c->readConnection=c);}
    finally{readConnection=null;}
  }
  JSONObject decode(String raw)throws Exception{
    int at=raw.indexOf('\n');
    if(!(raw.startsWith("HTTP 200\n")||raw.startsWith("HTTP 202\n")))throw new IOException(at<0?raw:raw.substring(at+1));
    return new JSONObject(raw.substring(at+1));
  }
  String url(String path)throws Exception{return endpoint(path)+(path.contains("?")?"&":"?")+"t="+URLEncoder.encode(get("token"),"UTF-8");}
  String enc(String value)throws Exception{return URLEncoder.encode(value,"UTF-8");}
  void loadHistory(boolean older){
    loadHistory(older,false);
  }
  void loadHistory(boolean older,boolean automatic){
    if(listMode||isNew()||viewed.isEmpty()||loadingHistory)return;
    final String target=viewed,cursor=older?historyCursor:"";final int epoch=navigation;
    if(older&&cursor.isEmpty())return;loadingHistory=true;
    query(()->readRequest(url("/assistant/conversations/messages?thread_id="+enc(target)+(cursor.isEmpty()?"":"&cursor="+enc(cursor)))),epoch,data->{
      if(automatic&&!historyWindow.shouldRefresh(rows.isEmpty(),atBottom())){
        loadingHistory=false;historyStamp="";hint("新回复已完成 · 更多可返回最新消息");return;
      }
      loadingHistory=false;JSONObject meta=data.optJSONObject("conversation");
      historyWindow.loaded(older);
      JSONObject loaded=data.optJSONObject("active");
      if(!older&&loaded!=null)historyStamp=loaded.optString("thread_id")+":"+loaded.optString("turn_id")+":"+loaded.optString("state");
      if(meta!=null){viewTitle=meta.optString("title","对话");readOnly=meta.optBoolean("readonly",true);persistView();titleBar.setText(viewTitle+" ▾");}
      JSONArray messages=data.optJSONArray("messages");
      ArrayList<JSONObject> incoming=new ArrayList<>();if(messages!=null)for(int i=0;i<messages.length();i++)incoming.add(messages.optJSONObject(i));
      int first=list.getFirstVisiblePosition();View child=list.getChildAt(0);int offset=child==null?0:child.getTop();
      String anchor=anchorId();boolean follow=atBottom();boolean empty=rows.isEmpty();
      if(older){
        Set<String> seen=new HashSet<>();for(JSONObject row:rows)seen.add(row.optString("id"));
        incoming.removeIf(row->row==null||seen.contains(row.optString("id")));rows.addAll(0,incoming);
        while(rows.size()>30)rows.remove(rows.size()-1);
      }else{rows.clear();for(JSONObject row:incoming)if(row!=null)rows.add(row);}
      liveReply.clear();
      historyCursor=data.optString("next_cursor","");if(historyCursor.equals("null"))historyCursor="";
      adapter.notifyDataSetChanged();applyState(data.optJSONObject("active")==null?current:data.optJSONObject("active"));updateChat();
      if(!pendingKey.isEmpty())list.setSelection(0);
      else if(older)list.setSelectionFromTop(Math.min(rows.size(),first+incoming.size()),offset);
      else if(!rows.isEmpty()&&(follow||empty))list.setSelection(rows.size()+list.getHeaderViewsCount()-1);
      else restoreAnchor(anchor,first,offset);
      if(status.getText().toString().startsWith("读取失败")||(!older&&status.getText().toString().startsWith("新回复已完成")))hint("");
      if(rows.isEmpty())hint(readOnly?"暂无文字记录，可加载更早消息":"说一句，开始这段对话");
    });
  }
  void openList(){
    if(voiceWorking){hint("请先结束录音并等待识别完成");return;}
    saveDraft();prompt=null;listMode=true;attentionMode=false;loadingHistory=false;rows.clear();listCursor="";
    shell("对话列表");
    Button create=control("＋ 新建对话",false,this::newConversation);
    list.addHeaderView(create,null,false);
    LinearLayout footer=new LinearLayout(this);footer.setOrientation(LinearLayout.VERTICAL);
    listMore=control("加载更多",false,()->{if(!listCursor.isEmpty())loadConversations(listCursor);});listMore.setEnabled(false);addControl(footer,listMore);
    Button first=control("返回最近对话",false,()->loadConversations(""));addControl(footer,first);list.addFooterView(footer,null,false);
    adapter=new Rows();list.setAdapter(adapter);
    list.setOnItemClickListener((p,v,pos,id)->{
      int at=pos-list.getHeaderViewsCount();if(at<0||at>=rows.size())return;
      JSONObject item=rows.get(at);viewed=item.optString("id");viewTitle=item.optString("title");readOnly=item.optBoolean("readonly",true);persistView();showChat(true);
    });
    root.addView(status);status.setMaxLines(2);loadConversations("");
  }
  void loadConversations(String cursor){
    final int epoch=navigation;phase.setText("按最近更新时间排列");
    query(()->readRequest(url("/assistant/conversations"+(cursor.isEmpty()?"":"?cursor="+enc(cursor)))),epoch,data->{
      rows.clear();JSONArray items=data.optJSONArray("conversations");if(items!=null)for(int i=0;i<items.length();i++)rows.add(items.optJSONObject(i));
      listCursor=data.optString("next_cursor","");if(listCursor.equals("null"))listCursor="";
      listMore.setEnabled(!listCursor.isEmpty());listMore.setText(listCursor.isEmpty()?"已到列表末尾":"加载更多");
      adapter.notifyDataSetChanged();list.setSelection(0);hint(listCursor.isEmpty()?"已到列表末尾":"每页 20 段 · 点标题返回聊天");
      JSONObject state=data.optJSONObject("active");if(state!=null)applyState(state);
    });
  }
  void newConversation(){
    if(!shared()&&running()){hint("请等当前任务结束，或返回后停止任务");return;}
    String draft=get("newDraft");
    viewed=draft.isEmpty()?NEW+UUID.randomUUID():draft;viewTitle="新对话";readOnly=false;
    prefs.edit().putString("newDraft",viewed).apply();persistView();showChat(false);hint("第一条消息发送后才创建新对话");
  }
  class Holder{TextView name,body;}
  class Rows extends BaseAdapter{
    public int getCount(){return rows.size();}
    public JSONObject getItem(int position){return rows.get(position);}
    public long getItemId(int position){String id=getItem(position).optString("id");long value=1125899906842597L;for(int i=0;i<id.length();i++)value=31*value+id.charAt(i);return value;}
    public boolean hasStableIds(){return true;}
    public View getView(int position,View reusable,ViewGroup parent){
      Holder h;
      if(reusable==null){
        LinearLayout box=new LinearLayout(AssistantActivity.this);box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(8),dp(8),dp(8),dp(10));
        GradientDrawable line=surface(Color.BLACK,0);line.setStroke(dp(1),LINE);box.setBackground(line);
        h=new Holder();h.name=label("",12);h.name.setTextColor(GREEN);h.body=label("",15);
        box.addView(h.name);box.addView(h.body);box.setTag(h);reusable=box;
      }else h=(Holder)reusable.getTag();
      bind(reusable,getItem(position));return reusable;
    }
    void bind(View reusable,JSONObject row){
      Holder h=(Holder)reusable.getTag();
      if(listMode){
        h.name.setTextSize(16);h.body.setTextSize(12);
        h.name.setMaxLines(2);h.name.setEllipsize(TextUtils.TruncateAt.END);h.body.setMaxLines(2);
        h.name.setText(row.optString("title"));
        h.body.setText(attentionMode?row.optString("summary")+(row.optInt("count")>1?" · "+row.optInt("count")+" 项":""):
          (row.optBoolean("readonly")?"只读 · 可尝试连接":row.optString("source").equals("shared")?"电脑 · 可续聊":"手表")+(row.optString("id").equals(viewed)?" · 当前":""));
      }else{
        h.name.setTextSize(12);h.body.setTextSize(15);
        h.name.setMaxLines(1);h.body.setMaxLines(4);h.body.setEllipsize(TextUtils.TruncateAt.END);
        String body=row.optString("text");
        boolean expand=row.optBoolean("expandable")||body.length()>80||body.contains("\n");
        String state=row.optBoolean("live")?(row.optBoolean("complete")?"":" · 生成中"):row.optString("delivery");
        setText(h.name,(row.optString("role").equals("user")?"你":"> 助手")+state+(expand?" · 点开全文":""));
        setText(h.body,body.length()>80?body.substring(0,80)+"…":body);
      }
    }
  }
  void setText(TextView view,String value){if(!view.getText().toString().equals(value))view.setText(value);}
  boolean atBottom(){return !dragging&&(rows.isEmpty()||list.getLastVisiblePosition()>=rows.size()+list.getHeaderViewsCount()-1);}
  String anchorId(){int index=list.getFirstVisiblePosition()-list.getHeaderViewsCount();return index>=0&&index<rows.size()?rows.get(index).optString("id"):"";}
  void restoreAnchor(String id,int position,int offset){
    for(int i=0;i<rows.size();i++)if(!id.isEmpty()&&id.equals(rows.get(i).optString("id"))){list.setSelectionFromTop(i+list.getHeaderViewsCount(),offset);return;}
    list.setSelectionFromTop(Math.max(0,Math.min(position,rows.size()+list.getHeaderViewsCount()-1)),offset);
  }
  void updatePreview(){
    if(listMode||!viewingActive()||historyWindow.olderWindow)return;
    JSONObject preview=current.optJSONObject("reply_preview");
    if(preview==null){
      if(current.optBoolean("readonly"))return;
      boolean removed=rows.removeIf(row->row.optBoolean("live")&&!row.optBoolean("complete"));
      liveReply.clear();if(removed)adapter.notifyDataSetChanged();return;
    }
    if(!liveReply.update(viewed,current.optString("turn_id"),preview.optString("thread_id"),preview.optString("turn_id"),
        preview.optString("item_id"),preview.optString("text"),preview.optLong("version",-1),preview.optBoolean("complete"),preview.optBoolean("clipped")))return;
    boolean follow=atBottom();int first=list.getFirstVisiblePosition();View child=list.getChildAt(0);int offset=child==null?0:child.getTop();String anchor=anchorId();
    int index=-1;boolean changed=false;
    for(int i=rows.size()-1;i>=0;i--){JSONObject row=rows.get(i);
      if(row.optBoolean("live")&&!row.optBoolean("complete")&&!row.optString("id").equals(liveReply.itemId)){rows.remove(i);changed=true;}
    }
    for(int i=0;i<rows.size();i++)if(rows.get(i).optString("id").equals(liveReply.itemId)){index=i;break;}
    if(index>=0&&!rows.get(index).optBoolean("live")&&liveReply.complete)return;
    try{
      JSONObject row=new JSONObject();row.put("id",liveReply.itemId);row.put("role","assistant");row.put("text",liveReply.text);
      row.put("live",true);row.put("complete",liveReply.complete);row.put("clipped",liveReply.clipped);row.put("expandable",true);
      if(index<0){rows.add(row);while(rows.size()>30)rows.remove(0);index=rows.size()-1;changed=true;}else rows.set(index,row);
      if(changed)adapter.notifyDataSetChanged();else{
        int visible=index+list.getHeaderViewsCount()-list.getFirstVisiblePosition();View view=list.getChildAt(visible);
        if(view!=null&&view.getTag() instanceof Holder)adapter.bind(view,row);
      }
      if(follow&&changed)list.setSelection(rows.size()+list.getHeaderViewsCount()-1);
      else if(changed)restoreAnchor(anchor,first,offset);
      if(!measuredRequest.isEmpty()&&current.optString("request_id").equals(measuredRequest)){
        if(firstShownAt==0){firstShownAt=SystemClock.elapsedRealtime();android.util.Log.i("WristCodexTiming","request="+measuredRequest+" first_display_ms="+(firstShownAt-submittedAt));}
        if(liveReply.complete&&!measuredTurn.equals(liveReply.turnId)){measuredTurn=liveReply.turnId;android.util.Log.i("WristCodexTiming","request="+measuredRequest+" complete_display_ms="+(SystemClock.elapsedRealtime()-submittedAt));}
      }
    }catch(JSONException ignored){}
  }

  void exchange(Work work,Consumer<JSONObject> done){
    if(busy){hint("正在提交，请稍候");return;}
    busy=true;if(sendButton!=null)sendButton.setEnabled(false);
    controls.execute(()->{
      JSONObject value=null;String error=null;
      try{value=decode(work.run());}catch(Exception e){error=e.getMessage()==null?"连接失败":e.getMessage();}
      final JSONObject result=value;final String failure=error;
      runOnUiThread(()->{
        busy=false;
        if(failure!=null){if(!isDestroyed()){
          hint("未确认送达："+failure.substring(0,Math.min(failure.length(),140)));
          for(JSONObject row:rows)if(" · 发送中".equals(row.optString("delivery")))try{row.put("delivery"," · 未确认送达");}catch(JSONException ignored){}
          if(adapter!=null)adapter.notifyDataSetChanged();
        }}
        else if(!isDestroyed())done.accept(result);
        if(!isDestroyed()&&active&&!listMode)updateChat();
      });
    });
  }
  void send(){
    if(readOnly){hint("电脑对话当前只读");return;}
    if(voiceWorking){hint("请先结束录音并检查文字");return;}
    if(!AssistantMonitor.fresh()){refresh();return;}
    if(connectionState.optInt("protocol_version")<4){hint("请先更新电脑服务");return;}
    if(shared()&&!connectionState.optBoolean("desktop_connected")){hint("电脑客户端未连接，草稿已保留");return;}
    if(shared()&&running()&&!current.optBoolean("can_steer")){hint(current.optString("steer_unavailable_reason","请等本轮结束"));return;}
    if(running()&&!viewingActive()){hint("请等另一段对话执行结束");return;}
    if(running()&&current.optString("turn_id").isEmpty()){hint("正在建立任务，稍后可补充");return;}
    String value=prompt.getText().toString().trim();
    if(value.isEmpty()||value.length()>4000){hint("请输入 1–4000 字符");return;}
    if(!pendingKey.isEmpty()){hint("请先处理确认选项");list.setSelection(0);return;}
    final String origin=viewed,draft=draftKey(),pending="pending:"+origin;
    try{
      JSONObject payload=null;
      try{JSONObject saved=new JSONObject(get(pending));if(value.equals(saved.optString("prompt")))payload=saved;}catch(Exception ignored){}
      if(payload==null){
        if(running()&&!current.optString("state").equals("running")){hint("正在连接，稍后再补充");return;}
        String kind=running()?"steer":isNew()||viewed.isEmpty()?"new":"continue";
        payload=new JSONObject();payload.put("prompt",value);payload.put("request_id",UUID.randomUUID().toString());
        payload.put("kind",kind);payload.put("new",kind.equals("new"));
        payload.put("thread_id",kind.equals("new")?"":viewed);payload.put("turn_id",current.optString("turn_id"));
      }
      final String body=payload.toString(),kind=payload.getString("kind"),requestId=payload.getString("request_id");
      prefs.edit().putString(pending,body).apply();saveDraft();
      if(kind.equals("new"))prefs.edit().putString("followRequestId",requestId).putString("followDraft",origin).apply();
      hint("正在发送…");
      submittedAt=SystemClock.elapsedRealtime();firstShownAt=0;measuredRequest=requestId;measuredTurn="";
      JSONObject local=null;for(JSONObject row:rows)if(row.optString("id").equals("local:"+requestId)){local=row;break;}
      if(local==null){local=new JSONObject();local.put("id","local:"+requestId);local.put("role","user");rows.add(local);}
      local.put("text",value.substring(0,Math.min(value.length(),180)));local.put("delivery"," · 发送中");
      while(rows.size()>30)rows.remove(0);adapter.notifyDataSetChanged();list.setSelection(rows.size()+list.getHeaderViewsCount()-1);
      exchange(()->request(endpoint(kind.equals("steer")?"/assistant/steer":"/assistant/send"),"POST",body,get("token")),state->{
        String accepted=state.optString("accepted_thread_id");
        if(accepted.isEmpty()&&requestId.equals(state.optString("request_id")))accepted=state.optString("thread_id");
        boolean stillHere=origin.equals(viewed)||(kind.equals("new")&&!accepted.isEmpty()&&viewed.equals(accepted));
        acknowledgeDraft(origin,accepted,value,requestId);
        if(stillHere&&!accepted.isEmpty()&&!accepted.equals(viewed))bindCreated(accepted,accepted.equals(state.optString("thread_id"))?state.optString("title",viewTitle):viewTitle);
        applyState(state);
        if(active&&!listMode&&stillHere){
          for(JSONObject row:rows)if(row.optString("id").equals("local:"+requestId))try{row.put("delivery","");}catch(JSONException ignored){}
          adapter.notifyDataSetChanged();
          hint(kind.equals("steer")?"补充已送达当前任务":"电脑已接收");
          if(!viewingActive())loadHistory(false);
        }
      });
    }catch(Exception e){hint("消息未发送，请检查连接");}
  }
  void showPending(JSONObject item){
    final String thread=current.optString("thread_id"),turn=current.optString("turn_id"),id=item.optString("id");
    pendingPanel.addView(label(current.optString("title",viewTitle),12));
    TextView question=label(item.optString("summary","需要你决定"),16);question.setTextColor(0xFFFFCC75);pendingPanel.addView(question);
    if(item.optString("kind").equals("manual")){
      pendingPanel.addView(label("此类操作请在电脑处理",15));
    }else if(item.optString("kind").equals("approval")){
      addControl(pendingPanel,control("查看操作详情",false,()->showPages("操作详情",item.optString("detail"))));
      JSONArray decisions=item.optJSONArray("decisions");
      if(decisions!=null){
        for(int n=0;n<decisions.length();n++){
          JSONObject option=decisions.optJSONObject(n);if(option==null)continue;
          String decision=option.optString("value");
          addControl(pendingPanel,control(option.optString("label"),decision.equals("accept"),()->answer(id,decision,null,thread,turn)));
        }
      }else{
        addControl(pendingPanel,control("仅本次允许",true,()->answer(id,"accept",null,thread,turn)));
        addControl(pendingPanel,control("拒绝",false,()->answer(id,"decline",null,thread,turn)));
      }
    }else{
      JSONArray questions=item.optJSONArray("questions");Map<String,EditText> values=new LinkedHashMap<>();
      if(questions==null)return;
      for(int i=0;i<questions.length();i++){
        JSONObject q=questions.optJSONObject(i);if(q==null)continue;
        if(questions.length()>1||!q.optString("question").equals(item.optString("summary")))
          pendingPanel.addView(label(q.optString("question"),15));
        EditText input=new EditText(this);input.setTextColor(WHITE);input.setHintTextColor(MUTED);input.setTextSize(15);input.setHint("点选项或输入回答");
        pendingPanel.addView(input);values.put(q.optString("id"),input);
        JSONArray options=q.optJSONArray("options");
        if(options!=null)for(int n=0;n<options.length();n++){
          JSONObject option=options.optJSONObject(n);if(option==null)continue;String text=option.optString("label");
          addControl(pendingPanel,control(text,false,()->{input.setText(text);input.clearFocus();}));
          if(!option.optString("description").isEmpty())pendingPanel.addView(label(option.optString("description"),13));
        }
      }
      addControl(pendingPanel,control("提交回答",true,()->{
        JSONObject answers=new JSONObject();
        try{for(String name:values.keySet()){String text=values.get(name).getText().toString().trim();
          if(text.isEmpty()){hint("请回答全部问题");return;}answers.put(name,text);}
          answer(id,null,answers,thread,turn);
        }catch(Exception e){hint("请检查回答");}
      }));
    }
  }
  void answer(String id,String decision,JSONObject answers,String thread,String turn){
    if(voiceWorking){hint("请先结束录音");return;}
    try{
      JSONObject body=new JSONObject();body.put("id",id);body.put("thread_id",thread);body.put("turn_id",turn);
      if(decision!=null)body.put("decision",decision);if(answers!=null)body.put("answers",answers);
      exchange(()->request(endpoint("/assistant/answer"),"POST",body.toString(),get("token")),state->{applyState(state);hint("请求已处理");});
    }catch(Exception e){hint("回答未发送");}
  }
  void refresh(){
    AssistantMonitor.reconnectSoon();
    if(voiceWorking){hint("请先结束录音");return;}hint("正在检查连接…");
    final int epoch=navigation;
    final String target=viewed;
    query(()->readRequest(url("/assistant/state?compact=1"+(shared()&&!target.isEmpty()&&!target.startsWith(NEW)?"&thread_id="+enc(target):""))),epoch,state->{
      AssistantMonitor.connected=true;AssistantMonitor.lastContact=SystemClock.elapsedRealtime();applyState(state);
      hint("连接已恢复");
      if(attentionMode)loadAttention("");else if(listMode)loadConversations("");else if(!isNew())loadHistory(false);
    });
  }
  void returnToActive(){
    String target=current.optString("thread_id");if(target.isEmpty()){hint("还没有执行中的对话");return;}
    saveDraft();viewed=target;viewTitle=current.optString("title","手表对话");readOnly=false;persistView();showChat(true);
  }
  void menu(){
    if(voiceWorking){hint("请先结束录音并等待识别完成");return;}
    new AlertDialog.Builder(this).setTitle("更多操作").setItems(new String[]{"刷新连接与记录","返回执行中的对话","停止当前任务","电脑控制","设置与提醒"},(d,n)->{
      if(n==0)refresh();if(n==1)returnToActive();
      if(n==2){
        if(!viewingActive()||!running()){hint("请先返回执行中的对话");return;}
        final String thread=current.optString("thread_id"),turn=current.optString("turn_id");
        JSONObject body=new JSONObject();try{body.put("thread_id",thread);body.put("turn_id",turn);}catch(Exception ignored){}
        confirm("停止当前任务？已经完成的操作不会撤销。",()->exchange(
          ()->request(endpoint("/assistant/stop"),"POST",body.toString(),get("token")),this::applyState));
      }
      if(n==3)startActivity(new Intent(this,MainActivity.class).putExtra("controls",true));
      if(n==4)reminderSettings();
    }).show();
  }
  void reminderSettings(){
    boolean reminders=connectionState.optBoolean("desktop_attention_enabled",true)&&!prefs.getBoolean("desktopLocalMute",false);
    new AlertDialog.Builder(this).setTitle("设置与提醒").setItems(new String[]{
      "电脑确认提醒："+(reminders?"开":"关"),"自动弹出确认："+(android.provider.Settings.canDrawOverlays(this)?"已授权":"待授权"),
      "暂停全部主动提醒","连接设置","自定义接口"},(d,n)->{
      if(n==0)toggleDesktopReminders(!reminders);if(n==1)configureAttentionPopup();
      if(n==2){stopService(new Intent(this,AssistantMonitor.class));hint("提醒已暂停，下次打开助手时恢复");}
      if(n==3||n==4)startActivity(new Intent(this,MainActivity.class).putExtra("screen",n==3?"connection":"custom"));
    }).show();
  }
  void configureAttentionPopup(){
    new AlertDialog.Builder(this).setTitle("自动弹出确认")
      .setMessage("需要允许本应用“显示在其他应用上层”，才能在亮屏后打开确认卡片。只在新请求到达时打开，关闭电脑确认提醒后停止。未授权时仍可点通知处理。")
      .setPositiveButton("打开系统授权",(d,w)->{
        try{startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
          android.net.Uri.parse("package:"+getPackageName())));}
        catch(ActivityNotFoundException ex){hint("系统没有授权页面，请在应用权限中允许本应用显示在其他应用上层");}
      }).setNegativeButton("返回",null).show();
  }
  void toggleDesktopReminders(boolean enabled){
    if(!shared()){hint("请先更新电脑服务到 v0.5");return;}
    prefs.edit().putBoolean("desktopLocalMute",!enabled).apply();
    try{
      JSONObject body=new JSONObject();body.put("desktop_attention_enabled",enabled);
      exchange(()->request(endpoint("/assistant/preferences"),"POST",body.toString(),get("token")),state->{
        applyState(state);hint(enabled?(android.provider.Settings.canDrawOverlays(this)?"电脑确认提醒已开启":"提醒已开启；自动弹出请在更多操作中授权"):"电脑确认提醒已关闭，仍可手动处理");
      });
    }catch(Exception e){hint("设置未确认，请联网后重试");}
  }
  void activateViewed(){
    if(voiceWorking||viewed.isEmpty()||isNew())return;
    final String target=viewed;
    try{
      JSONObject body=new JSONObject();body.put("thread_id",target);hint("正在电脑连接此对话…");
      exchange(()->request(endpoint("/assistant/activate"),"POST",body.toString(),get("token")),state->{
        applyState(state);if(target.equals(viewed)&&!listMode)loadHistory(false);
      });
    }catch(Exception e){hint("未能连接此对话");}
  }
  void openAttention(String cursor){
    if(voiceWorking){hint("请先结束录音");return;}
    saveDraft();prompt=null;listMode=true;attentionMode=true;rows.clear();listCursor=cursor;
    shell("待处理");attentionButton.setVisibility(View.GONE);
    list.addHeaderView(control("刷新待处理",false,()->loadAttention("")),null,false);
    listMore=control("加载更多",false,()->{if(!listCursor.isEmpty())loadAttention(listCursor);});
    list.addFooterView(listMore,null,false);adapter=new Rows();list.setAdapter(adapter);
    list.setOnItemClickListener((p,v,pos,id)->{
      int at=pos-list.getHeaderViewsCount();if(at<0||at>=rows.size())return;
      JSONObject item=rows.get(at);viewed=item.optString("thread_id");viewTitle=item.optString("title");readOnly=false;
      persistView();showChat(true);
    });
    root.addView(status);status.setMaxLines(2);loadAttention(cursor);
  }
  void loadAttention(String cursor){
    if(!attentionMode)return;
    attentionPage=cursor;
    final int epoch=navigation;
    query(()->readRequest(url("/assistant/attention"+(cursor.isEmpty()?"":"?cursor="+enc(cursor)))),epoch,data->{
      if(!attentionMode||!attentionPage.equals(cursor))return;
      JSONArray items=data.optJSONArray("items");
      if(!cursor.isEmpty()&&data.optInt("total")>0&&(items==null||items.length()==0)){loadAttention("");return;}
      rows.clear();
      if(items!=null)for(int n=0;n<items.length();n++)rows.add(items.optJSONObject(n));
      listCursor=data.optString("next_cursor","");if(listCursor.equals("null"))listCursor="";
      listMore.setEnabled(!listCursor.isEmpty());listMore.setText(listCursor.isEmpty()?"已到末尾":"加载更多");
      phase.setText(data.optInt("total")+" 项等待处理");adapter.notifyDataSetChanged();list.setSelection(0);
      hint(rows.isEmpty()?"目前没有待确认项":"点对话查看说明和原始选项");
    });
  }
  void openMessage(JSONObject row){
    if(row.optBoolean("live")){showPages(row.optBoolean("complete")?"回复预览":"正在生成",row.optString("text")+(row.optBoolean("clipped")?"\n\n预览已达上限，完成后点开完整记录。":""));return;}
    if(row.optString("id").startsWith("local:")){hint("消息已发送，完成后可查看记录");return;}
    final String thread=viewed,item=row.optString("id");final int epoch=navigation;final int[] page={0},total={1};
    ScrollView box=new ScrollView(this){
      @Override protected void onMeasure(int w,int h){super.onMeasure(w,View.MeasureSpec.makeMeasureSpec(getResources().getDisplayMetrics().heightPixels/2,View.MeasureSpec.AT_MOST));}
    };
    TextView text=label("正在读取…",15);text.setTextIsSelectable(true);box.addView(text);
    AlertDialog dialog=new AlertDialog.Builder(this).setTitle("消息").setView(box)
      .setPositiveButton("关闭",null).setNegativeButton("上一页",null).setNeutralButton("下一页",null).create();dialog.show();
    Runnable fetch=()->{
      dialog.getButton(-2).setEnabled(false);dialog.getButton(-3).setEnabled(false);int selected=page[0];
      query(()->readRequest(url("/assistant/conversations/message?thread_id="+enc(thread)+"&item_id="+enc(item)+"&page="+selected)),epoch,data->{
        if(!dialog.isShowing())return;text.setText(data.optString("text"));total[0]=data.optInt("pages",1);
        dialog.setTitle("消息 "+(page[0]+1)+"/"+total[0]+(row.optBoolean("clipped")?" · 已截取前 2 万字":""));box.scrollTo(0,0);
        dialog.getButton(-2).setEnabled(page[0]>0);dialog.getButton(-3).setEnabled(page[0]+1<total[0]);
      });
    };
    dialog.getButton(-2).setOnClickListener(v->{page[0]--;fetch.run();});
    dialog.getButton(-3).setOnClickListener(v->{page[0]++;fetch.run();});fetch.run();
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
    if(listMode||readOnly||(running()&&!viewingActive())){hint("请先返回可发送的当前对话");return;}
    final String speechTarget=viewed;
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
      speech.execute(()->{
        JSONObject recognized=null;try{recognized=decode(upload(audio));}catch(Exception ignored){}
        final JSONObject result=recognized;
        runOnUiThread(()->{
        voiceWorking=false;if(isDestroyed())return;mic.setText("点一下说话");
        if(!active||discardCapture||!speechTarget.equals(viewed))return;
        if(result==null){hint("语音识别未成功，请重试；草稿已保留");return;}
        if(!prompt.getText().toString().trim().isEmpty()){
          new AlertDialog.Builder(this).setMessage("用识别文字替换当前草稿？")
            .setPositiveButton("替换",(d,w)->prompt.setText(result.optString("text")))
            .setNegativeButton("保留草稿",null).show();
        }else prompt.setText(result.optString("text"));
        hint("已识别，请检查文字再发送");
        });
      });
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
    if(!attention)return;
    if(intent.getBooleanExtra("inbox",false)){openAttention("");return;}
    String target=intent.getStringExtra("thread_id");if(target==null||target.isEmpty())target=current.optString("thread_id");
    if(!target.isEmpty()&&(!target.equals(viewed)||listMode)){
      discardCapture=true;capture.stop();voiceWorking=false;saveDraft();
      viewed=target;viewTitle=target.equals(current.optString("thread_id"))?current.optString("title","手表对话"):"手表对话";
      readOnly=false;persistView();showChat(true);
    }
    if(list!=null)list.post(()->list.setSelection(0));
  }
  @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);applyState(AssistantMonitor.latest);handleAttention(intent);}
  @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){
    super.onRequestPermissionsResult(code,permissions,results);
    if(code==7)hint(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED?"权限已允许，点麦克风开始":"没有录音权限，可以打字");
  }
  @Override protected void onResume(){super.onResume();active=true;if(mic!=null&&!voiceWorking)mic.setText("点一下说话");applyState(AssistantMonitor.latest);
    if(listMode&&rows.isEmpty()){if(attentionMode)loadAttention(attentionPage);else loadConversations(listCursor);}
  }
  @Override protected void onPause(){active=false;navigation++;loadingHistory=false;historyStamp="";cancelRead();discardCapture=true;capture.stop();saveDraft();super.onPause();}
  @Override protected void onStop(){
    if(Build.VERSION.SDK_INT>=27)setTurnScreenOn(false);
    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
    super.onStop();
  }
  @Override public void onBackPressed(){if(listMode)showChat(true);else finish();}
  @Override protected void onDestroy(){active=false;discardCapture=true;capture.stop();cancelRead();controls.shutdown();speech.shutdown();readCanceller.shutdown();ui.removeCallbacksAndMessages(null);unregisterReceiver(receiver);super.onDestroy();}
}
