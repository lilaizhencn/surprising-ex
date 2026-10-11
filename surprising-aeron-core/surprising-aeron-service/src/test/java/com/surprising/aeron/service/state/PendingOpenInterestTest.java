package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.product.api.ProductLine;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PendingOpenInterestTest {
    @Test void completedLaneChangesIncludeBothHedgedLegsAndCrossLaneMakerWithoutChangingCommittedTotals() {
        var before = TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL);
        var identities = new RuntimeIdentityRegistry();
        var totals = new OpenInterestIndex(before);
        var committed = new AdlPositionIndex(before, identities);
        var longBefore = position(1, "1", CorePositionSide.LONG, 10);
        var shortBefore = position(2, "1", CorePositionSide.SHORT, -10);
        totals.apply(null, longBefore); committed.apply(101, null, longBefore);
        totals.apply(null, shortBefore); committed.apply(102, null, shortBefore);
        try (var runtime = new TradingRuntimeState()) {
            runtime.bindOwner();
            var lane0 = new RuntimeIndexedChangeBuffer<PositionRuntime, RuntimePositionIndexValue>();
            var lane1 = new RuntimeIndexedChangeBuffer<PositionRuntime, RuntimePositionIndexValue>();
            lane0.putIndexed(101, null, true, position(1, "1", CorePositionSide.LONG, 15));
            lane0.putIndexed(103, null, true, position(1, "1", CorePositionSide.SHORT, -5));
            lane1.putIndexed(102, null, true, position(2, "1", CorePositionSide.SHORT, -20));
            lane1.putIndexed(104, null, true, position(3, "other", CorePositionSide.NET, 100));
            runtime.changedPositions.adopt(0, lane0);
            runtime.changedPositions.adopt(1, lane1);
            assertThat(totals.pendingOpenInterestSteps("1", runtime, committed, identities)).isEqualTo(25);
            assertThat(totals.pendingOpenInterestSteps("other", runtime, committed, identities)).isEqualTo(100);
            assertThat(totals.openInterestSteps("1")).isEqualTo(10);
            // A later batch item replaces the prior after-image, including a closed position.
            lane1.putIndexed(102, null, true, null);
            runtime.changedPositions.adopt(1, lane1);
            assertThat(totals.pendingOpenInterestSteps("1", runtime, committed, identities)).isEqualTo(15);
            assertThat(committed.value(102)).isSameAs(shortBefore);
        }
    }

    @Test void overflowDoesNotRetainScratchOrMutateCommittedTotals() {
        var before = TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL);
        var identities = new RuntimeIdentityRegistry();
        var totals = new OpenInterestIndex(before);
        var committed = new AdlPositionIndex(before, identities);
        totals.apply(null, position(1, "1", CorePositionSide.NET, Long.MAX_VALUE));
        try (var runtime = new TradingRuntimeState()) {
            runtime.bindOwner();
            var changes = new RuntimeIndexedChangeBuffer<PositionRuntime, RuntimePositionIndexValue>();
            changes.putIndexed(101, null, true, position(2, "1", CorePositionSide.NET, 1));
            runtime.changedPositions.adopt(0, changes);
            assertThatThrownBy(() -> totals.pendingOpenInterestSteps("1", runtime, committed, identities))
                    .isInstanceOf(ArithmeticException.class);
            runtime.changedPositions.clear();
            assertThat(totals.pendingOpenInterestSteps("1", runtime, committed, identities)).isEqualTo(Long.MAX_VALUE);
        }
    }

    private static RuntimePositionIndexValue position(long user, String instrument, CorePositionSide side, long quantity) {
        return new RuntimePositionIndexValue(user, instrument, "USDT", side, quantity);
    }
}
