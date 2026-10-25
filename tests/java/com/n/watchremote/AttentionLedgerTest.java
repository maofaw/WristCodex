package com.n.watchremote;
import java.io.*;
import java.util.*;
public final class AttentionLedgerTest {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 public static void main(String[] args)throws Exception{
  File f=File.createTempFile("attention", ".bin");f.delete();
  AttentionLedger l=new AttentionLedger(f);l.receive("s",Arrays.asList("a","b"));
  check(l.eligible("s",Arrays.asList("a","b")).size()==2,"received is not posted");
  l=new AttentionLedger(f);check(l.eligible("s",Arrays.asList("a")).size()==1,"restart before post remains eligible");
  l.posted("s",Arrays.asList("a"));l=new AttentionLedger(f);
  check(l.eligible("s",Arrays.asList("a","b")).equals(Arrays.asList("b")),"durable posted suppresses only posted subset");
  l.receive("s",Collections.emptyList());check(l.eligible("s",Arrays.asList("b")).size()==1,"incomplete absence never completes");
  check(l.eligible("new-scope",Arrays.asList("a")).size()==1,"unknown new scope cannot inherit suppression");
  AttentionLedger.Batch batch=new AttentionLedger.Batch("s","r");batch.add("s","r",Arrays.asList("a"),"next");
  check(!batch.ready(),"partial pages cannot post");
  try{batch.add("s","changed",Arrays.asList("b"),"");throw new AssertionError("mixed revision accepted");}catch(IOException expected){}
  batch=new AttentionLedger.Batch("s","r");for(int i=0;i<3;i++){List<String> ids=new ArrayList<>();for(int j=0;j<20;j++)ids.add("r"+(i*20+j));batch.add("s","r",ids,i==2?"":"cursor"+i);}
  check(batch.ready()&&batch.ids.size()==60,"all pages combine once");
  check(!AttentionLedger.canAct("pending","unknown",true),"unknown cannot resend");
  check(!AttentionLedger.canAct("stale","not_sent",true),"stale cannot act");
  check(AttentionLedger.canAct("pending","rejected",true),"explicit rejection may be manually retried");
  final int[] calls={0};
  check(!l.deliver("s",Arrays.asList("b"),()->false,()->calls[0]++),"muted receive cannot submit");
  check(calls[0]==0&&l.eligible("s",Arrays.asList("b")).size()==1,"muted does not consume eligibility");
  try{l.deliver("s",Arrays.asList("b"),()->true,()->{throw new IOException("OS submission failed");});throw new AssertionError("failed sink accepted");}catch(IOException expected){}
  l=new AttentionLedger(f);check(l.eligible("s",Arrays.asList("b")).size()==1,"failed sink remains eligible after restart");
  check(l.deliver("s",Arrays.asList("b","c"),()->true,()->calls[0]++),"enable merges pending once");
  check(!l.deliver("s",Arrays.asList("b","c"),()->true,()->calls[0]++ )&&calls[0]==1,"repeat snapshot cannot repost");
  l.completed("s","b");l=new AttentionLedger(f);check(l.eligible("s",Arrays.asList("b")).isEmpty(),"positive completion remains terminal");
  for(int i=0;i<270;i++)l.completed("s","terminal"+i);
  check(l.records.size()<270&&l.eligible("s",Arrays.asList("a")).isEmpty(),"prune terminals preserves pending posted records");
  List<String> many=new ArrayList<>();for(int i=0;i<60;i++)many.add("slow"+i);
  AttentionLedger.Validation progress=new AttentionLedger.Validation("s","r",many,many);long[] clock={0};int[] checks={0};
  boolean ready=progress.advance(20000,()->clock[0],id->{checks[0]++;clock[0]+=500;return true;},()->true);
  check(!ready&&progress.next==40,"slow stable batch retains prefix without posting partial batch");
  check(progress.matches("s","r",many)&&!progress.matches("s","r2",many),"changed revision invalidates checkpoint");
  clock[0]=0;check(progress.advance(20000,()->clock[0],id->{checks[0]++;clock[0]+=500;return true;},()->true),"stable batch eventually ready");
  check(progress.valid.size()==60&&checks[0]==60,"validated prefix is not rechecked each poll");
  int[] merged={0};File slowFile=File.createTempFile("ledger-slow", ".bin");slowFile.delete();AttentionLedger slow=new AttentionLedger(slowFile);
  check(slow.deliver("s",progress.valid,()->true,()->merged[0]++)&&merged[0]==1,"complete validation submits one merged notification");slowFile.delete();
  AttentionLedger.Validation cancelled=new AttentionLedger.Validation("s","r",many,many);boolean[] gate={true};
  check(!cancelled.advance(100,()->0,id->{gate[0]=false;return true;},()->gate[0])&&cancelled.next==0,"cancelled status callback cannot advance checkpoint");
  AttentionLedger.Validation suspended=new AttentionLedger.Validation("s","r",Arrays.asList("delayed"),Arrays.asList("delayed"));
  long[] suspendedClock={0};
  check(!suspended.advance(20000,()->suspendedClock[0],id->{suspendedClock[0]=120000;return true;},()->true),"a status check returning after the absolute deadline cannot authorize a notification");
  check(suspended.next==0&&suspended.valid.isEmpty(),"an overrun does not retain an unverified stale prefix");
  check(suspended.advance(140000,()->suspendedClock[0],id->false,()->true)&&suspended.valid.isEmpty(),"the next pass rechecks the delayed request and respects computer-side completion");
  java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);boolean[] accepted={false};
  Thread listener=new Thread(()->{try{AttentionLedger.acceptThenReconcile(()->accepted[0]=true,()->{entered.countDown();release.await();});}catch(Exception e){throw new RuntimeException(e);}});listener.start();
  check(entered.await(2,java.util.concurrent.TimeUnit.SECONDS)&&accepted[0],"accepted UI update delivered before stalled status reconciliation");release.countDown();listener.join();
  File fullFile=File.createTempFile("ledger-full", ".bin");fullFile.delete();
  AttentionLedger full=new AttentionLedger(fullFile);List<String> capacity=new ArrayList<>();for(int i=0;i<8192;i++)capacity.add("live"+i);
  full.receive("s",capacity);byte[] before=java.nio.file.Files.readAllBytes(fullFile.toPath());
  for(int i=0;i<5;i++){try{full.receive("other"+i,Arrays.asList("new"));throw new AssertionError("capacity accepted");}catch(IOException expected){}check(full.records.size()==8192,"rejected receive must not grow memory");}
  try{full.completed("other","new");throw new AssertionError("completion over capacity");}catch(IOException expected){}
  check(full.records.size()==8192&&Arrays.equals(before,java.nio.file.Files.readAllBytes(fullFile.toPath())),"capacity rejection retains disk and live unresolved rows");fullFile.delete();
  f.delete();System.out.println("Attention ledger lifecycle, restart, pagination and unknown guards passed");
 }
}
