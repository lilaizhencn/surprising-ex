package com.surprising.aeron.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.aeron.service.matching.CoreMatchingResult;
import org.junit.jupiter.api.Test;

class MatchingCompletionQueueTest {

    @Test
    void publicationCursorWakesTheOwnerWithoutAPerCommandFuture() throws Exception {
        MatchingCompletionQueue queue = new MatchingCompletionQueue(4);
        long observed = queue.publicationCursor();
        java.util.concurrent.atomic.AtomicBoolean published = new java.util.concurrent.atomic.AtomicBoolean();
        Thread waiter = Thread.ofVirtual().start(() -> published.set(
                queue.awaitPublication(observed, java.util.concurrent.TimeUnit.SECONDS.toNanos(1))));

        queue.offer(new CoreMatchingResult(true, "SUCCESS").withCoreSequence(1));
        waiter.join();

        assertThat(published).isTrue();
        assertThat(queue.publicationCursor()).isEqualTo(observed + 1);
    }

    @Test
    void preservesCompletionOrderAndReportsBoundedOverflow() {
        MatchingCompletionQueue queue = new MatchingCompletionQueue(1);
        CoreMatchingResult result = new CoreMatchingResult(true, "SUCCESS");

        CoreMatchingResult first = result.withCoreSequence(7);
        assertThat(queue.offer(first)).isTrue();
        assertThat(queue.offer(result.withCoreSequence(8))).isFalse();
        assertThat(queue.consumeOverflow()).isTrue();
        assertThat(queue.consumeOverflow()).isFalse();
        assertThat(queue.poll()).isSameAs(first);
        assertThat(queue.poll()).isNull();
    }
}
