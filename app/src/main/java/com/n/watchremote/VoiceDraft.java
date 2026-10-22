package com.n.watchremote;
/** Full hypotheses replace the prior hypothesis, never the pre-recording draft. */
final class VoiceDraft {
  private final String base,owner;
  private String hypothesis="";
  VoiceDraft(String base){this(base,"");}
  VoiceDraft(String base,String owner){this.base=base;this.owner=owner;}
  String updateFor(String viewed,String full){return owner.equals(viewed)?update(full):null;}
  String update(String full){if(!full.isEmpty())hypothesis=full;return text();}
  String text(){return base.isEmpty()?hypothesis:hypothesis.isEmpty()?base:base+"\n"+hypothesis;}
}
