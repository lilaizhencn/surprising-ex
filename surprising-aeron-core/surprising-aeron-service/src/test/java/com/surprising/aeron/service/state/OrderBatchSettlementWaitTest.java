package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;

class OrderBatchSettlementWaitTest {
    @Test
    void incompleteSettlementExpiresWithoutPublishingCompletionOrRecycling() throws Exception {
        try (var runtime = new TradingRuntimeState()) {
            var event = incompleteEvent();
            assertThatThrownBy(() -> runtime.settlements.awaitOrderBatchMatcherSettlement(event, System.nanoTime() - 1))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("timed out");
            assertThat(event.complete()).isFalse();
            assertThatThrownBy(() -> runtime.releaseMatcherSettlement(event))
                    .hasMessageContaining("cannot recycle an incomplete");
        }
    }

    @Test
    void interruptionPreservesTheInterruptAndIncompleteFence() throws Exception {
        try (var runtime = new TradingRuntimeState()) {
            var event = incompleteEvent();
            Thread.currentThread().interrupt();
            try {
                assertThatThrownBy(() -> runtime.settlements.awaitOrderBatchMatcherSettlement(
                        event, System.nanoTime() + 30_000_000_000L))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("interrupted");
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
                assertThat(event.complete()).isFalse();
            } finally {
                Thread.interrupted();
            }
        }
    }

    private static MatcherSettlementEvent incompleteEvent() throws Exception {
        var event = new MatcherSettlementEvent();
        var mask = MatcherSettlementEvent.class.getDeclaredField("requiredLaneMask");
        mask.setAccessible(true);
        mask.setLong(event, 1);
        var completed = MatcherSettlementEvent.class.getDeclaredField("completedLanes");
        completed.setAccessible(true);
        completed.set(event, new long[16]);
        return event;
    }
}
