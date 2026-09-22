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
                delta.preparePublication();
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
    @Test void positionPublicationCreatesUpdatesDeletesAndRecreatesOwnerValue() {
        try (var runtime = new TradingRuntimeState()) {
            var delta = new TradingRuntimeState.LaneCommitDelta();
            var instrument = CoreStateTestFixtures.runtimeInstrument();
            var source = new PositionRuntime(7, 0, 0,
                    com.surprising.aeron.protocol.CoreMarginMode.CROSS,
                    com.surprising.aeron.protocol.CorePositionSide.NET,
                    instrument, 2, 100, 200, 0, 20);
            delta.putPosition(1, source);
            delta.preparePublication();
            delta.commitTerminalToOwner(runtime, 0, null, 1, null, null);
            var published = runtime.publishedPositions.get(1);
            assertThat(published).isEqualTo(source).isNotSameAs(source);
            runtime.clearChangedKeys();
            delta.clear();

            source.applyInPlace(instrument, 3, 100, 300, 5, 30);
            assertThat(published.signedQuantitySteps()).isEqualTo(2);
            delta.putPosition(1, source);
            delta.preparePublication();
            delta.commitTerminalToOwner(runtime, 0, null, 2, null, null);
            assertThat(runtime.publishedPositions.get(1)).isSameAs(published).isEqualTo(source);
            assertThat(runtime.changedPositions.get(1)).isSameAs(published);
            runtime.clearChangedKeys();
            delta.clear();

            delta.putPosition(1, null);
            delta.preparePublication();
            delta.commitTerminalToOwner(runtime, 0, null, 3, null, null);
            assertThat(runtime.publishedPositions.get(1)).isNull();
            assertThat(runtime.changedPositions.toArray()).containsExactly(1);
            runtime.clearChangedKeys();
            delta.clear();

            delta.putPosition(1, source);
            delta.preparePublication();
            delta.commitTerminalToOwner(runtime, 0, null, 4, null, null);
            assertThat(runtime.publishedPositions.get(1)).isEqualTo(source).isNotSameAs(published);
        }
    }

}
