package com.n.watchremote;
import android.media.*;
import java.io.*;
import java.nio.*;
import java.util.function.Consumer;

/** 16 kHz mono PCM, limited to 30 seconds; WAV stays in memory. */
public class VoiceCapture {
  volatile boolean recording;
  Thread thread;
  @android.annotation.SuppressLint("MissingPermission") // AssistantActivity checks RECORD_AUDIO first.
  public void start(Consumer<byte[]> done,Consumer<String> error){
    if(recording)return;recording=true;
    thread=new Thread(()->{
      AudioRecord recorder=null;
      try{
        int min=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
        recorder=new AudioRecord(MediaRecorder.AudioSource.MIC,16000,AudioFormat.CHANNEL_IN_MONO,
          AudioFormat.ENCODING_PCM_16BIT,Math.max(min,8192));
        if(recorder.getState()!=AudioRecord.STATE_INITIALIZED)throw new IOException("无法打开麦克风");
        ByteArrayOutputStream pcm=new ByteArrayOutputStream();byte[] chunk=new byte[4096];recorder.startRecording();
        while(recording&&pcm.size()<960000){int n=recorder.read(chunk,0,chunk.length);if(n<0)throw new IOException("录音中断");if(n>0)pcm.write(chunk,0,Math.min(n,960000-pcm.size()));}
        byte[] bytes=pcm.toByteArray();ByteBuffer wav=ByteBuffer.allocate(44+bytes.length).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes("US-ASCII")).putInt(bytes.length+36).put("WAVEfmt ".getBytes("US-ASCII"))
          .putInt(16).putShort((short)1).putShort((short)1).putInt(16000).putInt(32000).putShort((short)2)
          .putShort((short)16).put("data".getBytes("US-ASCII")).putInt(bytes.length).put(bytes);
        done.accept(wav.array());
      }catch(Exception e){error.accept(e.getMessage());}
      finally{recording=false;if(recorder!=null){try{recorder.stop();}catch(Exception ignored){}recorder.release();}}
    },"voice-capture");thread.start();
  }
  public void stop(){recording=false;}
}
