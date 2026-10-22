package com.n.watchremote;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

public class CloudProtocolTest {
  static void check(boolean b){if(!b)throw new AssertionError();}
  static byte[] server(String text,int flags,boolean gzip)throws Exception{
    byte[] payload=text.getBytes(StandardCharsets.UTF_8);
    if(gzip){ByteArrayOutputStream out=new ByteArrayOutputStream();try(GZIPOutputStream z=new GZIPOutputStream(out)){z.write(payload);}payload=out.toByteArray();}
    ByteBuffer b=ByteBuffer.allocate(8+((flags&1)!=0?4:0)+payload.length).order(ByteOrder.BIG_ENDIAN);
    b.put(new byte[]{0x11,(byte)(0x90|flags),(byte)(0x10|(gzip?1:0)),0});
    if((flags&1)!=0)b.putInt((flags&2)!=0?-3:3);
    return b.putInt(payload.length).put(payload).array();
  }
  public static void main(String[] args)throws Exception{
    check(CloudProtocol.validKey("unit-test-speech-key"));
    for(String invalid:new String[]{"", "key\r\nother", "密钥不是英文", "key with space", "x".repeat(1025)})check(!CloudProtocol.validKey(invalid));
    byte[] request=CloudProtocol.request(1,1,"{}".getBytes(StandardCharsets.UTF_8),false);
    check(request[0]==0x11&&request[1]==0x11&&request[2]==0x11);
    check(ByteBuffer.wrap(request,4,4).getInt()==1);
    byte[] audio=CloudProtocol.request(2,3,new byte[]{1,2,3},true);
    check(audio[1]==0x23&&audio[2]==0x01&&ByteBuffer.wrap(audio,4,4).getInt()==-3);
    String json="{\"result\":{\"text\":\"测试成功\"}}";
    for(boolean compressed:new boolean[]{false,true}){
      CloudProtocol.Response r=CloudProtocol.response(server(json,3,compressed));
      check(r.last&&r.sequence==-3&&r.json.equals(json));
      check(!CloudProtocol.response(server(json,0,compressed)).last);
    }
    byte[] error=ByteBuffer.allocate(12).put(new byte[]{0x11,(byte)0xF0,0,0}).putInt(45000001).putInt(0).array();
    check(CloudProtocol.response(error).error==45000001);
    for(byte[] bad:new byte[][]{new byte[0],new byte[]{0x11,(byte)0x91,0x11,0},new byte[300000]}){
      try{CloudProtocol.response(bad);throw new AssertionError("Malformed response accepted");}catch(IOException expected){}
    }
    byte[] wrongSize=server("{}",0,false);ByteBuffer.wrap(wrongSize,4,4).putInt(10000);
    try{CloudProtocol.response(wrongSize);throw new AssertionError();}catch(IOException expected){}
    try{CloudProtocol.response(server("x".repeat(CloudProtocol.MAX_RESPONSE+1),0,true));throw new AssertionError("gzip bound ignored");}catch(IOException expected){}
    VoiceDraft draft=new VoiceDraft("已有草稿");
    check(draft.update("你").equals("已有草稿\n你"));
    check(draft.update("你好").equals("已有草稿\n你好"));
    check(draft.update("").equals("已有草稿\n你好"));
    check(draft.text().equals("已有草稿\n你好"));
    check(new VoiceDraft("").update("单独语音").equals("单独语音"));
    VoiceDraft bound=new VoiceDraft("draft A","conversation-A");
    check(bound.updateFor("conversation-A","first result").equals("draft A\nfirst result"));
    check(bound.updateFor("conversation-B","late result for another view")==null);
    check(bound.updateFor("conversation-A","").equals("draft A\nfirst result"));
    check(bound.updateFor("conversation-A","final result").equals("draft A\nfinal result"));
    System.out.println("Cloud protocol framing, malformed input, gzip bounds and draft replacement passed");
  }
}
