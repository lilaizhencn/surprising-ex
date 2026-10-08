package com.surprising.aeron.client;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class RealtimeOutboxTest {
 @Test void wakesWaitingConsumerOnlyAfterCommitAndStopsOnInterrupt() throws Exception {
  var q = new RealtimeOutbox(4, 128);
  var consumed = new java.util.concurrent.CompletableFuture<byte[]>();
  Thread consumer = Thread.ofPlatform().start(() -> {
   while (!Thread.currentThread().isInterrupted()) {
    byte[] bytes = q.poll();
    if (bytes != null) { consumed.complete(bytes); return; }
    q.awaitData(java.util.concurrent.TimeUnit.SECONDS.toNanos(5));
   }
  });
  try {
   long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
   while (consumer.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) Thread.sleep(1);
   assertThat(consumer.getState()).isEqualTo(Thread.State.TIMED_WAITING);
   q.begin(); q.stage(new byte[]{42});
   assertThat(consumed.isDone()).isFalse();
   q.commit();
   assertThat(consumed.get(1, java.util.concurrent.TimeUnit.SECONDS)).containsExactly((byte) 42);
  } finally { consumer.interrupt(); consumer.join(2000); }
  assertThat(consumer.isAlive()).isFalse();
 }

 @Test void publishesOnlyCompleteCallbacksAndAbortsOnOverflow() {
  var q=new RealtimeOutbox(2,128); q.begin(); q.stage(new byte[64]); assertThat(q.poll()).isNull();
  q.stage(new byte[64]); assertThat(q.stage(new byte[1])).isFalse(); q.commit();
  assertThat(q.poll()).isNull(); assertThat(q.droppedBatches()).isEqualTo(1);
  q.begin(); q.stage(new byte[]{1}); q.commit(); assertThat(q.poll()).containsExactly((byte)1);
  q.begin(); q.stage(new byte[]{2}); q.abort(); assertThat(q.poll()).isNull();
 }
 @Test void byteBudgetIncludesPreviouslyCommittedCallbacks() {
  var q=new RealtimeOutbox(10,128); q.begin(); q.stage(new byte[100]); q.commit();
  q.begin(); assertThat(q.stage(new byte[29])).isFalse(); q.commit();
  assertThat(q.poll()).hasSize(100); q.begin(); assertThat(q.stage(new byte[128])).isTrue();q.commit();
  assertThat(q.poll()).hasSize(128); assertThat(q.size()).isZero();
 }
 @Test void preservesVisibilityAndOrderingAcrossThreads() throws Exception {
  var q=new RealtimeOutbox(32,2048); var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
  Thread consumer=Thread.ofPlatform().start(()->{try {
   int seen=0; while(seen<10000) { byte[] bytes=q.poll(); if(bytes==null){q.awaitData(1_000_000_000L);continue;}
    assertThat(java.nio.ByteBuffer.wrap(bytes).getInt()).isEqualTo(seen++);
   }
  } catch(Throwable t){failure.set(t);}});
  for(int i=0;i<10000;i++) {while(q.size()>28) Thread.onSpinWait(); q.begin();
   assertThat(q.stage(java.nio.ByteBuffer.allocate(4).putInt(i).array())).isTrue(); q.commit();}
  consumer.join(10000); assertThat(consumer.isAlive()).isFalse();assertThat(failure.get()).isNull();
 }
}
