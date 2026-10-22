package com.n.watchremote;
import okhttp3.*;
import okio.ByteString;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** One direct TLS connection per user recording; no reconnect or audio replay. */
final class CloudSpeech {
  static final String ENDPOINT="wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async";
  private static final OkHttpClient CLIENT=new OkHttpClient.Builder()
    .connectTimeout(10,TimeUnit.SECONDS).readTimeout(0,TimeUnit.SECONDS)
    .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
    .pingInterval(15,TimeUnit.SECONDS).build();
  interface Audio {void start(Consumer<byte[]> frames,Runnable ended,Consumer<String> error);void stop();}
  interface Listener {void ready();void response(String json,boolean last);void done();void error(String reason);}
  private final OkHttpClient client;private final String endpoint,key;
  private final Audio audio;private final Listener listener;
  private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
  private final AtomicBoolean closed=new AtomicBoolean(),started=new AtomicBoolean(),endSent=new AtomicBoolean(),stopRequested=new AtomicBoolean();
  private volatile WebSocket socket;private volatile ScheduledFuture<?> deadline;
  private boolean recording;private byte[] pending;private int sequence=1;
  CloudSpeech(String key,Audio audio,Listener listener){this(CLIENT,ENDPOINT,key,audio,listener);}
  // Explicit localhost injection is used by JVM transport tests; production uses ENDPOINT only.
  CloudSpeech(OkHttpClient client,String endpoint,String key,Audio audio,Listener listener){
    this.client=client;this.endpoint=endpoint;this.key=key;this.audio=audio;this.listener=listener;
  }
  synchronized void start(){
    if(closed.get()||!started.compareAndSet(false,true))return;
    if(!CloudProtocol.validKey(key)){fail("请先配置有效的语音 API Key");return;}
    deadline=timer.schedule(()->fail("连接语音服务超时，草稿已保留"),12,TimeUnit.SECONDS);
    Request request=new Request.Builder().url(endpoint).header("X-Api-Key",key)
      .header("X-Api-Resource-Id","volc.seedasr.sauc.duration")
      .header("X-Api-Connect-Id",UUID.randomUUID().toString()).build();
    socket=client.newWebSocket(request,new WebSocketListener(){
      @Override public void onOpen(WebSocket ws,Response response){opened(ws);}
      @Override public void onMessage(WebSocket ws,ByteString data){received(data.toByteArray());}
      @Override public void onMessage(WebSocket ws,String data){fail("语音服务返回格式异常，草稿已保留");}
      @Override public void onFailure(WebSocket ws,Throwable error,Response response){
        if(response!=null&&(response.code()==401||response.code()==403))fail("语音鉴权失败，请检查 API Key 和模型开通状态");
        else fail("语音连接中断，已保留文字；不会自动重传录音");
      }
      @Override public void onClosed(WebSocket ws,int code,String reason){if(!closed.get())fail("语音连接已关闭，已保留文字");}
      @Override public void onClosing(WebSocket ws,int code,String reason){ws.close(code,reason);if(!closed.get())fail("语音连接已关闭，已保留文字");}
    });
  }
  private synchronized void opened(WebSocket ws){
    if(closed.get()){ws.cancel();return;}socket=ws;
    if(stopRequested.get()){finish(false,null);return;}
    if(deadline!=null)deadline.cancel(false);
    String config="{\"user\":{\"uid\":\"wristcodex\"},\"audio\":{\"format\":\"pcm\",\"codec\":\"raw\",\"rate\":16000,\"bits\":16,\"channel\":1},\"request\":{\"model_name\":\"bigmodel\",\"enable_itn\":true,\"enable_punc\":true,\"enable_nonstream\":true,\"result_type\":\"full\"}}";
    try{if(!ws.send(ByteString.of(CloudProtocol.request(1,sequence++,config.getBytes(StandardCharsets.UTF_8),false))))throw new IOException();}
    catch(IOException e){fail("无法开始语音识别，请重试");return;}
    recording=true;
    audio.start(this::frame,this::audioEnded,reason->fail("无法录音，请检查麦克风权限"));
    if(closed.get())return;
    listener.ready();deadline=timer.schedule(this::stop,30,TimeUnit.SECONDS);
  }
  private synchronized void frame(byte[] bytes){
    if(closed.get()||endSent.get())return;
    if(bytes.length==0||bytes.length>6400){fail("录音分片异常，已保留文字");return;}
    // Retain at most one 200 ms frame so the real final frame carries the end flag.
    if(pending!=null&&!sendAudio(pending,false))return;
    pending=bytes;
  }
  private boolean sendAudio(byte[] bytes,boolean last){
    try{
      if(socket==null||socket.queueSize()>128*1024||!socket.send(ByteString.of(CloudProtocol.request(2,sequence++,bytes,last)))){
        fail("上传跟不上录音，已停止并保留文字；不会重传");return false;
      }
      return true;
    }catch(IOException e){fail("语音数据发送失败，已保留文字");return false;}
  }
  private synchronized void audioEnded(){
    if(closed.get()||!endSent.compareAndSet(false,true))return;
    recording=false;if(deadline!=null)deadline.cancel(false);
    if(!sendAudio(pending==null?new byte[0]:pending,true))return;
    pending=null;deadline=timer.schedule(()->fail("语音收尾超时，已保留识别文字"),10,TimeUnit.SECONDS);
  }
  synchronized void stop(){
    if(closed.get()||!stopRequested.compareAndSet(false,true))return;
    if(recording)audio.stop();else finish(false,null);
  }
  synchronized void cancel(){finish(true,null);}
  private synchronized void received(byte[] bytes){
    if(closed.get())return;
    try{
      CloudProtocol.Response result=CloudProtocol.response(bytes);
      if(result.error!=0){fail("语音服务拒绝请求，请检查开通状态、密钥和额度");return;}
      listener.response(result.json,result.last);
      if(result.last)finish(false,null);
    }catch(IOException e){fail("语音响应无法解析，已保留文字");}
  }
  private synchronized void fail(String message){finish(false,message);}
  private void finish(boolean silent,String error){
    if(!closed.compareAndSet(false,true))return;
    pending=null;audio.stop();timer.shutdownNow();
    if(socket!=null){if(error==null&&!silent)socket.close(1000,"done");else socket.cancel();}
    if(!silent){if(error==null)listener.done();else listener.error(error);}
  }
}
