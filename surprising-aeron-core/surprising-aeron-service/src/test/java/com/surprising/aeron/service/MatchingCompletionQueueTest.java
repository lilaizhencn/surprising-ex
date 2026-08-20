package com.surprising.aeron.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.aeron.service.matching.CoreMatchingResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class MatchingCompletionQueueTest {

    @Test
    void preservesCompletionOrderAndReportsBoundedOverflow() {
        MatchingCompletionQueue queue = new MatchingCompletionQueue(1);
        CoreMatchingResult result = new CoreMatchingResult(true, "SUCCESS", List.of());

        assertThat(queue.offer(7, result)).isTrue();
        assertThat(queue.offer(8, result)).isFalse();
        assertThat(queue.consumeOverflow()).isTrue();
        assertThat(queue.consumeOverflow()).isFalse();
        assertThat(queue.poll()).isEqualTo(new MatchingCompletionQueue.Completion(7, result));
        assertThat(queue.poll()).isNull();
    }
}
