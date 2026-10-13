package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.model.CoreOrderStatus;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class SettlementChangesReuseTest {
    @Test void mixedSettlementKeepsFinalCountersAndTerminalCleanupAcrossReuse() {
        try (var runtime = new TradingRuntimeState()) {
            int laneId = runtime.topology().accountLaneId(7);
            var lane = runtime.accountLanes[laneId];
            var changes = new MatcherSettlementChanges(runtime.topology().accountLaneCount());
            changes.ensureOrderCapacity(2, 1L << laneId);
            var delta = changes.laneDeltas[laneId];
            var identities = new RuntimeIdentityRegistry();
            var open = CoreStateTestFixtures.order(11, 7, 0, 10);
            var filled = CoreStateTestFixtures.order(12, 7, 0, 10);
            var openReservation = CoreStateTestFixtures.reservation(11, 7, 0, 100);
            var filledReservation = CoreStateTestFixtures.reservation(12, 7, 0, 100);
            lane.putOrder(open);
            lane.putOrder(filled);
            lane.reservations.put(11, openReservation);
            lane.reservations.put(12, filledReservation);
            delta.putOrder(11, open);
            delta.putOrder(12, filled);
            delta.putReservation(11, openReservation);
            delta.putReservation(12, filledReservation);
            lane.updateOrderInPlace(11, 4, 6, 7, CoreOrderStatus.OPEN,
                    2, 1000, 2000);
            lane.updateOrderInPlace(12, 10, 0, 3, CoreOrderStatus.FILLED,
                    2, 1000, 2000);
            openReservation.consumeInPlace(40);
            filledReservation.consumeInPlace(100);

            changes.prepareLaneTerminal(laneId, identities, lane, runtime, 1);
            assertThat(lane.orders.get(12)).isNull();
            assertThat(lane.reservations.get(12)).isNull();
            assertThat(delta.reservations.get(11).consumedUnits()).isEqualTo(40);
            assertThat(lane.reservations.get(11).reservedUnits()).isEqualTo(60);
            assertThat(delta.terminalOrderCount()).isEqualTo(1);
            assertThat(delta.terminalOrderId(0)).isEqualTo(12);
            delta.commitTerminalToOwner(runtime, laneId, null, 1, null, null);
            assertThat(runtime.changedOrderValue(11).executedQuantitySteps()).isEqualTo(4);
            assertThat(runtime.changedOrderValue(11).cumulativeFeeUnits()).isEqualTo(7);
            assertThat(runtime.changedOrderValue(12).remainingQuantitySteps()).isZero();
            runtime.clearChangedKeys();
            delta.clear();

            lane.updateOrderInPlace(11, 4, 6, 0, CoreOrderStatus.CANCELED, 3, 1001, 2001);
            openReservation.releaseInPlace(60);
            delta.putOrder(11, open);
            delta.putReservation(11, openReservation);
            changes.prepareLaneTerminal(laneId, identities, lane, runtime, 2);
            assertThat(delta.terminalOrderCount()).isEqualTo(1);
            assertThat(delta.terminalOrderId(0)).isEqualTo(11);
            assertThat(lane.orders.get(11)).isNull();
            assertThat(lane.reservations.get(11)).isNull();
            delta.commitTerminalToOwner(runtime, laneId, null, 2, null, null);
            assertThat(runtime.changedOrderValue(11).cumulativeFeeUnits()).isEqualTo(7);
            assertThat(runtime.changedOrderValue(12)).isNull();
        }
    }

    @Test void terminalReceiptReuseReadsOnlyTheNewCountAndReleasesReferences() throws Exception {
        try (var runtime = new TradingRuntimeState()) {
            var delta = new LaneCommitDelta();
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
                var field = LaneCommitDelta.class.getDeclaredField("terminalOrderClients");
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
    @Test void positionPublicationTracksChangedPositions() {
        try (var runtime = new TradingRuntimeState()) {
            var delta = new LaneCommitDelta();
            var instrument = CoreStateTestFixtures.runtimeInstrument();
            var source = new PositionRuntime(7, 0, 0,
                    com.surprising.aeron.protocol.CoreMarginMode.CROSS,
                    com.surprising.aeron.protocol.CorePositionSide.NET,
                    instrument, 2, 100, 200, 0, 20);
            delta.putPosition(1, source);
            delta.preparePublication();
            delta.commitTerminalToOwner(runtime, 0, null, 1, null, null);
            assertThat(runtime.changedPositions.get(1)).isEqualTo(source);
            runtime.clearChangedKeys();
            delta.clear();

            source.applyInPlace(instrument, 3, 100, 300, 5, 30);
            delta.putPosition(1, source);
            delta.preparePublication();
            delta.commitTerminalToOwner(runtime, 0, null, 2, null, null);
            assertThat(runtime.changedPositions.get(1)).isEqualTo(source);
            runtime.clearChangedKeys();
            delta.clear();

            delta.putPosition(1, null);
            delta.preparePublication();
            delta.commitTerminalToOwner(runtime, 0, null, 3, null, null);
            assertThat(runtime.changedPositions.toArray()).containsExactly(1);
        }
    }

}
