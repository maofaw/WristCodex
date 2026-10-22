package com.n.watchremote;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;

/** Bounded SAUC v3 framing. No credentials or Android runtime dependencies. */
final class CloudProtocol {
  static final int MAX_RESPONSE=256*1024;
  static boolean validKey(String key){
    if(key==null||key.isEmpty()||key.length()>1024)return false;
    for(int i=0;i<key.length();i++)if(key.charAt(i)<33||key.charAt(i)>126)return false;
    return true;
  }
  static final class Response {
    final String json;final int sequence,error;final boolean last;
    Response(String json,int sequence,int error,boolean last){this.json=json;this.sequence=sequence;this.error=error;this.last=last;}
  }
  static byte[] request(int type,int sequence,byte[] payload,boolean last)throws IOException{
    if((type!=1&&type!=2)||sequence<=0||payload.length>32000)throw new IOException("Invalid outgoing frame");
    ByteArrayOutputStream out=new ByteArrayOutputStream();
    try(GZIPOutputStream z=new GZIPOutputStream(out)){z.write(payload);}
    byte[] data=out.toByteArray();
    return ByteBuffer.allocate(12+data.length).order(ByteOrder.BIG_ENDIAN)
      .put((byte)0x11).put((byte)((type<<4)|(last?3:1))).put((byte)(type==1?0x11:0x01)).put((byte)0)
      .putInt(last?-sequence:sequence).putInt(data.length).put(data).array();
  }
  static Response response(byte[] frame)throws IOException{
    if(frame.length<8||frame.length>MAX_RESPONSE)throw new IOException("Invalid response size");
    ByteBuffer b=ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
    int first=b.get()&255,kind=b.get()&255,format=b.get()&255;b.get();
    int header=(first&15)*4,type=kind>>4,flags=kind&15,compression=format&15;
    if(first>>4!=1||header<4||header>frame.length-4||(type!=9&&type!=15)||flags>3||compression>1)
      throw new IOException("Unsupported response header");
    b.position(header);int sequence=0,error=0;
    if(type==15){if(b.remaining()<8)throw new IOException("Truncated error");error=b.getInt();}
    else if((flags&1)!=0){if(b.remaining()<8)throw new IOException("Truncated sequence");sequence=b.getInt();}
    if(b.remaining()<4)throw new IOException("Missing payload length");
    int length=b.getInt();if(length<0||length!=b.remaining())throw new IOException("Invalid payload length");
    byte[] payload=new byte[length];b.get(payload);
    if(compression==1){
      ByteArrayOutputStream out=new ByteArrayOutputStream();
      try(InputStream in=new GZIPInputStream(new ByteArrayInputStream(payload))){
        byte[] chunk=new byte[4096];int n;
        while((n=in.read(chunk))!=-1){if(out.size()+n>MAX_RESPONSE)throw new IOException("Expanded response too large");out.write(chunk,0,n);}
      }
      payload=out.toByteArray();
    }
    if(type==9&&(format>>4)!=1)throw new IOException("Expected JSON response");
    return new Response(new String(payload,StandardCharsets.UTF_8),sequence,error,(flags&2)!=0);
  }
}
