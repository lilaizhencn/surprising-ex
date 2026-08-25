package com.surprising.aeron.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.aeron.service.matching.CoreMatchingResult;
import org.junit.jupiter.api.Test;

class MatchingCompletionQueueTest {

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
