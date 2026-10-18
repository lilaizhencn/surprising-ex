package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;

class OrderBatchSettlementWaitTest {
    @Test
    void incompleteItemCannotBeCollectedOrRecycled() throws Exception {
        try (var runtime = new TradingRuntimeState()) {
            runtime.beginOrderBatchMutationScope();
            var event = incompleteEvent();
            assertThatThrownBy(() -> runtime.collectOrderBatchMatcherSettlement(event, null))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("not ready");
            assertThat(event.complete()).isFalse();
            assertThatThrownBy(() -> runtime.releaseMatcherSettlement(event))
                    .hasMessageContaining("cannot recycle an incomplete");
            runtime.endOrderBatchMutationScope();
        }
    }

    @Test
    void laneCommitTimeoutPreservesUnfinishedSequenceAndCannotRecycle() {
        try (var runtime = new TradingRuntimeState()) {
            var event = incompleteLaneCommit(runtime);
            assertThatThrownBy(() -> runtime.awaitLaneCommit(event, System.nanoTime() - 1))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("timed out");
            assertThat(event.coreSequence()).isEqualTo(1);
            assertThat(event.completedLaneMask()).isZero();
            assertThatThrownBy(() -> runtime.releaseLaneCommit(event))
                    .hasMessageContaining("incomplete Account Lane commit");
        }
    }

    @Test
    void laneCommitInterruptionDoesNotClearInterruptOrClaimCompletion() {
        try (var runtime = new TradingRuntimeState()) {
            var event = incompleteLaneCommit(runtime);
            Thread.currentThread().interrupt();
            try {
                assertThatThrownBy(() -> runtime.awaitLaneCommit(event, System.nanoTime() + 30_000_000_000L))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("interrupted");
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
                assertThat(event.complete()).isFalse();
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test
    void ledgerReadinessDoesNotMutateOwnerCompletionCacheOrExecuteAccounts() throws Exception {
        var event = incompleteEvent();
        var mask = MatcherSettlementEvent.class.getDeclaredField("requiredLaneMask"); mask.setAccessible(true);
        mask.setLong(event, 3);
        var route = MatcherSettlementEvent.class.getDeclaredField("routedLaneMask"); route.setAccessible(true);
        route.setLong(event, 3);
        var reset = MatcherSettlementEvent.class.getDeclaredMethod("resetCompletions", int.class); reset.setAccessible(true);
        reset.invoke(event, 2);
        var slotsField = MatcherSettlementEvent.class.getDeclaredField("completedLanes"); slotsField.setAccessible(true);
        long[] slots = (long[]) slotsField.get(event);
        var cache = MatcherSettlementEvent.class.getDeclaredField("observedCompletedLaneMask"); cache.setAccessible(true);
        var longs = java.lang.invoke.MethodHandles.arrayElementVarHandle(long[].class);
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var done = new java.util.concurrent.CountDownLatch(1);
        try (var ledger = new SystemLedgerLane(new TreasuryRuntime(), 256, failure::set)) {
            assertThat(ledger.offer(new SystemLedgerLane.Entry() {
                @Override public boolean ready() { return event.accountLanesCompleteForLedger(); }
                @Override public void apply(TreasuryRuntime actual) { done.countDown(); }
            })).isTrue();
            try {
                assertThat(event.accountLanesCompleteForLedger()).isFalse();
                assertThat(cache.getLong(event)).isZero();
                longs.setRelease(slots, 0, 1L);
                assertThat(event.accountLanesCompleteForLedger()).isFalse();
                assertThat(cache.getLong(event)).isZero();
                assertThat(done.getCount()).isOne();
            } finally { longs.setRelease(slots, 16, 1L); }
            assertThat(done.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(failure.get()).isNull();
            assertThat(event.accountLanesCompleteForLedger()).isTrue();
            assertThat(cache.getLong(event)).isZero();
            assertThat(event.completedLaneMask()).isEqualTo(3);
            assertThat(cache.getLong(event)).isEqualTo(3);
        }
        reset.invoke(event, 2);
        assertThat(event.accountLanesCompleteForLedger()).isFalse();
        assertThat(cache.getLong(event)).isZero();
    }

    @Test
    void completionCacheWaitsForEveryLaneAndResetsBeforeReuse() throws Exception {
        var event = incompleteEvent();
        var mask = MatcherSettlementEvent.class.getDeclaredField("requiredLaneMask"); mask.setAccessible(true);
        mask.setLong(event, 3);
        var route = MatcherSettlementEvent.class.getDeclaredField("routedLaneMask"); route.setAccessible(true);
        route.setLong(event, 3);
        var slotsField = MatcherSettlementEvent.class.getDeclaredField("completedLanes"); slotsField.setAccessible(true);
        var reset = MatcherSettlementEvent.class.getDeclaredMethod("resetCompletions", int.class); reset.setAccessible(true);
        reset.invoke(event, 2);
        long[] slots = (long[]) slotsField.get(event);
        var longs = java.lang.invoke.MethodHandles.arrayElementVarHandle(long[].class);
        longs.setRelease(slots, 0, 1L);
        assertThat(event.completedLaneMask()).isEqualTo(1);
        assertThat(event.complete()).isFalse();
        longs.setRelease(slots, 16, 1L);
        assertThat(event.complete()).isTrue();
        reset.invoke(event, 2);
        assertThat(event.completedLaneMask()).isZero();
        assertThat(event.complete()).isFalse();
        longs.setRelease(slots, 16, 1L);
        assertThat(event.complete()).isFalse();
        longs.setRelease(slots, 0, 1L);
        assertThat(event.complete()).isTrue();
    }

    private static LaneCommitEvent incompleteLaneCommit(TradingRuntimeState runtime) {
        return new LaneCommitEvent(1).prepare(1, 1,
                new org.eclipse.collections.impl.list.mutable.primitive.LongArrayList[] {
                    new org.eclipse.collections.impl.list.mutable.primitive.LongArrayList()
                }, runtime);
    }

    private static MatcherSettlementEvent incompleteEvent() throws Exception {
        // Isolate the Direct completion-marker/cache contract. Business execution and Ledger
        // publication are covered through real native facts in MatcherTreasurySettlementTest.
        var event = new MatcherSettlementEvent();
        var mask = MatcherSettlementEvent.class.getDeclaredField("requiredLaneMask");
        mask.setAccessible(true);
        mask.setLong(event, 1);
        var route = MatcherSettlementEvent.class.getDeclaredField("routedLaneMask");
        route.setAccessible(true);
        route.setLong(event, 1);
        var published = MatcherSettlementEvent.class.getDeclaredField("directPublished");
        published.setAccessible(true);
        published.setBoolean(event, true);
        var completed = MatcherSettlementEvent.class.getDeclaredField("completedLanes");
        completed.setAccessible(true);
        completed.set(event, new long[16]);
        return event;
    }
}
