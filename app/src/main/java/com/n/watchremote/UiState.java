package com.n.watchremote;

/** Bounded, framework-free decisions shared by the native UI and host-JVM tests. */
final class UiState {
  private UiState(){}
  static int panelLimit(int cap,int available,boolean bounded){return bounded?Math.min(cap,available):cap;}
  static boolean chatShortcuts(boolean list,boolean attention){return !list&&!attention;}
  static String rowSource(boolean cached,boolean readonly,String source){return cached?"离线记录":readonly?"只读 · 可尝试连接":source.equals("shared")?"电脑 · 可续聊":"手表";}
  static final class DecisionView {
    boolean busy;long owner;String request="",clickedId="",clickedVersion="";
    void bind(String id,String version){clickedId=id;clickedVersion=version;}
    boolean matches(String id,String version){return clickedId.isEmpty()||(clickedId.equals(id)&&clickedVersion.equals(version));}
    long begin(String request){this.request=request;busy=true;return ++owner;}
    boolean finish(long ticket){if(ticket!=owner)return false;busy=false;return true;}
    void cancel(boolean leaveNotification){owner++;busy=false;request="";if(leaveNotification){clickedId="";clickedVersion="";}}
  }
  static int effortIndex(String current,String[] values){
    for(int i=0;i<values.length;i++)if(values[i].equals(current))return i+1;
    return 0;
  }
  static String effortValue(int index,String[] values){return index>0&&index<=values.length?values[index-1]:null;}
  static int swipePage(float dx,float dy,float startX,float screenWidth){
    if(startX<24||startX>screenWidth-24||Math.abs(dx)<48||Math.abs(dx)<=1.5f*Math.abs(dy))return 0;
    return dx<0?1:-1;
  }
  static final class Pager {
    final java.util.ArrayList<String> cursors=new java.util.ArrayList<>();
    int page,target;boolean loading,next,reset;
    Pager(){cursors.add("");}
    static Pager cached(String cursor,String next){Pager pager=new Pager();pager.cursors.set(0,cursor);pager.loading=true;pager.success(next);return pager;}
    Pager copy(){Pager out=new Pager();out.cursors.clear();out.cursors.addAll(cursors);out.page=page;out.target=page;out.next=next;return out;}
    boolean canPrevious(){return !loading&&page>0;}
    boolean canNext(){return !loading&&next;}
    String first(){if(loading)return null;loading=true;target=0;reset=true;return "";}
    String begin(int direction){
      if(direction!=1&&direction!=-1)return null;
      if(direction>0?!canNext():!canPrevious())return null;
      target=page+direction;
      if(target<0||target>=cursors.size())return null;
      loading=true;reset=false;return cursors.get(target);
    }
    void success(String following){
      if(!loading)return;
      if(reset){cursors.clear();cursors.add("");}
      page=target;next=following!=null&&!following.isEmpty()&&!following.equals("null");
      while(cursors.size()>page+1)cursors.remove(cursors.size()-1);
      if(next)cursors.add(following);
      loading=false;reset=false;
    }
    void failed(){loading=false;reset=false;}
    void refresh(String following){if(!loading){loading=true;target=page;reset=false;}success(following);}
  }
  static boolean canRememberAnchor(String viewed,String rowOwner,boolean listMode){return !listMode&&!viewed.isEmpty()&&viewed.equals(rowOwner);}
  static final class AttentionPage {
    final String current,next;final int total;
    AttentionPage(String current,String next,int total){this.current=current;this.next=next.equals("null")?"":next;this.total=total;}
    AttentionPage copy(){return new AttentionPage(current,next,total);}
    boolean hasNext(){return !next.isEmpty();}
    String countLabel(){return total+" 项等待处理";}
  }
  static String answerText(String value){
    return value.trim().isEmpty()?null:value;
  }
  static boolean canSubmit(boolean shared,boolean blank,boolean running,boolean canSend,boolean canSteer){
    return !shared||blank||(running?canSteer:canSend);
  }
  static long retryDelay(int failures){
    return BridgeNetworkConfig.DEFAULT.retryDelay(failures);
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
