package com.n.watchremote;

/** Bounded, framework-free decisions shared by the native UI and host-JVM tests. */
final class UiState {
  private UiState(){}
  static String answerText(String value){
    return value.trim().isEmpty()?null:value;
  }
  static boolean canSubmit(boolean shared,boolean blank,boolean running,boolean canSend,boolean canSteer){
    return !shared||blank||(running?canSteer:canSend);
  }
  static long retryDelay(int failures){
    if(failures<=1)return 1000;
    if(failures>=5)return 15000;
    return 1000L<<(failures-1);
  }
  static final class History {
    boolean olderWindow,deferred;
    void loaded(boolean older){olderWindow=older;deferred=false;}
    boolean shouldRefresh(boolean empty,boolean following){
      deferred=!empty&&(olderWindow||!following);return !deferred;
    }
  }
  static final class Preview {
    String threadId="",turnId="",itemId="",text="";
    long version=-1,renderedAt;
    boolean complete,clipped;
    void rendered(long now){renderedAt=now;}
    long renderDelay(String thread,String turn,String item,long next,boolean finished,long now){
      boolean same=thread.equals(threadId)&&turn.equals(turnId)&&item.equals(itemId);
      if(!same||finished||next<=version||(complete&&!finished))return 0;
      return Math.max(0,2000-(now-renderedAt));
    }
    void clear(){threadId="";turnId="";itemId="";text="";version=-1;renderedAt=0;complete=false;clipped=false;}
    boolean update(String viewed,String expectedTurn,String thread,String turn,String item,
                   String body,long next,boolean finished,boolean truncated){
      if(viewed.isEmpty()||!viewed.equals(thread)||expectedTurn.isEmpty()||!expectedTurn.equals(turn)
          ||item.isEmpty()||next<0||body.isEmpty())return false;
      boolean same=thread.equals(threadId)&&turn.equals(turnId)&&item.equals(itemId);
      if(same&&(next<=version||(complete&&!finished)))return false;
      threadId=thread;turnId=turn;itemId=item;version=next;
      int end=Math.min(1000,body.length());
      if(end<body.length()&&Character.isHighSurrogate(body.charAt(end-1)))end--;
      text=body.substring(0,end);complete=finished;clipped=truncated||end<body.length();return true;
    }
  }
}
