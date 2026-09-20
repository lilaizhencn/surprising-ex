package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class SettlementChangesReuseTest {
    @Test void completionUpdatesTheIndependentOwnerMirrorWithoutAnotherSnapshot() {
        var order = CoreStateTestFixtures.order(1, 7, 0, 1);
        var admission = order.snapshot();
        var changes = new OrderChangeBuffer();
        var published = new LanePublishedMap<OrderRuntime>();
        published.put(1, admission);
        changes.put(1, admission);
        changes.capturePublicationValues();
        assertThat(changes.applyPublished(0, published, null)).isSameAs(admission);
        // Commit metadata is not represented by revision alone.
        order.applyCommitMetadataInPlace(123, 456);
        assertThat(order.revision()).isEqualTo(admission.revision());
        assertThat(order).isNotEqualTo(admission);
        changes.put(1, order);
        changes.capturePublicationValues();
        assertThat(changes.applyPublished(0, published, null)).isSameAs(admission).isEqualTo(order);
        assertThat(admission.clusterPosition()).isEqualTo(456);
    }

    @Test void terminalReceiptReuseReadsOnlyTheNewCountAndReleasesReferences() throws Exception {
        try (var runtime = new TradingRuntimeState()) {
            var delta = new TradingRuntimeState.LaneCommitDelta();
            long nextId = 1;
            for (int count : new int[]{65, 1, 0, 7, 0}) {
                long firstId = nextId;
                for (int i = 0; i < count; i++) {
                    long id = nextId++;
                    delta.orders.put(id, CoreStateTestFixtures.order(id, id + 100, 0, 1, true).snapshot());
                }
                delta.preparePublication(runtime);
                assertThat(delta.terminalOrderCount()).isEqualTo(count);
                for (int i = 0; i < delta.terminalOrderCount(); i++) {
                    assertThat(delta.terminalOrderId(i)).isEqualTo(firstId + i);
                    assertThat(delta.terminalOrderUser(i)).isEqualTo(firstId + i + 100);
                }
                // Recycle an unpublished receipt too: failure cleanup must not leak into reuse.
                delta.clear();
                assertThat(delta.terminalOrderCount()).isZero();
                assertThat(delta.orders.isEmpty()).isTrue();
                var field = TradingRuntimeState.LaneCommitDelta.class.getDeclaredField("terminalOrderClients");
                field.setAccessible(true);
                assertThat((Object[]) field.get(delta)).containsOnlyNulls();
            }
        }
    }

    @Test void pooledChangesMoveBetweenLanesWithoutRetainingPreviousOrdersOrPendingCounts() {
        var topology = new LaneTopology(LaneTopology.ROUTE_VERSION,1,0,0,4,
                LaneTopology.DEFAULT_ACCOUNT_LANE_SEED,16,16,16);
        try (var runtime = new TradingRuntimeState(topology)) {
            for (int turn = 0; turn < 100; turn++) {
                int lane = turn % 4;
                var changes = runtime.acquireMatcherSettlementChanges(1L << lane);
                for (int i = 0; i < 4; i++) {
                    assertThat(changes.laneDeltas[i].orders.isEmpty()).isTrue();
                    assertThat(changes.completedPending[i]).isZero();
                }
                changes.laneDeltas[lane].putOrder(turn + 1, CoreStateTestFixtures.order(turn + 1, 7, 0, 1, true));
                changes.completedPending[lane] = 3;
                runtime.releaseMatcherSettlementChanges(changes);
            }
        }
    }
}
