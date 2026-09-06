package com.surprising.aeron.client;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class RealtimeOutboxTest {
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
   int seen=0; while(seen<10000) { byte[] bytes=q.poll(); if(bytes==null){Thread.onSpinWait();continue;}
    assertThat(java.nio.ByteBuffer.wrap(bytes).getInt()).isEqualTo(seen++);
   }
  } catch(Throwable t){failure.set(t);}});
  for(int i=0;i<10000;i++) {while(q.size()>28) Thread.onSpinWait(); q.begin();
   assertThat(q.stage(java.nio.ByteBuffer.allocate(4).putInt(i).array())).isTrue(); q.commit();}
  consumer.join(10000); assertThat(consumer.isAlive()).isFalse();assertThat(failure.get()).isNull();
 }
}
