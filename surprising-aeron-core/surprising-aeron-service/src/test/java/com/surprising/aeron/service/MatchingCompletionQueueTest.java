package com.surprising.aeron.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.aeron.service.matching.CoreMatchingResult;
import org.junit.jupiter.api.Test;

class MatchingCompletionQueueTest {

    @Test
    void sequenceMailboxWakesTheOwnerWithoutAPerCommandFuture() throws Exception {
        MatchingCompletionQueue queue = new MatchingCompletionQueue(4);
        java.util.concurrent.atomic.AtomicBoolean published = new java.util.concurrent.atomic.AtomicBoolean();
        Thread waiter = Thread.ofVirtual().start(() -> published.set(
                queue.awaitSequence(1, java.util.concurrent.TimeUnit.SECONDS.toNanos(1))));

        queue.offer(new CoreMatchingResult(true, "SUCCESS").withCoreSequence(1));
        waiter.join();

        assertThat(published).isTrue();
        assertThat(queue.poll(1).nativeCommand().coreSequence()).isEqualTo(1);
    }

    @Test
    void preservesOutOfOrderCompletionsByCoreSequence() {
        MatchingCompletionQueue queue = new MatchingCompletionQueue(4);
        CoreMatchingResult result = new CoreMatchingResult(true, "SUCCESS");

        assertThat(queue.offer(result.withCoreSequence(8))).isTrue();
        assertThat(queue.offer(result.withCoreSequence(7))).isTrue();
        assertThat(queue.poll(7).nativeCommand().coreSequence()).isEqualTo(7);
        assertThat(queue.poll(8).nativeCommand().coreSequence()).isEqualTo(8);
        assertThat(queue.depth()).isZero();
    }

    @Test
    void reportsBoundedSequenceSlotOverflow() {
        MatchingCompletionQueue queue = new MatchingCompletionQueue(1);
        CoreMatchingResult result = new CoreMatchingResult(true, "SUCCESS");

        CoreMatchingResult first = result.withCoreSequence(7);
        assertThat(queue.offer(first)).isTrue();
        assertThat(queue.offer(result.withCoreSequence(8))).isFalse();
        assertThat(queue.consumeOverflow()).isTrue();
        assertThat(queue.consumeOverflow()).isFalse();
        assertThat(queue.poll(7)).isSameAs(first);
        assertThat(queue.poll(8)).isNull();
    }
}
