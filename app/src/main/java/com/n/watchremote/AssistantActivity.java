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
  Button titleBar,mic,sendButton,olderButton,listMore,listPrevious,attentionButton,moreButton;
  UiState.Pager conversationPages=new UiState.Pager();
  boolean archivedList;
  TextView phase;
  JSONObject current=new JSONObject();
  JSONObject connectionState=new JSONObject();
  ArrayList<JSONObject> rows=new ArrayList<>();
  Rows adapter;
  String viewed="",draftOwner="",viewTitle="日常助手",pendingKey="",historyCursor="",listCursor="",historyStamp="";
  boolean active,listMode,attentionMode,readOnly,voiceWorking,discardCapture,loadingHistory;
  String resumeHistoryThread="";
  boolean historyReadOlder,historyReadAutomatic,resumeHistoryOlder,resumeHistoryAutomatic;
  String attentionStamp="";
  String attentionPage="";
  String automaticHint="";
  String recoveryKey="";
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
  final ExecutorService storage=Executors.newSingleThreadExecutor();
  final String cacheSession=UUID.randomUUID().toString();
  ConversationCache conversationCache;
  final ConversationCache sessionCache=new ConversationCache(null);
  String sessionMain="",sessionMainTitle="";
  boolean mainPrompted;
  String connectionScope="",connectionIdentity="",temporaryReturn="",temporaryTitle="";
  boolean temporaryNavigation,temporaryReadonly;
  boolean temporaryList,temporaryArchived,temporaryAttention;
  UiState.Pager temporaryPager;
  String temporaryListCursor="",temporaryAnchor="",offlineListHint="";
  int temporaryPosition,temporaryOffset;
  int listRequest;
  String mainIssueKey="";
  String messageRowsOwner="";
  UiState.AttentionPage attentionPageState=new UiState.AttentionPage("","",0),temporaryAttentionState;
  ArrayList<JSONObject> temporaryRows;
  Button modelButton,stopButton,mainListButton;
  VoiceCapture capture=new VoiceCapture();
  CloudSpeech cloudSpeech;
  int cloudEpoch;
  VoiceDraft cloudDraft;
  String cloudPending="";
  boolean cloudRenderScheduled;
  Runnable cloudRender=()->{cloudRenderScheduled=false;renderCloudPreview();};
  Runnable saveDraft=()->saveDraft();
  Runnable showLatestPreview=()->{if(active&&!isDestroyed())updatePreview();};
  BroadcastReceiver receiver=new BroadcastReceiver(){public void onReceive(Context c,Intent i){applyState(AssistantMonitor.latest);}};
  @Override void initialPage(){}
  @Override public void onCreate(Bundle state){
    super.onCreate(state);active=true;viewed=get("viewedConversation");viewTitle=get("viewedTitle");
    conversationCache=new ConversationCache(new File(getCacheDir(),"conversation-list-v1"));
    connectionScope=scopeForState(AssistantMonitor.latest);
    connectionIdentity=identityForState(AssistantMonitor.latest);
    if(!connectionIdentity.isEmpty())restoreVerifiedView();
    if(viewTitle.isEmpty())viewTitle="日常助手";if(connectionIdentity.isEmpty())readOnly=prefs.getBoolean("viewedReadonly",false);
    registerReceiver(receiver,new IntentFilter(AssistantMonitor.UPDATE));
    showChat(true);applyState(AssistantMonitor.latest);handleAttention(getIntent());
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
  LinearLayout buttonContainer(Button button){
    // ListView disables nonselectable header/footer roots. Keep actions as children.
    LinearLayout container=new LinearLayout(this);container.addView(button,new LinearLayout.LayoutParams(-1,-2));return container;
  }
  void addButtonHeader(Button button){list.addHeaderView(buttonContainer(button),null,false);}
  TextView label(String value,int size){
    TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(WHITE);
    t.setPadding(dp(4),dp(4),dp(4),dp(4));return t;
  }
  void shell(String title){
    decisionView.cancel(false);navigation++;ui.removeCallbacks(showLatestPreview);liveReply.clear();cancelRead();root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
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
    modelButton=control("模型",false,this::threadSettings);modelButton.setTextSize(12);modelButton.setVisibility(UiState.chatShortcuts(listMode,attentionMode)?View.VISIBLE:View.GONE);
    stateRow.addView(modelButton,new LinearLayout.LayoutParams(dp(82),dp(48)));
    stopButton=control("停止",false,this::stopCurrent);stopButton.setVisibility(View.GONE);
    stateRow.addView(stopButton,new LinearLayout.LayoutParams(dp(64),dp(48)));
    attentionButton=control("待处理",false,()->openAttention(""));attentionButton.setVisibility(View.GONE);
    stateRow.addView(attentionButton,new LinearLayout.LayoutParams(dp(94),dp(48)));root.addView(stateRow);
    status=label("",12);
    list=new ConversationList(this);list.setBackgroundColor(Color.BLACK);list.setDivider(null);
    list.setCacheColorHint(Color.BLACK);list.setScrollingCacheEnabled(false);list.setClipToPadding(true);
    list.setOnScrollListener(new AbsListView.OnScrollListener(){
      public void onScrollStateChanged(AbsListView v,int state){dragging=state!=SCROLL_STATE_IDLE;if(!dragging&&!listMode&&historyWindow.deferred)updateChat();}
      public void onScroll(AbsListView v,int first,int count,int total){}
    });
    root.addView(list,new LinearLayout.LayoutParams(-1,0,1));
  }
  String recoveryNamespace(){return "recovery-v2:"+ConversationState.recoveryScope(get("base"),connectionIdentity)+":";}
  String draftStorage(String thread){return recoveryNamespace()+"draft:"+thread;}
  String connectionKey(String key){return recoveryNamespace()+key;}
  String pendingStorage(String thread){return connectionKey("pending:"+thread);}
  String operationStorage(String thread,String action){return connectionKey("operation:"+thread+":"+action);}
  String scopeForState(JSONObject state){
    String identity=identityForState(state);
    String generation=state.optString("connection_scope","");
    return ConversationState.scope(get("base"),identity,generation.isEmpty()?cacheSession:generation);
  }
  String identityForState(JSONObject state){String value=state.optBoolean("connection_identity_verified")?state.optString("connection_identity",""):"";return value.equals("null")?"":value;}
  void restoreVerifiedView(){
    String normal=get("normal-v1:"+connectionScope);
    viewed=ConversationState.initialTarget(connectionIdentity,normal,get("main-v1:"+connectionScope),"");
    viewTitle=get((normal.isEmpty()?"main-title-v1:":"normal-title-v1:")+connectionScope);
    readOnly=prefs.getBoolean("normal-readonly-v1:"+connectionScope,false);
  }
  String draftKey(){return draftStorage(viewed);}
  void saveDraft(){
    ui.removeCallbacks(saveDraft);
    if(prompt!=null&&!listMode){String key=draftStorage(draftOwner);android.content.SharedPreferences.Editor edit=prefs.edit().putString(key,prompt.getText().toString());if(!prefs.contains("owner:"+key))edit.putString("owner:"+key,connectionScope);edit.apply();}
  }
  void persistView(){
    if(!temporaryNavigation)prefs.edit().putString("viewedConversation",viewed).putString("viewedTitle",viewTitle).putBoolean("viewedReadonly",readOnly)
      .putString("normal-v1:"+connectionScope,viewed).putString("normal-title-v1:"+connectionScope,viewTitle).putBoolean("normal-readonly-v1:"+connectionScope,readOnly).apply();
    AssistantMonitor.selectView(viewed);
  }
  void rememberPosition(){
    if(list!=null&&UiState.canRememberAnchor(viewed,messageRowsOwner,listMode)){
      View first=list.getChildAt(0);prefs.edit().putString("anchor-v1:"+connectionScope+":"+viewed,anchorId())
        .putInt("offset-v1:"+connectionScope+":"+viewed,first==null?0:first.getTop()).apply();
    }
  }
  void showChat(boolean load){
    if(!temporaryNavigation)decisionView.cancel(true);saveDraft();historyWindow.loaded(false);listMode=false;attentionMode=false;loadingHistory=false;pendingKey="";recoveryKey="";historyStamp="";historyCursor="";rows.clear();
    messageRowsOwner=viewed;
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
    String draft=get(draftKey());
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
    if(hasRecoveryRecords())hint("更多 → 保留记录可找回旧草稿、核对未确认送达操作");
  }
  boolean isNew(){return viewed.startsWith(NEW);}
  boolean shared(){return connectionState.optInt("protocol_version")>=5;}
  boolean running(){String s=current.optString("state");return s.equals("starting")||s.equals("running")||s.equals("waiting");}
  boolean canSubmit(){return UiState.canSubmit(shared(),isNew()||viewed.isEmpty(),running(),current.optBoolean("can_send"),current.optBoolean("can_steer"));}
  boolean viewingActive(){
    boolean creating=!get(connectionKey("followRequestId")).isEmpty()&&get(connectionKey("followRequestId")).equals(current.optString("request_id"))&&viewed.equals(get(connectionKey("followDraft")));
    return (!viewed.isEmpty()&&viewed.equals(current.optString("thread_id")))||creating;
  }
  void hint(String value){if(status!=null&&!status.getText().toString().equals(value))status.setText(value);}
  void applyState(JSONObject state){
    if(state.length()==0||!active)return;
    String identity=identityForState(state);
    String scope=scopeForState(state);
    if(scope.equals(connectionScope)&&state.optLong("revision",-1)<connectionState.optLong("revision",-1))return;
    boolean scopeChanged=!scope.equals(connectionScope),previousKnown=!connectionIdentity.isEmpty();
    if(scopeChanged){
      decisionView.cancel(true);
      cancelCloudVoice();discardCapture=true;capture.stop();voiceWorking=false;saveDraft();prompt=null;rows.clear();
      sessionMain="";sessionMainTitle="";connectionScope=scope;connectionIdentity=identity;conversationPages=new UiState.Pager();
      if(connectionState.length()>0){viewed="";current=new JSONObject();rows.clear();temporaryNavigation=false;temporaryReturn="";}
      if(!identity.isEmpty()){
        restoreVerifiedView();
      }
    }
    connectionState=state;
    if(!active)return;
    String target=state.optString("thread_id");
    String follow=get(connectionKey("followRequestId")),followView=get(connectionKey("followDraft"));
    if(!target.isEmpty()&&(!follow.isEmpty()&&follow.equals(state.optString("request_id")))){
      try{
        JSONObject sent=new JSONObject(get(pendingStorage(followView)));
        if(follow.equals(sent.optString("request_id")))acknowledgeDraft(followView,target,sent.optString("prompt"),follow);
      }catch(JSONException ignored){}
      if(viewed.equals(followView)||viewed.isEmpty())bindCreated(target,state.optString("title","手表对话"));
      prefs.edit().remove(connectionKey("followRequestId")).remove(connectionKey("followDraft")).apply();
    }else if(viewed.isEmpty()&&!target.isEmpty()&&!scopeChanged){
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
    if(scopeChanged){if(listMode&&!attentionMode)openList(archivedList);else if(!listMode)showChat(true);}
    if(!mainPrompted&&!temporaryNavigation){mainPrompted=true;if(mainTarget().isEmpty())hint("可在更多 → 主对话设置中绑定现有对话，也可稍后设置");}
  }
  void acknowledgeDraft(String origin,String accepted,String value,String requestId){
    boolean here=viewed.equals(origin)||(!accepted.isEmpty()&&viewed.equals(accepted));
    if(here&&!listMode)saveDraft();
    for(String target:new String[]{origin,accepted}){
      if(!target.isEmpty()&&ConversationState.sameDraft(get(draftStorage(target)),value))prefs.edit().putString(draftStorage(target),"").apply();
    }
    try{if(requestId.equals(new JSONObject(get(pendingStorage(origin))).optString("request_id")))
      prefs.edit().remove(pendingStorage(origin)).apply();}catch(JSONException ignored){}
    if(here&&!listMode&&prompt!=null&&ConversationState.sameDraft(prompt.getText().toString(),value)){
      prompt.setText("");prompt.clearFocus();
      ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(prompt.getWindowToken(),0);
    }
  }
  void bindCreated(String target,String title){
    if(!target.equals(viewed))rememberPosition();
    if(!listMode)saveDraft();
    String draft=get(draftStorage(viewed));
    String old=viewed;
    viewed=target;viewTitle=title;readOnly=false;
    if(!draft.isEmpty()&&get(draftKey()).isEmpty())prefs.edit().putString(draftKey(),draft).apply();
    if(old.equals(get("newDraft")))prefs.edit().remove("newDraft").apply();
    persistView();
    if(!listMode){messageRowsOwner=viewed;draftOwner=viewed;prompt.setText(get(draftKey()));titleBar.setText(viewTitle+" ▾");}
  }
  void updateChat(){
    if(listMode||phase==null)return;
    String connection=AssistantMonitor.fresh()?"ONLINE":"OFFLINE";
    String stage=isNew()?"新对话":shared()?current.optString("message","正在读取对话"):readOnly?"电脑记录 · 只读":viewingActive()?current.optString("message","就绪"):"就绪";
    if(shared()&&!connectionState.optBoolean("desktop_connected"))stage="电脑客户端未连接";
    String text=connection+" · "+stage;
    if(!phase.getText().toString().equals(text))phase.setText(text);
    boolean executing=viewingActive()&&running();
    modelButton.setVisibility(executing?View.GONE:View.VISIBLE);modelButton.setEnabled(!busy);
    String model=current.isNull("model")?"模型":current.optString("model","模型");if(model.isEmpty())model="模型";modelButton.setText(model.length()>12?model.substring(0,12):model);
    stopButton.setVisibility(executing?View.VISIBLE:View.GONE);stopButton.setEnabled(!busy);
    JSONArray pending=viewingActive()?current.optJSONArray("pending"):null;
    JSONObject item=pending==null?null:pending.optJSONObject(0);
    String key=item==null?"":viewed+":"+item.optString("id")+":"+item.optString("version")+":"+item.optString("availability_version")+":"+item.optBoolean("supported",true)+":"+item.optString("delivery_status");
    if(!key.equals(pendingKey)){
      pendingKey=key;pendingPanel.removeAllViews();if(item!=null){showPending(item);list.setSelection(0);}
    }
    if(item==null){
      String failure=current.optString("state").equals("failed")?viewed+":"+current.optString("turn_id"):"";
      if(!failure.equals(recoveryKey)){
        recoveryKey=failure;pendingPanel.removeAllViews();
        if(!failure.isEmpty()){
          pendingPanel.addView(label("任务未完成，需要你决定下一步",15));
          addControl(pendingPanel,control("输入后续处理意见",false,()->{if(prompt!=null){prompt.requestFocus();((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(prompt,InputMethodManager.SHOW_IMPLICIT);}}));
          addControl(pendingPanel,control("在电脑查看",false,()->openOnDesktop(viewed)));
        }
      }
    }else recoveryKey="";
    int count=connectionState.optInt("attention_count");
    attentionButton.setVisibility(shared()&&count>0?View.VISIBLE:View.INVISIBLE);setText(attentionButton,"待办 "+count);
    boolean blocked=!shared()&&running()&&!viewingActive();
    boolean cannotSteer=shared()&&running()&&!current.optBoolean("can_steer");
    boolean cannotSend=!canSubmit();
    boolean cloudRecording=cloudDraft!=null;
    composer.setVisibility(!cloudRecording&&(readOnly||item!=null||blocked)?View.GONE:View.VISIBLE);
    sendButton.setEnabled(!busy&&!blocked&&!cannotSend);sendButton.setAlpha(busy||blocked||cannotSend?0.45f:1f);
    mic.setEnabled(cloudRecording||!blocked);
    boolean activation=shared()&&readOnly&&!viewed.isEmpty()&&!isNew()&&connectionState.optBoolean("desktop_connected");
    boolean returnLink=!shared()&&(blocked||readOnly)&&!current.optString("thread_id").isEmpty();
    status.setClickable(returnLink||activation);status.setMinimumHeight(returnLink||activation?dp(48):0);status.setTextColor(returnLink||activation?GREEN:MUTED);
    status.setOnClickListener(v->{if(activation)activateViewed();else if(returnLink)returnToActive();});
    status.setClickable(returnLink||activation);
    String nextHint=blocked?"另一段对话正在执行 · 点此返回":activation?"点此连接电脑中的这段对话":readOnly?
      (shared()?"请先打开电脑客户端；草稿已保留":returnLink?"电脑记录只读 · 点此返回手表对话":"电脑记录当前只读"):
      cannotSteer&&item==null?current.optString("steer_unavailable_reason","请等本轮结束后发送；草稿会保留"):
      cannotSend&&item==null?"电脑正在同步任务状态 · 草稿已保留":"";
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
    query(work,expected,done,null);
  }
  void query(Work work,int expected,Consumer<JSONObject> done,Consumer<Exception> failed){
    worker.execute(()->{
      if(expected!=navigation)return;
      try{
        JSONObject result=decode(work.run());
        runOnUiThread(()->{if(!isDestroyed()&&active&&expected==navigation)done.accept(result);});
      }catch(Exception e){runOnUiThread(()->{if(!isDestroyed()&&active&&expected==navigation){loadingHistory=false;if(failed!=null){failed.accept(e);return;}hint("读取失败，可在更多中刷新");}});}
    });
  }
  void cancelRead(){
    final HttpURLConnection old=readConnection;readConnection=null;
    if(old!=null&&!readCanceller.isShutdown())readCanceller.execute(old::disconnect);
  }
  String readRequest(String address)throws Exception{
    try{return bridgeRequest(address,"GET","","",c->readConnection=c);}
    finally{readConnection=null;}
  }
  static final class RequestFailure extends IOException{
    JSONObject decisionResult;
    final boolean notApplied;
    RequestFailure(String message,boolean known){super(message);notApplied=known;}
  }
  static final class ReadFailure extends IOException{final int code;ReadFailure(String message,int code){super(message);this.code=code;}}
  JSONObject decode(String raw)throws Exception{
    int at=raw.indexOf('\n');
    if(!(raw.startsWith("HTTP 200\n")||raw.startsWith("HTTP 202\n"))){
      String body=at<0?raw:raw.substring(at+1);
      JSONObject failure=null;try{failure=new JSONObject(body);}catch(JSONException ignored){}
      if(failure!=null&&failure.optJSONObject("decision_result")!=null){
        JSONObject status=failure.optJSONObject("decision_result");String delivery=status.optString("delivery_status");RequestFailure error=new RequestFailure(failure.optString("error","回答结果未确认"),delivery.equals("not_sent")||delivery.equals("rejected"));error.decisionResult=status;throw error;
      }
      if(failure!=null&&failure.has("delivery_status")){
        String delivery=failure.optString("delivery_status");
        throw new RequestFailure(failure.optString("error",body),delivery.equals("not_sent")||delivery.equals("rejected"));
      }
      int code=0;try{code=Integer.parseInt(raw.substring(5,8));}catch(Exception ignored){}
      throw new ReadFailure(body,code);
    }
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
    if(older&&cursor.isEmpty())return;loadingHistory=true;historyReadOlder=older;historyReadAutomatic=automatic;
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
      else if(empty&&!get("anchor-v1:"+connectionScope+":"+target).isEmpty())restoreAnchor(get("anchor-v1:"+connectionScope+":"+target),0,prefs.getInt("offset-v1:"+connectionScope+":"+target,0));
      else if(!rows.isEmpty()&&(follow||empty))list.setSelection(rows.size()+list.getHeaderViewsCount()-1);
      else restoreAnchor(anchor,first,offset);
      if(status.getText().toString().startsWith("读取失败")||(!older&&status.getText().toString().startsWith("新回复已完成")))hint("");
      if(rows.isEmpty())hint(readOnly?"暂无文字记录，可加载更早消息":"说一句，开始这段对话");
      if(target.equals(mainTarget())&&meta!=null&&(meta.optBoolean("archived")||readOnly))mainUnavailable(target,meta.optBoolean("archived")?"主对话已归档，可在已归档列表恢复或重新绑定。":"电脑当前将主对话设为只读，请在电脑核对，或重新绑定。");
    },error->{
      loadingHistory=false;
      if(target.equals(mainTarget())){String reason=error.getMessage()==null?"":error.getMessage();String lower=reason.toLowerCase(Locale.ROOT);boolean missing=error instanceof ReadFailure&&((ReadFailure)error).code==404||lower.contains("not found")||lower.contains("does not exist")||reason.contains("不存在")||reason.contains("已删除");mainUnavailable(target,missing?"电脑找不到绑定的主对话，可能已删除或当前账号无权读取。":"暂时无法读取主对话，请检查电脑连接并刷新。未将它判定为已删除。");}
      else hint("读取失败，草稿和当前位置已保留，可刷新重试");
    });
  }
  void mainUnavailable(String target,String reason){
    hint(reason+" 主对话编号保留，可在更多中重绑。");String key=connectionScope+":"+target+":"+reason;if(key.equals(mainIssueKey))return;mainIssueKey=key;
    showDialog(new AlertDialog.Builder(this).setTitle("主对话暂不可用").setMessage(reason+"\n绑定编号保持不变。打开其他对话后，在更多 → 主对话设置中重新绑定。")
      .setPositiveButton("选择其他对话",(d,n)->openList()).setNegativeButton("保留绑定",null).create());
  }
  void openList(){
    openList(false);
  }
  void openList(boolean archived){
    if(voiceWorking){hint("请先结束录音并等待识别完成");return;}
    rememberPosition();archivedList=archived;conversationPages=new UiState.Pager();
    decisionView.cancel(true);saveDraft();prompt=null;listMode=true;attentionMode=false;loadingHistory=false;rows.clear();listCursor="";
    shell(archived?"已归档对话":"对话列表");
    Button create=control(archived?"返回最近对话":"＋ 新建对话",false,archived?this::openList:this::newConversation);
    addButtonHeader(create);
    String primary=mainTarget();mainListButton=null;
    if(!archived&&!primary.isEmpty()){
      Button main=control("主对话 · "+(connectionIdentity.isEmpty()?sessionMainTitle:get("main-title-v1:"+connectionScope)),false,()->{
        rememberPosition();saveDraft();viewed=primary;viewTitle=connectionIdentity.isEmpty()?sessionMainTitle:get("main-title-v1:"+connectionScope);persistView();showChat(true);
      });mainListButton=main;addButtonHeader(main);
    }
    LinearLayout footer=new LinearLayout(this);footer.setOrientation(LinearLayout.VERTICAL);
    LinearLayout pageRow=new LinearLayout(this);pageRow.setOrientation(LinearLayout.HORIZONTAL);
    listPrevious=control("上一页",false,()->flipConversations(-1));listMore=control("下一页",false,()->flipConversations(1));
    pageRow.addView(listPrevious,new LinearLayout.LayoutParams(0,dp(48),1));
    LinearLayout.LayoutParams nextLp=new LinearLayout.LayoutParams(0,dp(48),1);nextLp.setMargins(dp(6),0,0,0);pageRow.addView(listMore,nextLp);
    footer.addView(pageRow);
    Button first=control(archived?"返回归档首页":"返回最近对话",false,()->loadConversations(""));addControl(footer,first);list.addFooterView(footer,null,false);
    adapter=new Rows();list.setAdapter(adapter);
    list.setOnItemClickListener((p,v,pos,id)->{
      int at=pos-list.getHeaderViewsCount();if(at<0||at>=rows.size())return;
      JSONObject item=rows.get(at);
      if(archivedList){conversationManagement(item.optString("id"),true);return;}
      viewed=item.optString("id");viewTitle=item.optString("title");readOnly=item.optBoolean("readonly",true);persistView();showChat(true);
    });
    root.addView(status);status.setMaxLines(2);loadConversations("");
  }
  void loadConversations(String cursor){
    if(!cursor.isEmpty())return;
    String target=conversationPages.first();if(target!=null)readConversationPage(target);
  }
  void flipConversations(int direction){
    if(!listMode||attentionMode)return;
    String target=conversationPages.begin(direction);
    if(target!=null)readConversationPage(target);
    else if(!conversationPages.loading)hint(direction<0?"已经是第一页":"已经是最后一页");
  }
  void updatePageControls(){
    if(!listMode||attentionMode||listPrevious==null||listMore==null)return;
    listPrevious.setEnabled(conversationPages.canPrevious());listMore.setEnabled(conversationPages.canNext());
    if(mainListButton!=null)mainListButton.setVisibility(conversationPages.page==0&&conversationPages.cursors.get(0).isEmpty()?View.VISIBLE:View.GONE);
    String pageLabel=conversationPages.cursors.get(0).isEmpty()?"第 "+(conversationPages.page+1)+" 页":"缓存页";
    setText(phase,pageLabel+" · "+(conversationPages.loading?"读取中":"左右滑动可翻页"));
  }
  void readConversationPage(String cursor){
    final int epoch=navigation,request=++listRequest;updatePageControls();
    final boolean archived=archivedList;final String scope=connectionScope;
    final ConversationCache cache=connectionIdentity.isEmpty()?sessionCache:conversationCache;
    storage.execute(()->{
      ConversationCache.Page found=cache.get(scope,archived,cursor);
      if(found==null&&cursor.isEmpty())found=cache.fallback(scope,archived);
      final ConversationCache.Page cached=found;
      runOnUiThread(()->{
        if(epoch!=navigation||request!=listRequest||!scope.equals(connectionScope)||!active)return;
        if(cached!=null){
          if(!cached.cursor.equals(cursor))conversationPages=UiState.Pager.cached(cached.cursor,cached.next);
          else conversationPages.success(cached.next);
          renderCachedPage(cached);listCursor=cached.next;updatePageControls();
          offlineListHint="离线记录 · 更新于 "+android.text.format.DateFormat.format("MM-dd HH:mm",cached.updated);
          hint(AssistantMonitor.fresh()?offlineListHint.replace("离线记录","缓存记录"):offlineListHint);
        }
        refreshConversationPage(cursor,epoch,archived,scope,request);
      });
    });
  }
  void renderCachedPage(ConversationCache.Page page){
    rows.clear();for(ConversationCache.Item item:page.items)try{rows.add(new JSONObject().put("id",item.id).put("title",item.title).put("readonly",item.readonly).put("cached_metadata",true));}catch(JSONException ignored){}
    removeDuplicateMain(page.cursor);adapter.notifyDataSetChanged();list.setSelection(0);
  }
  String mainTarget(){return connectionIdentity.isEmpty()?sessionMain:get("main-v1:"+connectionScope);}
  void removeDuplicateMain(String cursor){if(!archivedList&&cursor.isEmpty()&&!mainTarget().isEmpty())rows.removeIf(row->mainTarget().equals(row.optString("id")));}
  void refreshConversationPage(String cursor,int epoch,boolean archived,String scope,int request){
    query(()->readRequest(url("/assistant/conversations?archived="+(archived?"1":"0")+(cursor.isEmpty()?"":"&cursor="+enc(cursor)))),epoch,data->{
      if(request!=listRequest)return;
      JSONObject state=data.optJSONObject("active");
      if(state!=null){applyState(state);if(epoch!=navigation||!scope.equals(connectionScope))return;}
      String anchor=anchorId();int first=list.getFirstVisiblePosition();View child=list.getChildAt(0);int offset=child==null?0:child.getTop();
      rows.clear();JSONArray items=data.optJSONArray("conversations");if(items!=null)for(int i=0;i<Math.min(20,items.length());i++){JSONObject item=items.optJSONObject(i);if(item!=null)rows.add(item);}
      listCursor=data.optString("next_cursor","");if(listCursor.equals("null"))listCursor="";
      ArrayList<ConversationCache.Item> metadata=new ArrayList<>();for(JSONObject row:rows)metadata.add(new ConversationCache.Item(row.optString("id"),row.optString("title"),row.optBoolean("readonly",true)));
      final ConversationCache cache=connectionIdentity.isEmpty()?sessionCache:conversationCache;
      String next=listCursor;storage.execute(()->{try{cache.put(scope,archived,cursor,next,metadata,System.currentTimeMillis());}catch(IOException ignored){}});
      removeDuplicateMain(cursor);
      if(cursor.isEmpty()&&!conversationPages.cursors.get(0).isEmpty()){conversationPages=new UiState.Pager();conversationPages.first();}
      conversationPages.refresh(listCursor);offlineListHint="";updatePageControls();
      adapter.notifyDataSetChanged();if(anchor.isEmpty())list.setSelection(0);else restoreAnchor(anchor,first,offset);hint(listCursor.isEmpty()?"已到列表末尾":"每页 20 段 · 点标题返回聊天");
    },error->{if(request==listRequest){conversationPages.failed();updatePageControls();hint(offlineListHint.isEmpty()?"读取失败，当前页保留，可刷新重试":offlineListHint);}});
  }
  final class ConversationList extends ListView{
    float startX,startY;boolean horizontal,vertical,eligible;
    final int slop=ViewConfiguration.get(AssistantActivity.this).getScaledTouchSlop();
    ConversationList(Context context){super(context);}
    @Override public boolean dispatchTouchEvent(MotionEvent event){
      if(!listMode||attentionMode)return super.dispatchTouchEvent(event);
      int action=event.getActionMasked();
      if(action==MotionEvent.ACTION_DOWN){startX=event.getRawX();startY=event.getRawY();horizontal=false;vertical=false;
        int row=pointToPosition((int)event.getX(),(int)event.getY());
        eligible=startX>=dp(24)&&startX<=getResources().getDisplayMetrics().widthPixels-dp(24)&&row>=getHeaderViewsCount()&&row<getCount()-getFooterViewsCount();}
      float dx=event.getRawX()-startX,dy=event.getRawY()-startY;
      if(action==MotionEvent.ACTION_MOVE&&eligible&&!horizontal&&!vertical){
        if(Math.abs(dy)>slop&&Math.abs(dy)*1.5f>=Math.abs(dx))vertical=true;
        else if(Math.abs(dx)>slop&&Math.abs(dx)>Math.abs(dy)*1.5f){horizontal=true;
          MotionEvent cancel=MotionEvent.obtain(event);cancel.setAction(MotionEvent.ACTION_CANCEL);super.dispatchTouchEvent(cancel);cancel.recycle();}
      }
      if(horizontal){
        if(action==MotionEvent.ACTION_UP){float density=getResources().getDisplayMetrics().density;
          int direction=UiState.swipePage(dx/density,dy/density,startX/density,getResources().getDisplayMetrics().widthPixels/density);
          horizontal=false;if(direction!=0)flipConversations(direction);}
        else if(action==MotionEvent.ACTION_CANCEL)horizontal=false;
        return true;
      }
      return super.dispatchTouchEvent(event);
    }
  }
  void newConversation(){
    if(!shared()&&running()){hint("请等当前任务结束，或返回后停止任务");return;}
    rememberPosition();saveDraft();
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
          UiState.rowSource(row.optBoolean("cached_metadata"),row.optBoolean("readonly"),row.optString("source"))+(row.optString("id").equals(viewed)?" · 当前":""));
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
    if(!active||listMode||!viewingActive()||historyWindow.olderWindow)return;
    JSONObject preview=current.optJSONObject("reply_preview");
    if(preview==null){
      ui.removeCallbacks(showLatestPreview);
      if(current.optBoolean("readonly"))return;
      boolean removed=rows.removeIf(row->row.optBoolean("live")&&!row.optBoolean("complete"));
      liveReply.clear();if(removed)adapter.notifyDataSetChanged();return;
    }
    long now=SystemClock.elapsedRealtime();
    long delay=liveReply.renderDelay(preview.optString("thread_id"),preview.optString("turn_id"),
      preview.optString("item_id"),preview.optLong("version",-1),preview.optBoolean("complete"),now);
    if(delay>0){ui.removeCallbacks(showLatestPreview);ui.postDelayed(showLatestPreview,delay);return;}
    ui.removeCallbacks(showLatestPreview);
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
      liveReply.rendered(now);
      if(!measuredRequest.isEmpty()&&current.optString("request_id").equals(measuredRequest)){
        if(firstShownAt==0){firstShownAt=SystemClock.elapsedRealtime();android.util.Log.i("WristCodexTiming","request="+measuredRequest+" first_display_ms="+(firstShownAt-submittedAt));}
        if(liveReply.complete&&!measuredTurn.equals(liveReply.turnId)){measuredTurn=liveReply.turnId;android.util.Log.i("WristCodexTiming","request="+measuredRequest+" complete_display_ms="+(SystemClock.elapsedRealtime()-submittedAt));}
      }
    }catch(JSONException ignored){}
  }

  void exchange(Work work,Consumer<JSONObject> done){
    exchange(work,done,null);
  }
  void exchange(Work work,Consumer<JSONObject> done,Consumer<Exception> failed){
    if(busy){hint("正在提交，请稍候");return;}
    final String operationScope=connectionScope;busy=true;if(sendButton!=null)sendButton.setEnabled(false);
    controls.execute(()->{
      JSONObject value=null;Exception error=null;
      try{value=decode(work.run());}catch(Exception e){error=e;}
      final JSONObject result=value;final Exception failure=error;
      runOnUiThread(()->{
        busy=false;
        if(!operationScope.equals(connectionScope))return;
        if(failure!=null){if(failed!=null)failed.accept(failure);if(!isDestroyed()){
          String message=failure.getMessage()==null?"连接失败":failure.getMessage();
          hint((failure instanceof RequestFailure&&((RequestFailure)failure).notApplied?"未执行：":"未确认送达：")+message.substring(0,Math.min(message.length(),140)));
          for(JSONObject row:rows)if(" · 发送中".equals(row.optString("delivery")))try{row.put("delivery"," · 未确认送达");}catch(JSONException ignored){}
          if(adapter!=null)adapter.notifyDataSetChanged();
        }}
        else if(!isDestroyed())done.accept(result);
        if(!isDestroyed()&&active&&!listMode)updateChat();
      });
    });
  }
  void send(){
    if(busy){hint("正在提交，请稍候");return;}
    if(blockedRecovery(viewed,"pending")){hint("有未核对的送达记录，请先在更多 → 保留记录中核对，避免重复发送");return;}
    if(connectionIdentity.isEmpty()&&!get(draftKey()).isEmpty()&&!connectionScope.equals(get("owner:"+draftKey()))){
      String scope=connectionScope,target=viewed;
      confirm("无法验证当前账号。确认将这份保留草稿用于当前连接的此对话？原送达记录不会重发。",()->{if(scope.equals(connectionScope)&&target.equals(viewed)){prefs.edit().putString("owner:"+draftKey(),connectionScope).apply();hint("草稿已关联，请检查文字后再次发送");}});return;
    }
    if(readOnly){hint("电脑对话当前只读");return;}
    if(voiceWorking){hint("请先结束录音并检查文字");return;}
    if(!AssistantMonitor.fresh()){refresh();return;}
    if(connectionState.optInt("protocol_version")<4){hint("请先更新电脑服务");return;}
    if(shared()&&!connectionState.optBoolean("desktop_connected")){hint("电脑客户端未连接，草稿已保留");return;}
    if(shared()&&running()&&!current.optBoolean("can_steer")){hint(current.optString("steer_unavailable_reason","请等本轮结束"));return;}
    if(!canSubmit()){hint("电脑正在同步任务状态 · 草稿已保留");return;}
    if(running()&&!viewingActive()){hint("请等另一段对话执行结束");return;}
    if(running()&&current.optString("turn_id").isEmpty()){hint("正在建立任务，稍后可补充");return;}
    String value=prompt.getText().toString();
    if(value.trim().isEmpty()||value.length()>4000){hint("请输入 1–4000 字符");return;}
    if(!pendingKey.isEmpty()){hint("请先处理确认选项");list.setSelection(0);return;}
    final String origin=viewed,draft=draftKey(),pending=pendingStorage(origin),originScope=connectionScope;
    try{
      JSONObject payload=null;
      try{JSONObject saved=new JSONObject(get(pending));if((!connectionIdentity.isEmpty()||connectionScope.equals(get("owner:"+pending)))&&value.equals(saved.optString("prompt")))payload=saved;}catch(Exception ignored){}
      if(payload==null){
        if(running()&&!current.optString("state").equals("running")){hint("正在连接，稍后再补充");return;}
        String kind=running()?"steer":isNew()||viewed.isEmpty()?"new":"continue";
        payload=new JSONObject();payload.put("prompt",value);payload.put("request_id",UUID.randomUUID().toString());
        payload.put("kind",kind);payload.put("new",kind.equals("new"));
        payload.put("thread_id",kind.equals("new")?"":viewed);payload.put("turn_id",current.optString("turn_id"));
      }
      final String body=payload.toString(),kind=payload.getString("kind"),requestId=payload.getString("request_id");
      String previousPending=get(pending);if(!previousPending.isEmpty()&&!previousPending.equals(body))prefs.edit().putString("orphan-receipt-v1:"+ConversationState.hash(pending+previousPending),previousPending).apply();
      prefs.edit().putString(pending,body).putString("owner:"+pending,connectionScope).apply();saveDraft();
      if(kind.equals("new"))prefs.edit().putString(connectionKey("followRequestId"),requestId).putString(connectionKey("followDraft"),origin).apply();
      hint("正在发送…");
      submittedAt=SystemClock.elapsedRealtime();firstShownAt=0;measuredRequest=requestId;measuredTurn="";
      JSONObject local=null;for(JSONObject row:rows)if(row.optString("id").equals("local:"+requestId)){local=row;break;}
      if(local==null){local=new JSONObject();local.put("id","local:"+requestId);local.put("role","user");rows.add(local);}
      local.put("text",value.substring(0,Math.min(value.length(),180)));local.put("delivery"," · 发送中");
      while(rows.size()>30)rows.remove(0);adapter.notifyDataSetChanged();list.setSelection(rows.size()+list.getHeaderViewsCount()-1);
      exchange(()->bridgeRequest(endpoint(kind.equals("steer")?"/assistant/steer":"/assistant/send"),"POST",body,get("token")),state->{
        if(!originScope.equals(connectionScope)){hint("连接身份已变化，原消息状态已保留，请在原连接核对");return;}
        String accepted=state.optString("accepted_thread_id");
        if(accepted.isEmpty()&&requestId.equals(state.optString("request_id")))accepted=state.optString("thread_id");
        boolean stillHere=origin.equals(viewed)||(kind.equals("new")&&!accepted.isEmpty()&&viewed.equals(accepted));
        if(accepted.isEmpty()){hint("送达未知，草稿已保留，请在电脑核对");return;}
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
  String decisionKey(String id){return "decision-v1:"+connectionScope+":"+id;}
  final UiState.DecisionView decisionView=new UiState.DecisionView();
  String decisionStatusPath(JSONObject item,String raw)throws Exception{return "/assistant/answer/status?id="+enc(item.optString("id"))+"&thread_id="+enc(item.optString("thread_id",current.optString("thread_id")))+"&turn_id="+enc(item.optString("turn_id",current.optString("turn_id")))+"&expected_version="+enc(item.optString("version"))+"&connection_scope="+enc(raw);}
  void decisionReadFailed(String message){
    // Rebuild the card after clearing busy; its cached key does not include read state.
    // Existing unknown-delivery markers still keep answer actions blocked.
    pendingKey="";if(active&&!listMode)updateChat();hint(message);
  }
  void verifyDecision(JSONObject item,Consumer<JSONObject> done){
    final String scope=connectionScope,raw=connectionState.optString("connection_scope");final int epoch=navigation;
    final long ticket=decisionView.begin(scope+":"+item.optString("thread_id")+":"+item.optString("turn_id")+":"+item.optString("id")+":"+item.optString("version"));
    query(()->readRequest(url(decisionStatusPath(item,raw))),epoch,result->{
      if(!scope.equals(connectionScope)||!raw.equals(connectionState.optString("connection_scope"))||!decisionView.finish(ticket))return;
      JSONObject status=result.optJSONObject("decision_result");if(status!=null)done.accept(status);else decisionReadFailed("无法核实请求，请在电脑处理");
    },error->{if(decisionView.finish(ticket))decisionReadFailed("无法核实请求，请刷新，不会重发回答");});
  }
  void decisionResult(String key,JSONObject result){
    if(!key.startsWith("decision-v1:"+connectionScope+":"))return;
    if(result==null){hint("结果未知，请核对原请求，不会自动重发");return;}
    String request=result.optString("request_status"),delivery=result.optString("delivery_status");
    if(request.equals("processed")){AssistantMonitor.processed(connectionScope,result.optString("id"));prefs.edit().putString(key,"processed").apply();hint("请求已处理");}
    else if(AttentionLedger.canAct(request,delivery,result.optBoolean("can_submit"))){prefs.edit().remove(key).apply();hint("请求仍待选择；如需重试请重新选择");}
    else {if(get(key).isEmpty())prefs.edit().putString(key,"unverified").apply();hint(request.equals("stale")?"原请求或连接已变化，禁止旧选项":delivery.equals("accepted")?"电脑已接收，等待状态同步":"结果未知或无法核实；请刷新或在电脑处理，不会重发");}
    pendingKey="";if(active&&!listMode)updateChat();AssistantMonitor.reconnectSoon();
  }
  void showPending(JSONObject item){
    if(!decisionView.matches(item.optString("id"),item.optString("version"))){pendingPanel.addView(label("通知原请求已变化，打开待处理列表重新选择",15));return;}
    String guard=decisionKey(item.optString("id"));
    if("processed".equals(get(guard))){pendingPanel.addView(label("原请求已处理，等待电脑状态同步",15));return;}
    if(decisionView.busy||!get(guard).isEmpty()){
      pendingPanel.addView(label(decisionView.busy?"正在核实请求…":"回答正在提交或结果未知",15));
      addControl(pendingPanel,control("核对原请求状态",false,()->verifyDecision(item,status->decisionResult(guard,status))));return;
    }
    final String thread=current.optString("thread_id"),turn=current.optString("turn_id"),id=item.optString("id");
    final String version=item.optString("version");
    pendingPanel.addView(label(current.optString("title",viewTitle),12));
    TextView question=label(item.optString("summary","需要你决定"),16);question.setTextColor(0xFFFFCC75);pendingPanel.addView(question);
    if(item.optString("kind").equals("manual")||!item.optBoolean("supported",true)){
      if(!item.optString("detail").isEmpty())addControl(pendingPanel,control(item.optString("kind").equals("plan")?"查看计划全文":"查看详情",false,()->showPages("详情",item.optString("detail"))));
      pendingPanel.addView(label(item.optString("unavailable_reason","此类操作请在电脑处理"),15));
      if(item.optString("kind").equals("plan")){
        Button execute=control("执行此计划 · 需电脑",false,()->{});execute.setEnabled(false);addControl(pendingPanel,execute);
        Button modify=control("提出修改 · 需电脑",false,()->{});modify.setEnabled(false);addControl(pendingPanel,modify);
      }
      addControl(pendingPanel,control("在电脑打开此对话",false,()->openOnDesktop(thread)));
    }else if(item.optString("kind").equals("approval")){
      addControl(pendingPanel,control("查看操作详情",false,()->showPages("操作详情",item.optString("detail"))));
      JSONArray decisions=item.optJSONArray("decisions");
      if(decisions!=null){
        boolean needsComputer=false;
        for(int n=0;n<decisions.length();n++){
          JSONObject option=decisions.optJSONObject(n);if(option==null)continue;
          String decision=option.optString("value");
          if(!option.optString("detail").isEmpty())addControl(pendingPanel,control("查看规则详情",false,()->showPages("规则详情",option.optString("detail"))));
          Button choice=control(option.optString("label"),decision.equals("accept"),()->answer(id,decision,null,null,thread,turn,version));
          if(!option.optBoolean("supported",true)){
            choice.setEnabled(false);choice.setTextColor(MUTED);needsComputer=true;
            pendingPanel.addView(label(option.optString("unavailable_reason","此授权范围请在电脑处理"),12));
          }
          addControl(pendingPanel,choice);
        }
        if(needsComputer)addControl(pendingPanel,control("在电脑打开此对话",false,()->openOnDesktop(thread)));
      }else{
        addControl(pendingPanel,control("仅本次允许",true,()->answer(id,"accept",null,null,thread,turn,version)));
        addControl(pendingPanel,control("拒绝",false,()->answer(id,"decline",null,null,thread,turn,version)));
      }
    }else if(item.optString("kind").equals("permissions")){
      showPermissionFields(item,id,thread,turn,version);
    }else if(item.optString("kind").equals("form")){
      showFormFields(item,id,thread,turn,version);
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
        try{for(String name:values.keySet()){String text=UiState.answerText(values.get(name).getText().toString());
          if(text==null){hint("请回答全部问题");return;}answers.put(name,text);}
          answer(id,null,answers,null,thread,turn,version);
        }catch(Exception e){hint("请检查回答");}
      }));
    }
  }
  void answer(String id,String decision,JSONObject answers,JSONObject response,String thread,String turn,String version){
    if(voiceWorking||busy||decisionView.busy){hint("请结束录音或等待本次核实");return;}
    JSONArray pending=current.optJSONArray("pending");JSONObject item=pending==null?null:pending.optJSONObject(0);
    if(item==null||!id.equals(item.optString("id"))||!version.equals(item.optString("version"))||!thread.equals(current.optString("thread_id"))||!turn.equals(current.optString("turn_id"))){hint("请求已变化，请刷新");return;}
    final String scope=connectionScope,raw=connectionState.optString("connection_scope"),key=decisionKey(id);
    if(raw.isEmpty()){hint("电脑服务不支持安全状态核对，请在电脑处理");return;}
    if(!get(key).isEmpty()){verifyDecision(item,status->decisionResult(key,status));return;}
    decisionView.busy=true;pendingKey="";updateChat();
    verifyDecision(item,status->{decisionView.busy=false;
      if(!scope.equals(connectionScope)||!AttentionLedger.canAct(status.optString("request_status"),status.optString("delivery_status"),status.optBoolean("can_submit"))){decisionResult(key,status);return;}
      try{
        JSONObject body=new JSONObject().put("id",id).put("thread_id",thread).put("turn_id",turn).put("expected_version",version).put("connection_scope",raw);
        if(decision!=null)body.put("decision",decision);if(answers!=null)body.put("answers",answers);if(response!=null)body.put("response",response);
        String marker=new JSONObject().put("id",id).put("thread_id",thread).put("turn_id",turn).put("version",version).put("connection_scope",raw).toString();
        prefs.edit().putString(key,marker).apply();pendingKey="";updateChat();
        final String address=endpoint("/assistant/answer"),credential=get("token"),base=get("base");
        exchange(()->{if(!base.equals(get("base"))||!credential.equals(get("token"))||!raw.equals(AssistantMonitor.latest.optString("connection_scope")))throw new IOException("连接已变化，未发送");if(!prefs.edit().putString(key,marker).commit())throw new IOException("无法保存送达记录，未发送");return bridgeRequest(address,"POST",body.toString(),credential);},state->{applyState(state);decisionResult(key,state.optJSONObject("decision_result"));},error->{JSONObject result=error instanceof RequestFailure?((RequestFailure)error).decisionResult:null;decisionResult(key,result);});
      }catch(Exception e){hint("回答尚未发送，请重新核实");}
    });
  }  void openOnDesktop(String target){
    try{JSONObject body=new JSONObject().put("thread_id",target);
      exchange(()->bridgeRequest(endpoint("/assistant/conversations/open"),"POST",body.toString(),get("token")),result->hint("已在电脑打开对应对话"));
    }catch(JSONException e){hint("缺少对应对话，请刷新");}
  }
  CheckBox decisionCheck(String text,LinearLayout parent){
    CheckBox box=new CheckBox(this);box.setText(text);box.setTextColor(WHITE);box.setTextSize(14);box.setMinHeight(dp(48));
    parent.addView(box,new LinearLayout.LayoutParams(-1,-2));return box;
  }
  EditText decisionInput(LinearLayout parent,String hint){
    EditText input=new EditText(this);input.setTextColor(WHITE);input.setHintTextColor(MUTED);input.setTextSize(15);input.setHint(hint);input.setMinHeight(dp(48));
    parent.addView(input,new LinearLayout.LayoutParams(-1,-2));return input;
  }
  void showPermissionFields(JSONObject item,String id,String thread,String turn,String version){
    JSONObject asked=item.optJSONObject("permissions");if(asked==null)return;
    addControl(pendingPanel,control("查看申请范围",false,()->showPages("申请范围",item.optString("detail"))));
    LinkedHashMap<String,CheckBox> picks=new LinkedHashMap<>();
    JSONObject network=asked.optJSONObject("network");
    if(network!=null)picks.put("network",decisionCheck("允许网络访问",pendingPanel));
    JSONObject fs=asked.optJSONObject("fileSystem");
    if(fs!=null)for(String kind:new String[]{"read","write","entries"}){
      JSONArray paths=fs.optJSONArray(kind);if(paths==null)continue;
      for(int n=0;n<paths.length();n++)picks.put(kind+":"+n,decisionCheck((kind.equals("read")?"读取：":kind.equals("write")?"写入：":"访问项：")+paths.opt(n),pendingPanel));
    }
    CheckBox session=decisionCheck("在本次对话后续也允许",pendingPanel);
    addControl(pendingPanel,control("允许所选范围",true,()->{
      try{
        JSONObject granted=new JSONObject(),selectedFs=new JSONObject();
        if(picks.containsKey("network")&&picks.get("network").isChecked())granted.put("network",network);
        if(fs!=null)for(String kind:new String[]{"read","write","entries"}){
          JSONArray paths=fs.optJSONArray(kind),selected=new JSONArray();if(paths==null)continue;
          for(int n=0;n<paths.length();n++)if(picks.get(kind+":"+n).isChecked())selected.put(paths.get(n));
          if(selected.length()>0)selectedFs.put(kind,selected);
        }
        if(selectedFs.length()>0){if(fs.has("globScanMaxDepth"))selectedFs.put("globScanMaxDepth",fs.get("globScanMaxDepth"));granted.put("fileSystem",selectedFs);}
        if(granted.length()==0){hint("请选择需要允许的范围，或点拒绝");return;}
        JSONObject response=new JSONObject().put("permissions",granted).put("scope",session.isChecked()?"session":"turn");
        answer(id,null,null,response,thread,turn,version);
      }catch(Exception e){hint("请检查授权范围");}
    }));
    addControl(pendingPanel,control("拒绝",false,()->{
      try{answer(id,null,null,new JSONObject().put("permissions",new JSONObject()).put("scope","turn"),thread,turn,version);}catch(JSONException ignored){}
    }));
  }
  void showFormFields(JSONObject item,String id,String thread,String turn,String version){
    JSONArray fields=item.optJSONArray("fields");if(fields==null)return;
    Map<String,EditText> inputs=new LinkedHashMap<>();Map<String,CheckBox> checks=new LinkedHashMap<>(),optional=new LinkedHashMap<>();
    Map<String,List<CheckBox>> multi=new LinkedHashMap<>();
    for(int n=0;n<fields.length();n++){
      JSONObject f=fields.optJSONObject(n);if(f==null)continue;String key=f.optString("id"),type=f.optString("type");
      pendingPanel.addView(label(f.optString("title",key)+(f.optBoolean("required")?" *":"（可选）"),15));
      if(!f.optString("description").isEmpty())pendingPanel.addView(label(f.optString("description"),12));
      if(!f.optBoolean("required"))optional.put(key,decisionCheck("填写此项",pendingPanel));
      JSONArray choices=f.optJSONArray("enum"),names=f.optJSONArray("enumNames");
      if(type.equals("boolean")){CheckBox box=decisionCheck("是／启用",pendingPanel);box.setChecked(f.optBoolean("default",false));checks.put(key,box);}
      else if(type.equals("array")){
        List<CheckBox> boxes=new ArrayList<>();
        if(choices!=null)for(int c=0;c<choices.length();c++)boxes.add(decisionCheck(names==null?choices.optString(c):names.optString(c,choices.optString(c)),pendingPanel));
        multi.put(key,boxes);
      }else{
        EditText input=decisionInput(pendingPanel,type.equals("string")?"请输入回答":"请输入数字");inputs.put(key,input);
        if(!type.equals("string"))input.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_SIGNED|(type.equals("number")?InputType.TYPE_NUMBER_FLAG_DECIMAL:0));
        if(f.has("default")&&!f.isNull("default"))input.setText(f.optString("default"));
        if(choices!=null)for(int c=0;c<choices.length();c++){
          final String value=choices.optString(c),name=names==null?value:names.optString(c,value);
          addControl(pendingPanel,control(name,false,()->{input.setText(value);input.clearFocus();}));
        }
      }
    }
    addControl(pendingPanel,control("提交表单",true,()->{
      try{
        JSONObject content=new JSONObject();
        for(int n=0;n<fields.length();n++){
          JSONObject f=fields.getJSONObject(n);String key=f.getString("id"),type=f.getString("type");
          if(optional.containsKey(key)&&!optional.get(key).isChecked())continue;
          if(type.equals("boolean"))content.put(key,checks.get(key).isChecked());
          else if(type.equals("array")){
            JSONArray selected=new JSONArray(),choices=f.getJSONArray("enum");List<CheckBox> boxes=multi.get(key);
            for(int c=0;c<boxes.size();c++)if(boxes.get(c).isChecked())selected.put(choices.get(c));content.put(key,selected);
          }else{
            String text=inputs.get(key).getText().toString();
            if(type.equals("integer"))content.put(key,Long.parseLong(text));
            else if(type.equals("number")){double value=Double.parseDouble(text);if(Double.isNaN(value)||Double.isInfinite(value))throw new NumberFormatException();content.put(key,value);}
            else content.put(key,text);
          }
        }
        answer(id,null,null,new JSONObject().put("action","accept").put("content",content),thread,turn,version);
      }catch(Exception e){hint("请检查必填项与数字格式");}
    }));
    for(String action:new String[]{"decline","cancel"})addControl(pendingPanel,control(action.equals("decline")?"拒绝":"取消",false,()->{
      try{answer(id,null,null,new JSONObject().put("action",action).put("content",JSONObject.NULL),thread,turn,version);}catch(JSONException ignored){}
    }));
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
    if(voiceWorking){hint("请先结束录音并等待识别完成");return;}
    String target=current.optString("thread_id");if(target.isEmpty()){hint("还没有执行中的对话");return;}
    rememberPosition();saveDraft();viewed=target;viewTitle=current.optString("title","手表对话");readOnly=false;persistView();showChat(true);
  }
  void menu(){
    if(voiceWorking){hint("请先结束录音并等待识别完成");return;}
    showDialog(new AlertDialog.Builder(this).setTitle("更多操作").setItems(new String[]{"刷新连接与记录","返回执行中的对话","停止当前任务","电脑控制","设置与提醒","当前对话设置","对话管理","主对话设置","保留记录"},(d,n)->{
      if(n==0)refresh();if(n==1)returnToActive();
      if(n==2)stopCurrent();
      if(n==3)startActivity(new Intent(this,MainActivity.class).putExtra("controls",true));
      if(n==4)reminderSettings();
      if(n==5)threadSettings();
      if(n==6){if(isNew()||viewed.isEmpty())openList(true);else conversationManagement(viewed,false);}
      if(n==7)mainConversationSettings();
      if(n==8)showRecoveryRecords();
    }).create());
  }
  boolean hasRecoveryRecords(){return !ConversationState.recoveryRecords(prefs.getAll()).isEmpty();}
  boolean reviewedRecovery(String key){return ConversationState.hash(get(key)).equals(get("reviewed:"+key));}
  boolean blockedRecovery(String target,String kind){
    for(String key:ConversationState.recoveryRecords(prefs.getAll())){
      boolean matches=kind.equals("pending")?(key.equals("pendingPayload")||key.equals("followRequestId")&&target.equals(get("followDraft"))||key.equals("pending:"+target)||key.endsWith(":pending:"+target)):(key.startsWith("operation:"+target+":")||key.contains(":operation:"+target+":"));
      if(!matches||reviewedRecovery(key))continue;
      boolean own=key.startsWith(recoveryNamespace())&&(!connectionIdentity.isEmpty()||connectionScope.equals(get("owner:"+key)));
      if(!own)return true;
    }return false;
  }
  void showRecoveryRecords(){
    saveDraft();List<String> records=ConversationState.recoveryRecords(prefs.getAll());
    if(records.isEmpty()){showPages("保留记录","没有保留草稿或未确认送达操作。");return;}
    List<String> labels=new ArrayList<>();for(String key:records){boolean draft=key.equals("aiDraft")||key.startsWith("draft:")||key.startsWith("draft-v1:")||key.contains(":draft:");labels.add((draft?"草稿":"送达待核对")+" · "+(labels.size()+1));}
    showDialog(new AlertDialog.Builder(this).setTitle("保留记录 · 不自动关联或重发").setItems(labels.toArray(new String[0]),(d,n)->{
      String key=records.get(n),value=get(key),scope=connectionScope,target=viewed;
      boolean draft=key.equals("aiDraft")||key.startsWith("draft:")||key.startsWith("draft-v1:")||key.contains(":draft:");
      String detail=draft?value:"请在原电脑和账号核对，原请求编号会保留，不会重新提交。\n\n"+value;
      LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.addView(label(detail,14));
      AlertDialog dialog=new AlertDialog.Builder(this).setTitle(draft?"保留草稿":"未确认送达记录").setView(boundedPanel(content)).setNegativeButton("保留",null)
        .setPositiveButton(draft?"复制到当前草稿":"已在电脑核对",(a,b)->confirm(draft?"将草稿复制到当前对话？请确认当前连接与账号。原记录保留。":"确认已在原电脑核对此操作？只解除发送拦截，保留原记录，不重发操作。",()->{
          if(!scope.equals(connectionScope)||!target.equals(viewed))return;
          if(draft){if(listMode||prompt==null){hint("请先打开目标对话，再从保留记录复制");return;}prompt.setText(value);prefs.edit().putString(draftKey(),value).putString("owner:"+draftKey(),connectionScope).apply();}
          else prefs.edit().putString("reviewed:"+key,ConversationState.hash(value)).apply();
          hint(draft?"已复制，请检查文字再发送":"核对标记已保存，原记录仍保留");
        })).create();showDialog(dialog);
    }).create());
  }
  void stopCurrent(){
    if(!viewingActive()||!running()){hint("请先返回执行中的对话");return;}
    final String scope=connectionScope,thread=current.optString("thread_id"),turn=current.optString("turn_id");
    if(turn.isEmpty()){hint("本轮编号尚未同步，请稍后重试");return;}
    confirm("停止当前任务？已经完成的操作不会撤销。",()->{
      if(!scope.equals(connectionScope)||!canStopTarget(thread,turn)){hint("当前回合已变化，请重新确认");return;}
      try{JSONObject body=new JSONObject().put("thread_id",thread).put("turn_id",turn);
        exchange(()->bridgeRequest(endpoint("/assistant/stop"),"POST",body.toString(),get("token")),this::applyState);
      }catch(JSONException ignored){}
    });
  }
  boolean canStopTarget(String thread,String turn){return ConversationState.canStop(thread,turn,current.optString("thread_id"),current.optString("turn_id"),running(),busy);}
  void mainConversationSettings(){
    String scope=connectionScope;
    showDialog(new AlertDialog.Builder(this).setTitle(connectionIdentity.isEmpty()?"主对话 · 仅本次连接":"主对话").setItems(new String[]{"将当前对话设为主对话","打开主对话","清除绑定"},(d,n)->{
      if(!scope.equals(connectionScope))return;
      if(n==0){if(isNew()||viewed.isEmpty()||readOnly){hint("请先打开可继续的现有对话");return;}sessionMain=viewed;sessionMainTitle=viewTitle;if(!connectionIdentity.isEmpty())prefs.edit().putString("main-v1:"+scope,viewed).putString("main-title-v1:"+scope,viewTitle).apply();hint("主对话已绑定，权限沿用电脑设置；未知账号身份时仅本次连接有效");}
      if(n==1){String target=connectionIdentity.isEmpty()?sessionMain:get("main-v1:"+scope);if(target.isEmpty()){hint("尚未绑定，可打开现有对话后绑定");return;}rememberPosition();saveDraft();viewed=target;viewTitle=connectionIdentity.isEmpty()?sessionMainTitle:get("main-title-v1:"+scope);persistView();showChat(true);}
      if(n==2){sessionMain="";sessionMainTitle="";prefs.edit().remove("main-v1:"+scope).remove("main-title-v1:"+scope).apply();hint("主对话绑定已清除");}
    }).create());
  }
  ScrollView boundedPanel(LinearLayout panel){
    ScrollView scroll=new ScrollView(this){
      @Override protected void onMeasure(int w,int h){super.onMeasure(w,View.MeasureSpec.makeMeasureSpec(UiState.panelLimit(getResources().getDisplayMetrics().heightPixels/2,View.MeasureSpec.getSize(h),View.MeasureSpec.getMode(h)!=View.MeasureSpec.UNSPECIFIED),View.MeasureSpec.AT_MOST));}
    };
    scroll.setFillViewport(false);scroll.addView(panel,new ScrollView.LayoutParams(-1,-2));return scroll;
  }
  Spinner choiceSpinner(LinearLayout parent,List<String> names){
    Spinner choice=new Spinner(this);choice.setMinimumHeight(dp(48));
    ArrayAdapter<String> adapter=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,names){
      @Override public View getView(int position,View reusable,ViewGroup holder){View view=super.getView(position,reusable,holder);((TextView)view).setTextColor(WHITE);return view;}
    };
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);choice.setAdapter(adapter);
    LinearLayout.LayoutParams choiceLayout=new LinearLayout.LayoutParams(-1,dp(48));choiceLayout.weight=0;parent.addView(choice,choiceLayout);return choice;
  }
  void threadSettings(){
    if(running()){hint("本轮结束后可修改模型与模式");return;}
    if(isNew()||viewed.isEmpty()){hint("第一条消息创建对话后，可查看此对话设置");return;}
    final String target=viewed;final int epoch=navigation;hint("正在读取当前对话设置…");
    query(()->readRequest(url("/assistant/conversations/settings?thread_id="+enc(target))),epoch,this::showThreadSettings);
  }
  void showThreadSettings(JSONObject data){
    final String target=data.optString("thread_id"),version=data.optString("version");
    final String scope=connectionScope;
    JSONArray models=data.optJSONArray("models"),modes=data.optJSONArray("modes");
    if(models==null)models=new JSONArray();if(modes==null)modes=new JSONArray();
    final JSONArray modelItems=models,modeItems=modes;
    LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);
    panel.addView(label("仅此对话，与电脑同步",12));
    String previous=get(operationStorage(target,"settings"));
    if(!previous.isEmpty())try{
      JSONObject old=new JSONObject(previous),changes=old.optJSONObject("changes");boolean matches=changes!=null;
      if(changes!=null)for(Iterator<String> it=changes.keys();it.hasNext();){String k=it.next();if(!String.valueOf(changes.opt(k)).equals(String.valueOf(data.opt(k))))matches=false;}
      if(matches&&(!connectionIdentity.isEmpty()||connectionScope.equals(get("owner:"+operationStorage(target,"settings")))))prefs.edit().remove(operationStorage(target,"settings")).apply();
    }catch(JSONException ignored){}
    panel.addView(label("模型",14));List<String> modelNames=new ArrayList<>();int modelIndex=-1;
    for(int n=0;n<modelItems.length();n++){JSONObject m=modelItems.optJSONObject(n);if(m==null)continue;modelNames.add(m.optString("name",m.optString("id")));if(m.optString("id").equals(data.optString("model")))modelIndex=n;}
    if(modelNames.isEmpty())modelNames.add(data.optString("model","未获取"));
    Spinner model=choiceSpinner(panel,modelNames);model.setSelection(Math.max(0,modelIndex));
    panel.addView(label("思考强度",14));List<String> effortNames=new ArrayList<>(),effortValues=new ArrayList<>();Spinner effort=choiceSpinner(panel,effortNames);
    Runnable fillEfforts=()->{
      effortNames.clear();effortValues.clear();effortNames.add("保持当前设置");JSONObject m=modelItems.optJSONObject(model.getSelectedItemPosition());JSONArray values=m==null?null:m.optJSONArray("efforts");
      if(values!=null)for(int n=0;n<values.length();n++){String value=values.optString(n);effortNames.add(value);effortValues.add(value);}
      int index=UiState.effortIndex(data.isNull("effort")?null:data.optString("effort"),effortValues.toArray(new String[0]));
      ((ArrayAdapter<?>)effort.getAdapter()).notifyDataSetChanged();effort.setSelection(index);
    };fillEfforts.run();
    model.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int n,long id){fillEfforts.run();}public void onNothingSelected(AdapterView<?> p){}});
    panel.addView(label("工作模式",14));List<String> modeNames=new ArrayList<>();int modeIndex=0;
    for(int n=0;n<modeItems.length();n++){String mode=modeItems.optString(n);modeNames.add(mode.equals("plan")?"规划模式":"执行模式");if(mode.equals(data.optString("mode")))modeIndex=n;}
    if(modeNames.isEmpty())modeNames.add(data.optString("mode","未获取"));
    Spinner mode=choiceSpinner(panel,modeNames);mode.setSelection(modeIndex);
    boolean writable=data.optBoolean("can_save")&&modelIndex>=0&&modeItems.length()>0;
    model.setEnabled(writable);effort.setEnabled(writable);mode.setEnabled(writable);
    if(!writable)panel.addView(label(data.optString("unavailable_reason","当前模型未出现在可选目录，请回电脑设置"),14));
    AlertDialog dialog=new AlertDialog.Builder(this).setTitle("当前对话设置").setView(boundedPanel(panel)).setPositiveButton("保存",null).setNegativeButton("关闭",null).create();showDialog(dialog);
    dialog.getButton(-1).setEnabled(writable);
    dialog.getButton(-1).setOnClickListener(v->{
      if(!scope.equals(connectionScope)||!target.equals(viewed)||running()){dialog.dismiss();hint("对话状态已变化，请重新打开设置");return;}
      try{
        JSONObject changes=new JSONObject();JSONObject selected=modelItems.getJSONObject(model.getSelectedItemPosition());
        String selectedModel=selected.getString("id"),selectedMode=modeItems.getString(mode.getSelectedItemPosition());
        String selectedEffort=UiState.effortValue(effort.getSelectedItemPosition(),effortValues.toArray(new String[0]));
        if(!selectedModel.equals(data.optString("model")))changes.put("model",selectedModel);
        if(selectedEffort!=null&&!selectedEffort.equals(data.optString("effort")))changes.put("effort",selectedEffort);
        if(!selectedMode.equals(data.optString("mode")))changes.put("mode",selectedMode);
        if(changes.length()==0){dialog.dismiss();hint("设置没有变化");return;}
        JSONObject body=new JSONObject().put("thread_id",target).put("expected_version",version).put("changes",changes);
        postThreadOperation("/assistant/conversations/settings",body,target,"settings",result->{dialog.dismiss();hint(result.optBoolean("synchronizing")?"电脑已接收，等待设置同步":"当前对话设置已保存");});
      }catch(Exception e){hint("请重新选择电脑提供的设置");}
    });
  }
  void postThreadOperation(String path,JSONObject body,String target,String action,Consumer<JSONObject> done){
    if(busy){hint("正在提交，请稍候");return;}
    if(blockedRecovery(target,"operation")){hint("有未核对的旧操作，请先在保留记录中核对");return;}
    final String key=operationStorage(target,action);
    final String scope=connectionScope;
    try{
      String saved=get(key);JSONObject payload=body;
      if(connectionIdentity.isEmpty()&&!connectionScope.equals(get("owner:"+key)))saved="";
      if(!saved.isEmpty()){
        JSONObject previous=new JSONObject(saved);String operation=previous.optString("operation_id");previous.remove("operation_id");
        if(!previous.toString().equals(body.toString())){hint("上次操作送达尚未确认，请先在电脑核对并刷新");return;}
        payload.put("operation_id",operation);
      }else payload.put("operation_id",UUID.randomUUID().toString());
      final String json=payload.toString();String old=get(key);if(!old.isEmpty()&&!old.equals(json))prefs.edit().putString("orphan-receipt-v1:"+ConversationState.hash(key+old),old).apply();
      prefs.edit().putString(key,json).putString("owner:"+key,connectionScope).apply();
      exchange(()->bridgeRequest(endpoint(path),"POST",json,get("token")),result->{if(!scope.equals(connectionScope)){hint("原连接操作状态保留，请在电脑核对");return;}prefs.edit().remove(key).apply();done.accept(result);},error->{
        if(error instanceof RequestFailure&&((RequestFailure)error).notApplied)prefs.edit().remove(key).apply();
      });
    }catch(Exception e){hint("操作尚未发送，请刷新后重试");}
  }
  void conversationManagement(String target,boolean archived){
    final int epoch=navigation;hint("正在读取对话管理信息…");
    query(()->readRequest(url("/assistant/conversations/manage?thread_id="+enc(target))),epoch,data->{
      String title=data.optString("title","对话");JSONObject capabilities=data.optJSONObject("capabilities");
      String[] options=archived?new String[]{"恢复此对话","返回最近对话"}:new String[]{"重命名","归档此对话","已归档对话"};
      showDialog(new AlertDialog.Builder(this).setTitle(title).setItems(options,(dialog,index)->{
        if(archived&&index==1){openList();return;}if(!archived&&index==2){openList(true);return;}
        String action=archived?"restore":index==0?"rename":"archive";
        JSONObject cap=capabilities==null?null:capabilities.optJSONObject(action);
        if(cap==null||!cap.optBoolean("available")){showPages("暂不可用",cap==null?"电脑服务尚未提供此操作":cap.optString("reason","请回电脑处理"));return;}
        if(action.equals("rename")){
          LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);EditText name=decisionInput(panel,"对话名称");name.setText(title);
          AlertDialog rename=new AlertDialog.Builder(this).setTitle("重命名").setView(boundedPanel(panel)).setPositiveButton("保存",null).setNegativeButton("取消",null).create();showDialog(rename);
          rename.getButton(-1).setOnClickListener(v->{String value=name.getText().toString().trim();if(value.isEmpty()||value.length()>120){hint("名称应为1到120个字符");return;}
            submitManagement(data,action,value,false,result->{rename.dismiss();if(viewed.equals(target)){viewTitle=result.optString("title",value);persistView();if(!listMode)setText(titleBar,viewTitle+" ▾");}hint("名称已更新");});});
        }else if(action.equals("archive"))confirm("归档这段对话？记录会保留，可在已归档列表恢复。",()->submitManagement(data,action,null,true,result->{openList();hint("对话已归档");}));
        else submitManagement(data,action,null,false,result->{openList(true);hint("对话已恢复，可在最近对话中继续");});
      }).create());
    });
  }
  void submitManagement(JSONObject info,String action,String name,boolean confirmed,Consumer<JSONObject> done){
    try{
      String target=info.getString("thread_id");JSONObject body=new JSONObject().put("thread_id",target).put("action",action).put("expected_version",info.getString("version"));
      if(name!=null)body.put("name",name);if(confirmed)body.put("confirmed",true);
      final String scope=connectionScope;
      postThreadOperation("/assistant/conversations/manage",body,target,action,result->{storage.execute(()->{conversationCache.invalidate(scope);sessionCache.invalidate(scope);});done.accept(result);});
    }catch(JSONException e){hint("对话信息已变化，请刷新");}
  }
  boolean alertSettingPending,desktopReminderSyncPending;
  void preferenceExchange(Work work,Consumer<JSONObject> done,Consumer<Exception> failed){
    if(desktopReminderSyncPending){hint("正在同步提醒设置，请稍候");return;}
    desktopReminderSyncPending=true;
    try{controls.execute(()->{
      JSONObject value=null;Exception error=null;
      try{value=decode(work.run());}catch(Exception failure){error=failure;}
      final JSONObject result=value;final Exception failure=error;
      ui.post(()->{
        desktopReminderSyncPending=false;
        if(isDestroyed())return;
        if(failure!=null)failed.accept(failure);else done.accept(result);
      });
    });}catch(RejectedExecutionException closed){desktopReminderSyncPending=false;if(!isDestroyed())failed.accept(closed);}
  }
  void localAlertSetting(String pending,Work change,Consumer<String> committed){
    if(alertSettingPending){hint("正在保存提醒设置，请稍候");return;}
    final int page=navigation;alertSettingPending=true;hint(pending);
    try{storage.execute(()->{
      String value="";Exception error=null;
      try{value=change.run();}catch(Exception failed){error=failed;}
      final String outcome=value;final Exception failure=error;
      ui.post(()->{
        alertSettingPending=false;
        if(!isDestroyed()&&page==navigation)hint(failure==null?outcome:"提醒设置未保存，请重试");
        if(failure==null&&!isDestroyed()&&committed!=null)committed.accept(outcome);
      });
    });}catch(RejectedExecutionException closed){alertSettingPending=false;if(!isDestroyed())hint("页面已关闭，请重新打开设置");}
  }
  boolean sameReminderTarget(String base,String credential,String scope){
    return base.equals(get("base"))&&credential.equals(get("token"))&&scope.equals(AssistantMonitor.latest.optString("connection_scope"));
  }
  // Synchronous worker entry points also used by the isolated device acceptance probe.
  void setCompletionVibration(boolean enabled)throws Exception{
    if(enabled)commitCompletionBoundary("completionVibrationEnabled",true);
    else saveLocalAlertState("completionVibrationEnabled",false);
  }
  void pauseActiveAlerts()throws Exception{
    saveLocalAlertState("attentionPaused",true);
    getSystemService(NotificationManager.class).cancel(41);
    AssistantMonitor.reconnectSoon();
  }
  void resumeActiveAlerts()throws Exception{
    commitCompletionBoundary("attentionPaused",false);
    AssistantMonitor.reconnectSoon();
  }
  void saveLocalAlertState(String key,boolean value)throws Exception{
    synchronized(AssistantMonitor.alertGate){
      AssistantMonitor owner=AssistantMonitor.instance;
      if(owner!=null&&owner.completionCursor.rebasePending())throw new IOException("另一项提醒设置正在保存");
      boolean old=prefs.getBoolean(key,key.equals("completionVibrationEnabled"));
      if(old==value)return;
      if(!prefs.edit().putBoolean(key,value).commit()){
        prefs.edit().putBoolean(key,old).commit(); // commit failure can still change the in-memory preferences.
        throw new IOException("提醒设置未保存");
      }
    }
  }
  void commitCompletionBoundary(String key,boolean value)throws Exception{
    if(Looper.myLooper()==Looper.getMainLooper())throw new IOException("提醒设置需要后台保存");
    final AssistantMonitor owner;final CompletionCursor checkpoint;final int generation;
    final String base,token,raw;final boolean oldEnabled,oldPaused;final BridgeNetworkConfig network;
    synchronized(AssistantMonitor.alertGate){
      if(prefs.getBoolean(key,key.equals("completionVibrationEnabled"))==value)return;
      owner=AssistantMonitor.instance;
      base=get("base");token=get("token");raw=AssistantMonitor.latest.optString("connection_scope");
      if(owner==null||base.isEmpty()||token.isEmpty()||raw.isEmpty())throw new IOException("请先连接电脑后再恢复提醒");
      generation=owner.reconnectVersion;
      if(!owner.completionTargetCurrent(prefs,generation,base,token,raw))throw new IOException("连接已变化，请重试");
      oldEnabled=prefs.getBoolean("completionVibrationEnabled",true);oldPaused=prefs.getBoolean("attentionPaused",false);
      network=BridgeNetworkConfig.from(prefs.getAll());checkpoint=owner.completionCursor;
      if(!checkpoint.beginRebase())throw new IOException("另一项提醒设置正在保存");
    }
    try{
      // Local connection: never overwrite the monitor's longpoll/cancellation connection.
      // No alertGate is held during network I/O; confirmations continue normally.
      JSONObject snapshot=decode(MainActivity.request(base+"/assistant/state?compact=1&t="+URLEncoder.encode(token,"UTF-8"),"GET","","",null,network));
      JSONObject batch=snapshot.optJSONObject("completion_notifications");
      if(batch==null||!batch.optBoolean("available")||!raw.equals(snapshot.optString("connection_scope"))
        ||!raw.equals(batch.optString("scope"))||batch.optLong("cursor",-1)<0)throw new IOException("电脑完成状态未能核实，设置未更改");
      long cut=batch.optLong("cursor",-1);String namespace=ConversationState.hash(base+":"+raw);
      synchronized(AssistantMonitor.alertGate){
        if(!owner.completionTargetCurrent(prefs,generation,base,token,raw)
          ||oldEnabled!=prefs.getBoolean("completionVibrationEnabled",true)||oldPaused!=prefs.getBoolean("attentionPaused",false))
          throw new IOException("连接或提醒设置已变化，请重试");
        String oldScope=prefs.getString("completionCursorScope","");long oldCursor=prefs.getLong("completionCursor",0);
        boolean saved=checkpoint.rebase(namespace,cut,cursor->{
          if(!owner.completionTargetCurrent(prefs,generation,base,token,raw))return false;
          if(prefs.edit().putBoolean(key,value).putString("completionCursorScope",namespace).putLong("completionCursor",cursor).commit())return true;
          prefs.edit().putBoolean(key,key.equals("completionVibrationEnabled")?oldEnabled:oldPaused)
            .putString("completionCursorScope",oldScope).putLong("completionCursor",oldCursor).commit();
          return false;
        });
        if(!saved)throw new IOException("提醒设置未保存，请重试");
        // The explicit snapshot already establishes this connection's baseline.
        if(owner.completionTargetCurrent(prefs,generation,base,token,raw)){
          owner.completionOwner=ConversationState.hash(base+":"+token);owner.completionBaseline=false;
        }
      }
    }finally{synchronized(AssistantMonitor.alertGate){checkpoint.finishRebase();}}
  }
  void reminderSettings(){
    boolean reminders=connectionState.optBoolean("desktop_attention_enabled",true)&&!prefs.getBoolean("desktopLocalMute",false);
    showDialog(new AlertDialog.Builder(this).setTitle("设置与提醒").setItems(new String[]{
      "电脑确认提醒："+(reminders?"开":"关"),"自动弹出确认："+(android.provider.Settings.canDrawOverlays(this)?"已授权":"待授权"),
      "暂停全部主动提醒","连接设置","自定义接口","语音输入："+(get("voiceProvider").equals("doubao")?"豆包云端":"电脑本地"),"提醒诊断","测试振动与亮屏","恢复主动提醒","手表兼容振动："+(AssistantMonitor.compatibilityEnabled(this)?"开":"关"),"任务完成振动："+(AssistantMonitor.completionEnabled(this)?"开":"关")},(d,n)->{
      if(n==0)toggleDesktopReminders(!reminders);if(n==1)configureAttentionPopup();
      if(n==2)localAlertSetting("正在暂停主动提醒…",()->{pauseActiveAlerts();return "主动提醒已暂停，可在设置中恢复";},null);
      if(n==3||n==4)startActivity(new Intent(this,MainActivity.class).putExtra("screen",n==3?"connection":"custom"));
      if(n==5)cloudVoiceSettings();
      if(n==6)showPages("提醒诊断",AssistantMonitor.diagnostics(this));
      if(n==7){
        final int page=navigation;boolean[] submitted={false};
        localAlertSetting("正在提交测试通知…",()->{submitted[0]=AssistantMonitor.testAlert(this);return submitted[0]?"测试通知已提交":"测试通知未提交，请检查设置";},ignored->{if(active&&page==navigation)showAlertTest(submitted[0]);});
      }
      if(n==8)localAlertSetting("正在恢复主动提醒…",()->{resumeActiveAlerts();return "已恢复，正在重新核对待处理";},null);
      if(n==9)configureCompatibleVibration();
      if(n==10){final boolean enabled=!AssistantMonitor.completionEnabled(this);
        localAlertSetting("正在保存完成振动设置…",()->{setCompletionVibration(enabled);return enabled?"任务完成振动已开启":"任务完成振动已关闭";},null);
      }
    }).create());
  }
  void configureCompatibleVibration(){
    showDialog(new AlertDialog.Builder(this).setTitle("手表兼容振动")
      .setSingleChoiceItems(new String[]{"关 · 系统通知振动","开 · 兼容此手表"},AssistantMonitor.compatibilityEnabled(this)?1:0,(dialog,which)->{
        final boolean enabled=which==1;dialog.dismiss();
        localAlertSetting("正在保存振动设置…",()->{synchronized(AssistantMonitor.alertGate){prefs.edit().putBoolean("watchCompatibleVibration",enabled).apply();}return enabled?"兼容振动已开启；可运行提醒测试":"已恢复系统通知振动";},null);
      }).setNegativeButton("关闭",null).create());
  }
  void showAlertTest(boolean submitted){
    showDialog(new AlertDialog.Builder(this).setTitle("提醒测试")
      .setMessage((submitted?"已提交测试通知。\n请确认是否振动、亮屏。\n已提交不代表实际成功。":"测试未提交。\n请检查提醒开关和系统通知设置。")+"\n"+AssistantMonitor.vibrationStatus(this))
      .setPositiveButton("关闭",null)
      .setNeutralButton("查看详情",(dialog,which)->showPages("提醒诊断",AssistantMonitor.diagnostics(this))).create());
  }
  void configureAttentionPopup(){
    showDialog(new AlertDialog.Builder(this).setTitle("自动弹出确认")
      .setMessage("系统通知、通道、勿扰和后台限制都会影响自动亮屏或弹页。上层显示权限可能有帮助，不能保证成功；受限时请点通知处理。只对新有效请求尝试，尊重关闭和暂停设置。")
      .setPositiveButton("打开系统授权",(d,w)->{
        try{startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
          android.net.Uri.parse("package:"+getPackageName())));}
        catch(ActivityNotFoundException ex){hint("系统没有授权页面，请在应用权限中允许本应用显示在其他应用上层");}
      }).setNegativeButton("返回",null).create());
  }
  void toggleDesktopReminders(boolean enabled){
    if(desktopReminderSyncPending){hint("正在同步提醒设置，请稍候");return;}
    if(!shared()){hint("请先更新电脑服务到 v0.5");return;}
    try{
      final String base=get("base"),credential=get("token"),scope=connectionState.optString("connection_scope");
      final int page=navigation;
      final String body=new JSONObject().put("desktop_attention_enabled",enabled).toString();
      localAlertSetting("正在保存提醒设置…",()->{
        synchronized(AssistantMonitor.alertGate){
          if(!sameReminderTarget(base,credential,scope))throw new IOException("连接已变化");
          if(!enabled)AssistantMonitor.silence(this,false);
          else {prefs.edit().putBoolean("desktopLocalMute",false).apply();AssistantMonitor.reconnectSoon();}
        }
        return "本地提醒设置已保存，正在同步电脑";
      },ignored->{
        if(!sameReminderTarget(base,credential,scope)){if(page==navigation)hint("连接已变化，未向新的电脑同步");return;}
        preferenceExchange(()->{
          if(!sameReminderTarget(base,credential,scope))throw new IOException("连接已变化，未发送设置");
          return bridgeRequest(base+"/assistant/preferences","POST",body,credential);
        },state->{
          if(!sameReminderTarget(base,credential,scope))return;
          applyState(state);AssistantMonitor.reconnectSoon();
          if(page==navigation)hint(enabled?(android.provider.Settings.canDrawOverlays(this)?"电脑确认提醒已开启":"提醒已开启；自动弹出请在更多操作中授权"):"电脑确认提醒已关闭，仍可手动处理");
        },failure->{
          if(page==navigation)hint(sameReminderTarget(base,credential,scope)?"本地提醒设置已保存，电脑端未确认；请联网后手动重试":"连接已变化，原设置同步未确认；未向新的电脑重发");
        });
      });
    }catch(Exception e){hint("设置未确认，请联网后重试");}
  }
  void activateViewed(){
    if(voiceWorking||viewed.isEmpty()||isNew())return;
    final String target=viewed;
    try{
      JSONObject body=new JSONObject();body.put("thread_id",target);hint("正在电脑连接此对话…");
      exchange(()->bridgeRequest(endpoint("/assistant/activate"),"POST",body.toString(),get("token")),state->{
        applyState(state);if(target.equals(viewed)&&!listMode)loadHistory(false);
      });
    }catch(Exception e){hint("未能连接此对话");}
  }
  void openAttention(String cursor){
    decisionView.cancel(true);
    if(voiceWorking){hint("请先结束录音");return;}
    saveDraft();prompt=null;listMode=true;attentionMode=true;rows.clear();listCursor=cursor;
    shell("待处理");attentionButton.setVisibility(View.GONE);
    addButtonHeader(control("刷新待处理",false,()->loadAttention("")));
    listMore=control("加载更多",false,()->{if(attentionPageState.hasNext())loadAttention(attentionPageState.next);});
    list.addFooterView(buttonContainer(listMore),null,false);adapter=new Rows();list.setAdapter(adapter);
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
      attentionPageState=new UiState.AttentionPage(cursor,data.optString("next_cursor",""),data.optInt("total"));
      updateAttentionPageControls();adapter.notifyDataSetChanged();list.setSelection(0);
      hint(rows.isEmpty()?"目前没有待确认项":"点对话查看说明和原始选项");
    });
  }
  void updateAttentionPageControls(){
    attentionPage=attentionPageState.current;listCursor=attentionPageState.next;
    listMore.setEnabled(attentionPageState.hasNext());listMore.setText(attentionPageState.hasNext()?"加载更多":"已到末尾");phase.setText(attentionPageState.countLabel());
  }
  void openMessage(JSONObject row){
    if(row.optBoolean("live")){showPages(row.optBoolean("complete")?"回复预览":"正在生成",row.optString("text")+(row.optBoolean("clipped")?"\n\n预览已达上限，完成后点开完整记录。":""));return;}
    if(row.optString("id").startsWith("local:")){hint("消息已发送，完成后可查看记录");return;}
    final String thread=viewed,item=row.optString("id");final int epoch=navigation;final int[] page={0},total={1};
    ScrollView box=new ScrollView(this){
      @Override protected void onMeasure(int w,int h){super.onMeasure(w,View.MeasureSpec.makeMeasureSpec(UiState.panelLimit(getResources().getDisplayMetrics().heightPixels/2,View.MeasureSpec.getSize(h),View.MeasureSpec.getMode(h)!=View.MeasureSpec.UNSPECIFIED),View.MeasureSpec.AT_MOST));}
    };
    TextView text=label("正在读取…",15);text.setTextIsSelectable(true);box.addView(text);
    AlertDialog dialog=new AlertDialog.Builder(this).setTitle("消息").setView(box)
      .setPositiveButton("关闭",null).setNegativeButton("上一页",null).setNeutralButton("下一页",null).create();showDialog(dialog);
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
    showDialog(dialog);
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
    if(cloudSpeech!=null){beginCloudSpeech();return;}
    if(listMode||readOnly||(running()&&!viewingActive())){hint("请先返回可发送的当前对话");return;}
    if(get("voiceProvider").equals("doubao")){beginCloudSpeech();return;}
    final String speechTarget=viewed,speechScope=connectionScope;
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
        if(!active||discardCapture||!speechScope.equals(connectionScope)||!speechTarget.equals(viewed))return;
        if(result==null){hint("语音识别未成功，请重试；草稿已保留");return;}
        if(!prompt.getText().toString().trim().isEmpty()){
          showDialog(new AlertDialog.Builder(this).setMessage("用识别文字替换当前草稿？")
            .setPositiveButton("替换",(d,w)->{if(speechScope.equals(connectionScope)&&speechTarget.equals(viewed)&&prompt!=null&&!listMode)prompt.setText(result.optString("text"));})
            .setNegativeButton("保留草稿",null).create());
        }else prompt.setText(result.optString("text"));
        hint("已识别，请检查文字再发送");
        });
      });
    }),error->runOnUiThread(()->{if(!isDestroyed()){voiceWorking=false;mic.setText("点一下说话");if(active)hint("录音失败，请重试");}}));
  }
  void cloudVoiceSettings(){
    if(voiceWorking){hint("请先结束录音");return;}
    LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(12),dp(4),dp(12),dp(4));
    Spinner provider=choiceSpinner(form,Arrays.asList("电脑本地识别","豆包云端 · 边说边出字"));
    provider.setSelection(get("voiceProvider").equals("doubao")?1:0);
    CloudVoiceKey saved=new CloudVoiceKey(this);
    form.addView(label("云端语音直接从手表上传火山引擎，不经电脑；需开通流式语音识别 2.0，可能产生调用费用。",13));
    EditText key=new EditText(this);key.setTextColor(WHITE);key.setHintTextColor(MUTED);key.setMinHeight(dp(48));
    key.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);key.setSingleLine(true);
    key.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
    key.setHint(saved.saved()?"密钥已保存；留空保持原值":"填入语音服务 API Key");form.addView(key);
    form.addView(label("仅在此手表加密保存，不回显密钥；识别结果由你检查后发送。",12));
    AlertDialog dialog=showDialog(new AlertDialog.Builder(this).setTitle("语音输入").setView(boundedPanel(form))
      .setPositiveButton("保存",null).setNegativeButton("返回",null)
      .setNeutralButton("清除密钥",(d,w)->speech.execute(()->{
        try{saved.clear();prefs.edit().putString("voiceProvider","desktop").apply();runOnUiThread(()->hint("云端密钥已清除，恢复电脑本地识别"));}
        catch(Exception e){runOnUiThread(()->hint("清除未完成，请重试"));}
      })).create());
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
      final boolean cloud=provider.getSelectedItemPosition()==1;final String value=key.getText().toString().trim();
      if(cloud&&!saved.saved()&&value.isEmpty()){hint("请先填写语音 API Key");return;}
      if(!value.isEmpty()&&(value.length()<16||!CloudProtocol.validKey(value))){hint("请检查语音 API Key 格式");return;}
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
      speech.execute(()->{
        try{if(!value.isEmpty())saved.save(value);prefs.edit().putString("voiceProvider",cloud?"doubao":"desktop").apply();
          runOnUiThread(()->{if(!isDestroyed())dialog.dismiss();hint(cloud?"已启用手表直连云端流式语音":"已切换电脑本地识别");});
        }catch(Exception e){runOnUiThread(()->{if(!isDestroyed())dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);hint("密钥未保存，请重试");});}
      });
    });
  }
  void beginCloudSpeech(){
    if(cloudSpeech!=null){cloudSpeech.stop();mic.setText("正在收尾…");hint("等待最后的识别文字；不会自动发送");return;}
    if(voiceWorking||busy){hint("正在处理，请稍候");return;}
    if(!new CloudVoiceKey(this).saved()){cloudVoiceSettings();return;}
    if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},7);return;}
    final int epoch=++cloudEpoch;final String target=viewed,scope=connectionScope;
    cloudDraft=new VoiceDraft(prompt.getText().toString(),target);cloudPending="";
    voiceWorking=true;discardCapture=false;prompt.setEnabled(false);mic.setText("连接云端…");hint("正在连接语音服务；不经过电脑");
    ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(prompt.getWindowToken(),0);
    speech.execute(()->{
      String apiKey;
      try{apiKey=new CloudVoiceKey(this).load();if(apiKey.isEmpty())throw new IOException();}
      catch(Exception e){runOnUiThread(()->cloudFinished(epoch,"无法读取云端密钥，请在语音设置中重新保存"));return;}
      runOnUiThread(()->{
        if(!active||isDestroyed()||epoch!=cloudEpoch||!scope.equals(connectionScope)||!target.equals(viewed))return;
        CloudSpeech.Audio audio=new CloudSpeech.Audio(){
          public void start(Consumer<byte[]> frame,Runnable ended,Consumer<String> error){capture.startStreaming(frame,ended,error);}
          public void stop(){capture.stop();}
        };
        cloudSpeech=new CloudSpeech(apiKey,audio,new CloudSpeech.Listener(){
          public void ready(){runOnUiThread(()->{if(epoch==cloudEpoch&&active){mic.setText("正在听 · 点此结束");hint("边说边出字 · 最长 30 秒");}});}
          public void response(String json,boolean last){
            String text;
            try{
              JSONObject value=new JSONObject(json);int code=value.optInt("code",0);
              if(code!=0&&code!=1000&&code!=20000000)throw new IOException();
              JSONObject result=value.optJSONObject("result");text=result==null?"":result.optString("text");
              if(text.length()>4000)throw new IOException();
            }catch(Exception e){runOnUiThread(()->cloudFinished(epoch,"云端返回异常，已保留文字"));return;}
            final String recognized=text;
            runOnUiThread(()->{if(epoch!=cloudEpoch||!active||!scope.equals(connectionScope)||!target.equals(viewed))return;
              if(!recognized.isEmpty())cloudPending=recognized;
              if(last)renderCloudPreview();else if(!cloudRenderScheduled){cloudRenderScheduled=true;ui.postDelayed(cloudRender,250);}
            });
          }
          public void done(){runOnUiThread(()->cloudFinished(epoch,"已识别，请检查文字再发送"));}
          public void error(String reason){runOnUiThread(()->cloudFinished(epoch,reason));}
        });
        cloudSpeech.start();
      });
    });
  }
  void renderCloudPreview(){
    if(cloudDraft==null||prompt==null)return;
    String text=cloudDraft.updateFor(viewed,cloudPending);if(text==null)return;
    if(!prompt.getText().toString().equals(text)){prompt.setText(text);prompt.setSelection(text.length());}
  }
  void cloudFinished(int epoch,String message){
    if(epoch!=cloudEpoch)return;
    if(message.equals("已识别，请检查文字再发送")&&cloudPending.isEmpty())message="没有识别到文字，请重试；原草稿已保留";
    renderCloudPreview();cancelCloudVoice();saveDraft();if(active&&!isDestroyed())hint(message);
  }
  void cancelCloudVoice(){
    if(cloudDraft==null&&cloudSpeech==null)return;
    renderCloudPreview();cloudEpoch++;ui.removeCallbacks(cloudRender);cloudRenderScheduled=false;
    CloudSpeech old=cloudSpeech;cloudSpeech=null;if(old!=null)old.cancel();
    cloudDraft=null;cloudPending="";voiceWorking=false;
    if(prompt!=null)prompt.setEnabled(true);if(mic!=null)mic.setText("点一下说话");
  }
  String upload(byte[] audio)throws Exception{
    try{
      HttpURLConnection c=(HttpURLConnection)new URL(endpoint("/assistant/voice")).openConnection();
      BridgeNetworkConfig network=bridgeNetwork();c.setRequestMethod("POST");c.setConnectTimeout(network.audioConnectMs);c.setReadTimeout(network.audioReadMs);c.setDoOutput(true);
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
    // This intent is a result view only. Recreating/opening it never repeats the test.
    if(intent.getBooleanExtra("test_alert",false)){showAlertTest(true);return;}
    if(intent.getBooleanExtra("attention",false)&&!connectionState.optString("connection_scope").equals(intent.getStringExtra("connection_scope"))){hint("通知属于旧连接，请刷新待处理");return;}
    boolean attention=intent.getBooleanExtra("attention",false);
    JSONObject notificationRequest=null;try{notificationRequest=new JSONObject(intent.getStringExtra("request"));}catch(Exception ignored){}
    if(attention&&!intent.getBooleanExtra("inbox",false))decisionView.busy=true;
    if(Build.VERSION.SDK_INT>=27){setShowWhenLocked(attention);setTurnScreenOn(attention);}
    else if(attention)getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED|WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
    if(!attention)return;
    if(!temporaryNavigation){
      rememberPosition();temporaryReturn=viewed;temporaryTitle=viewTitle;temporaryReadonly=readOnly;
      temporaryList=listMode;temporaryArchived=archivedList;temporaryAttention=attentionMode;
      temporaryPager=conversationPages.copy();temporaryListCursor=listCursor;temporaryAttentionState=attentionPageState.copy();
      temporaryRows=listMode?new ArrayList<>(rows):null;temporaryAnchor=anchorId();temporaryPosition=list.getFirstVisiblePosition();
      View child=list.getChildAt(0);temporaryOffset=child==null?0:child.getTop();temporaryNavigation=true;
    }
    if(notificationRequest==null)decisionView.cancel(true);else decisionView.bind(notificationRequest.optString("id"),notificationRequest.optString("version"));
    if(intent.getBooleanExtra("inbox",false)){openAttention("");return;}
    String target=intent.getStringExtra("thread_id");if(target==null||target.isEmpty())target=current.optString("thread_id");
    if(!target.isEmpty()&&(!target.equals(viewed)||listMode)){
      cancelCloudVoice();discardCapture=true;capture.stop();voiceWorking=false;saveDraft();
      viewed=target;viewTitle=target.equals(current.optString("thread_id"))?current.optString("title","手表对话"):"手表对话";
      readOnly=false;persistView();showChat(true);
    }
    if(list!=null)list.post(()->list.setSelection(0));
    if(notificationRequest==null)decisionView.cancel(true);else decisionView.bind(notificationRequest.optString("id"),notificationRequest.optString("version"));
    if(notificationRequest!=null&&!intent.getBooleanExtra("inbox",false)){final JSONObject targetRequest=notificationRequest;verifyDecision(targetRequest,result->{decisionView.busy=false;if(AttentionLedger.canAct(result.optString("request_status"),result.optString("delivery_status"),result.optBoolean("can_submit"))){pendingKey="";updateChat();}else {decisionView.busy=true;pendingKey="";updateChat();hint("原请求已处理、变化或无法核实；请刷新待处理");}});}
  }
  @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);applyState(AssistantMonitor.latest);handleAttention(intent);}
  @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){
    super.onRequestPermissionsResult(code,permissions,results);
    if(code==7)hint(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED?"权限已允许，点麦克风开始":"没有录音权限，可以打字");
  }
  @Override protected void onResume(){super.onResume();AssistantMonitor.reconnectSoon();active=true;if(mic!=null&&!voiceWorking)mic.setText("点一下说话");applyState(AssistantMonitor.latest);
    if(listMode&&rows.isEmpty()){if(attentionMode)loadAttention(attentionPage);else loadConversations(listCursor);}
    if(!listMode&&!loadingHistory&&viewed.equals(resumeHistoryThread)&&!viewed.isEmpty()&&!isNew())loadHistory(resumeHistoryOlder,resumeHistoryAutomatic);
    resumeHistoryThread="";
  }
  @Override protected void onPause(){
    rememberPosition();
    cancelCloudVoice();
    if(!listMode&&loadingHistory){resumeHistoryThread=viewed;resumeHistoryOlder=historyReadOlder;resumeHistoryAutomatic=historyReadAutomatic;}
    active=false;decisionView.cancel(false);pendingKey="";ui.removeCallbacks(showLatestPreview);navigation++;loadingHistory=false;historyStamp="";cancelRead();discardCapture=true;capture.stop();saveDraft();super.onPause();
  }
  @Override protected void onStop(){
    if(Build.VERSION.SDK_INT>=27)setTurnScreenOn(false);
    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
    super.onStop();
  }
  @Override public void onBackPressed(){
    if(voiceWorking){cancelCloudVoice();discardCapture=true;capture.stop();voiceWorking=false;saveDraft();}decisionView.cancel(true);pendingKey="";
    if(temporaryNavigation){
      saveDraft();rememberPosition();temporaryNavigation=false;
      if(temporaryList)listMode=true;
      viewed=temporaryReturn;viewTitle=temporaryTitle;readOnly=temporaryReadonly;AssistantMonitor.selectView(viewed);
      if(temporaryList){
        if(temporaryAttention)openAttention(temporaryAttentionState.current);else openList(temporaryArchived);
        navigation++;cancelRead();conversationPages=temporaryPager;listCursor=temporaryListCursor;rows.clear();rows.addAll(temporaryRows);
        adapter.notifyDataSetChanged();if(temporaryAttention){attentionPageState=temporaryAttentionState;updateAttentionPageControls();}else updatePageControls();restoreAnchor(temporaryAnchor,temporaryPosition,temporaryOffset);temporaryRows=null;
      }else showChat(true);
    }else if(listMode)showChat(true);else finish();
  }
  @Override protected void onDestroy(){cancelCloudVoice();active=false;discardCapture=true;capture.stop();cancelRead();controls.shutdown();speech.shutdown();storage.shutdown();readCanceller.shutdown();ui.removeCallbacksAndMessages(null);unregisterReceiver(receiver);super.onDestroy();}
}
