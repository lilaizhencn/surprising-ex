package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class SettlementChangesReuseTest {
    @Test void pooledChangesMoveBetweenLanesWithoutRetainingPreviousOrdersOrPendingCounts() {
        var topology = new LaneTopology(LaneTopology.ROUTE_VERSION,1,0,0,4,
                LaneTopology.DEFAULT_ACCOUNT_LANE_SEED,16,16,16);
        try (var runtime = new TradingRuntimeState(topology)) {
            for (int turn = 0; turn < 100; turn++) {
                int lane = turn % 4;
                var changes = runtime.acquireMatcherSettlementChanges(1L << lane);
                for (int i = 0; i < 4; i++) {
                    assertThat(changes.publishedLaneChanges[i].orders.isEmpty()).isTrue();
                    assertThat(changes.completedPending[i]).isZero();
                }
                changes.publishedLaneChanges[lane].putOrder(turn + 1, new OrderRuntime(turn + 1,7,0,1,true));
                changes.completedPending[lane] = 3;
                runtime.releaseMatcherSettlementChanges(changes);
            }
        }
    }
}
