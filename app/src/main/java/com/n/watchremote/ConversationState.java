package com.n.watchremote;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
/** Connection identity is supplied by the bridge; unknown identities never persist across sessions. */
final class ConversationState {
 static String normalize(String base){
  try{URI u=new URI(base.trim());String scheme=u.getScheme().toLowerCase(java.util.Locale.ROOT),host=u.getHost().toLowerCase(java.util.Locale.ROOT);int port=u.getPort();
   if((scheme.equals("https")&&port==443)||(scheme.equals("http")&&port==80))port=-1;
   String path=u.getPath()==null?"":u.getPath().replaceAll("/+$","");return new URI(scheme,null,host,port,path,null,null).toString();
  }catch(Exception e){return base.trim().replaceAll("/+$","");}
 }
 static String hash(String value){try{byte[] digest=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:digest)out.append(String.format("%02x",b&255));return out.toString();}catch(Exception e){throw new IllegalStateException(e);}}
 static String scope(String base,String identity,String session){return hash(normalize(base)+"\n"+(identity.isEmpty()?"session:"+session:"identity:"+identity));}
 static String recoveryScope(String base,String identity){return hash(normalize(base)+"\n"+(identity.isEmpty()?"unassigned-recovery":"identity:"+identity));}
 static String initialTarget(String identity,String normal,String main,String legacy){return identity.isEmpty()?legacy:!normal.isEmpty()?normal:main;}
 static java.util.List<String> recoveryRecords(java.util.Map<String,?> values){
  java.util.List<String> out=new java.util.ArrayList<>();
  for(java.util.Map.Entry<String,?> item:values.entrySet()){
   String key=item.getKey();Object value=item.getValue();if(!(value instanceof String)||((String)value).isEmpty())continue;
   if(key.equals("aiDraft")||key.equals("pendingPayload")||key.equals("followRequestId")||key.equals("followDraft")||key.startsWith("orphan-receipt-v1:")||key.startsWith("draft:")||key.startsWith("draft-v1:")||key.startsWith("pending:")||key.startsWith("operation:")||key.startsWith("connection-v1:")&&(key.contains(":pending:")||key.contains(":operation:")||key.endsWith(":followRequestId")||key.endsWith(":followDraft"))||key.startsWith("recovery-v2:")&&(key.contains(":draft:")||key.contains(":pending:")||key.contains(":operation:")))out.add(key);
  }
  java.util.Collections.sort(out);return out;
 }
 static boolean sameDraft(String draft,String submitted){return draft.equals(submitted);}
 static boolean canStop(String target,String turn,String current,String currentTurn,boolean running,boolean busy){return !busy&&running&&!target.isEmpty()&&!turn.isEmpty()&&target.equals(current)&&turn.equals(currentTurn);}
}
