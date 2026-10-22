package com.n.watchremote;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import okio.ByteString;

public class CloudSpeechTest {
  static class Mic implements CloudSpeech.Audio {
    java.util.function.Consumer<byte[]> frames;Runnable ended;int starts,stops;
    public void start(java.util.function.Consumer<byte[]> frames,Runnable ended,java.util.function.Consumer<String> error){this.frames=frames;this.ended=ended;starts++;}
    public void stop(){stops++;if(ended!=null)ended.run();}
  }
  static class Results implements CloudSpeech.Listener {
    final CountDownLatch ready=new CountDownLatch(1),finished=new CountDownLatch(1);
    int successes,errors;String failure;
    public void ready(){ready.countDown();}
    public void response(String json,boolean last){}
    public void done(){successes++;finished.countDown();}
    public void error(String reason){failure=reason;errors++;finished.countDown();}
  }
  @Test public void captureStartsAfterHandshakeAndFinishIsSentOnce()throws Exception{
    try(MockWebServer server=new MockWebServer()){
      List<byte[]> packets=Collections.synchronizedList(new ArrayList<>());
      CountDownLatch received=new CountDownLatch(3);
      server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener(){
        public void onMessage(WebSocket socket,ByteString data){packets.add(data.toByteArray());received.countDown();}
      }));server.start();Mic mic=new Mic();Results result=new Results();
      CloudSpeech stream=new CloudSpeech(new OkHttpClient(),server.url("/stream").toString(),"unit-test-key",mic,result);
      assertEquals(0,mic.starts);stream.start();assertTrue(result.ready.await(5,TimeUnit.SECONDS));
      assertEquals(1,mic.starts);
      mic.frames.accept(new byte[6400]);mic.frames.accept(new byte[6400]);stream.stop();stream.stop();
      assertTrue(received.await(5,TimeUnit.SECONDS));
      assertEquals(3,packets.size());assertEquals(0x11,packets.get(0)[1]&255);
      assertEquals(0x21,packets.get(1)[1]&255);assertEquals(0x23,packets.get(2)[1]&255);
      RecordedRequest request=server.takeRequest();assertEquals("unit-test-key",request.getHeader("X-Api-Key"));
      stream.cancel();assertEquals(0,result.errors);assertEquals(0,result.successes);
    }
  }
  @Test public void rejectedAuthenticationNeverRecordsAndNeverRetries()throws Exception{
    try(MockWebServer server=new MockWebServer()){
      server.enqueue(new MockResponse().setResponseCode(401));server.start();Mic mic=new Mic();Results result=new Results();
      CloudSpeech stream=new CloudSpeech(new OkHttpClient(),server.url("/stream").toString(),"unit-test-key",mic,result);
      stream.start();assertTrue(result.finished.await(5,TimeUnit.SECONDS));
      assertEquals(0,mic.starts);assertEquals(1,result.errors);assertEquals(1,server.getRequestCount());
      assertFalse(result.failure.contains("unit-test-key"));stream.cancel();
    }
  }
  @Test public void cancellationWhileConnectingSuppressesLateCallbacks()throws Exception{
    try(MockWebServer server=new MockWebServer()){
      server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener(){}));server.start();
      Mic mic=new Mic();Results result=new Results();CloudSpeech stream=new CloudSpeech(new OkHttpClient(),server.url("/stream").toString(),"unit-test-key",mic,result);
      stream.cancel();stream.start();assertEquals(0,mic.starts);assertEquals(0,result.errors);
    }
  }
  @Test public void finalResponseCompletesExactlyOnce()throws Exception{
    try(MockWebServer server=new MockWebServer()){
      byte[] body="{\"result\":{\"text\":\"hello\"}}".getBytes(StandardCharsets.UTF_8);
      byte[] finalPacket=java.nio.ByteBuffer.allocate(12+body.length).put(new byte[]{0x11,(byte)0x93,0x10,0}).putInt(-2).putInt(body.length).put(body).array();
      server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener(){
        public void onMessage(WebSocket ws,ByteString data){if((data.getByte(1)&15)==3)ws.send(ByteString.of(finalPacket));}
        public void onClosing(WebSocket ws,int code,String reason){ws.close(code,reason);}
      }));server.start();Mic mic=new Mic();Results result=new Results();
      CloudSpeech stream=new CloudSpeech(new OkHttpClient(),server.url("/stream").toString(),"unit-test-key",mic,result);
      stream.start();assertTrue(result.ready.await(5,TimeUnit.SECONDS));mic.frames.accept(new byte[6400]);stream.stop();
      assertTrue(result.finished.await(5,TimeUnit.SECONDS));stream.stop();stream.cancel();
      assertEquals(1,result.successes);assertEquals(0,result.errors);
    }
  }
  @Test public void unexpectedTextResponseStopsCapture()throws Exception{
    try(MockWebServer server=new MockWebServer()){
      server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener(){
        public void onMessage(WebSocket ws,ByteString data){ws.send("unexpected text");}
      }));server.start();Mic mic=new Mic();Results result=new Results();
      CloudSpeech stream=new CloudSpeech(new OkHttpClient(),server.url("/stream").toString(),"unit-test-key",mic,result);
      stream.start();assertTrue(result.finished.await(5,TimeUnit.SECONDS));assertEquals(1,result.errors);assertTrue(mic.stops>0);stream.cancel();
    }
  }
}
