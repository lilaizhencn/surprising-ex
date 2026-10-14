package com.surprising.aeron.service.state;
import com.surprising.aeron.service.state.account.BalanceRuntime;
import com.surprising.aeron.service.state.account.UserRuntime;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionSide;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class LaneEntityMembershipTest {
    @Test
    void reservationAmountAndAssetChangesKeepMembershipAndPendingTotals() {
        try (var state = new TradingRuntimeState()) {
            state.putUser(new UserRuntime(7));
            state.putBalance(new BalanceRuntime(7, 1, 1000, 0));
            state.putBalance(new BalanceRuntime(7, 2, 1000, 0));
            state.putReservation(CoreStateTestFixtures.reservation(11, 7, 1, 40));
            state.markPendingReservation(7, 11, 1);
            var lane = state.onLane(7L, value -> value);
            var ids = lane.reservationIdsByUser.get(7);
            state.replaceReservation(CoreStateTestFixtures.reservation(11, 7, 1, 20));
            assertThat(lane.reservationIdsByUser.get(7)).isSameAs(ids);
            assertThat(lane.pendingReservedUnits(7, 1)).isEqualTo(20);
            state.replaceReservation(CoreStateTestFixtures.reservation(11, 7, 2, 10));
            assertThat(lane.reservationIdsByUser.get(7)).isSameAs(ids);
            assertThat(ids.toArray()).containsExactly(11);
            assertThat(lane.pendingReservedUnits(7, 1)).isZero();
            assertThat(lane.pendingReservedUnits(7, 2)).isEqualTo(10);
            assertThatThrownBy(() -> state.replaceReservation(CoreStateTestFixtures.reservation(11, 8, 2, 10)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(lane.reservationIdsByUser.get(7)).isSameAs(ids);
            state.completePendingReservations(1);
            state.removeReservation(11, 7);
            assertThat(lane.reservationIdsByUser.get(7)).isSameAs(ids);
            assertThat(ids.size()).isZero();
            assertThat(lane.pendingReservationCount()).isZero();
        }
    }

    @Test
    void clientOrderIndexReusesItsPrimitiveTableUntilUserRemoval() {
        try (var state = new TradingRuntimeState()) {
            state.putUser(new UserRuntime(7));
            var lane = state.onLane(7L, value -> value);
            TradingRuntimeState.putClientOrderIndex(lane, 7, 91, 11);
            var clients = lane.clientOrderIndex.get(7);

            assertThat(TradingRuntimeState.removeClientOrderIndex(lane, 7, 91)).isEqualTo(11);
            assertThat(lane.clientOrderIndex.get(7)).isSameAs(clients);
            assertThat(clients.size()).isZero();

            TradingRuntimeState.putClientOrderIndex(lane, 7, 92, 12);
            assertThat(lane.clientOrderIndex.get(7)).isSameAs(clients);
            state.removeUser(7);
            assertThat(lane.clientOrderIndex.get(7)).isNull();
        }
    }

    @Test
    void pendingSequenceAndClientTablesSurviveUniqueIdChurnWithoutLosingLiveEntries() throws Exception {
        var lane = new AccountLaneState(0, 256);
        lane.bindOwner();
        var held = CoreStateTestFixtures.reservation(1, 7, 3, 13);
        lane.reservations.put(1, held);
        lane.markPendingReservation(1, 2);
        TradingRuntimeState.putClientOrderIndex(lane, 7, 0, 1);
        var clients = lane.clientOrderIndex.get(7);
        var entries = org.agrona.collections.Long2LongHashMap.class.getDeclaredField("entries");
        entries.setAccessible(true);
        Object pendingStorage = entries.get(lane.pendingReservationSequences);
        Object clientStorage = null;
        for (int round = 0; round < 1_001; round++) {
            long first = 2L + round * 20;
            for (long id = first; id < first + 20; id++) {
                lane.reservations.put(id, CoreStateTestFixtures.reservation(id, 7, 3, 5));
                lane.markPendingReservation(id, 3L + round);
                TradingRuntimeState.putClientOrderIndex(lane, 7, id, id);
            }
            if (round == 0) clientStorage = entries.get(clients); // Initial live-capacity growth is allowed.
            assertThat(lane.pendingReservationCount()).isEqualTo(21);
            assertThat(lane.pendingReservedUnits(7, 3)).isEqualTo(113);
            for (long id = first; id < first + 20; id++) {
                assertThat(lane.pendingReservationSequence(id)).isEqualTo(3L + round);
                lane.completePendingReservation(id, 3L + round);
                lane.reservations.remove(id);
                assertThat(TradingRuntimeState.removeClientOrderIndex(lane, 7, id)).isEqualTo(id);
                assertThat(lane.pendingReservationSequence(id)).isZero();
            }
            assertThat(entries.get(lane.pendingReservationSequences)).isSameAs(pendingStorage);
            assertThat(entries.get(clients)).isSameAs(clientStorage);
            assertThat(clients.size()).isOne();
            assertThat(clients.get(0L)).isEqualTo(1);
            assertThat(lane.pendingReservationSequence(1)).isEqualTo(2);
            assertThat(lane.pendingReservationCount()).isOne();
            assertThat(lane.pendingReservedUnits(7, 3)).isEqualTo(13);
            assertThat(lane.reservations.get(1)).isSameAs(held);
        }
        lane.completePendingReservation(1, 2);
        assertThat(lane.pendingReservationCount()).isZero();
        assertThat(lane.pendingReservedUnits(7, 3)).isZero();
    }

    @Test
    void rollbackRestoresReassignedRemovedAndNewClientAliases() {
        try (var state = new TradingRuntimeState()) {
            state.putUser(new UserRuntime(7));
            var lane = state.onLane(7L, value -> value);
            TradingRuntimeState.putClientOrderIndex(lane, 7, 91, 11);
            TradingRuntimeState.putClientOrderIndex(lane, 7, 92, 12);
            state.clearChangedKeys();
            state.accountRollback.captureClientOrderBefore(7, 91);
            state.accountRollback.captureClientOrderBefore(7, 92);
            state.accountRollback.captureClientOrderBefore(7, 93);
            TradingRuntimeState.putClientOrderIndex(lane, 7, 91, 99);
            TradingRuntimeState.removeClientOrderIndex(lane, 7, 92);
            TradingRuntimeState.putClientOrderIndex(lane, 7, 93, 99);

            state.accountRollback.restoreLane(lane);

            assertThat(state.orderIdByClientValue(7, 91)).isEqualTo(11);
            assertThat(state.orderIdByClientValue(7, 92)).isEqualTo(12);
            assertThat(state.orderIdByClientValue(7, 93)).isZero();
            var restored = new org.eclipse.collections.impl.set.mutable.primitive.LongHashSet();
            lane.clientKeysByOrderId.forEach(11, restored::add);
            lane.clientKeysByOrderId.forEach(12, restored::add);
            assertThat(restored.toArray()).containsExactlyInAnyOrder(91, 92);
            restored.clear();
            lane.clientKeysByOrderId.forEach(99, restored::add);
            assertThat(restored.size()).isZero();
        }
    }

    @Test
    void positionUpdatesPreserveMembershipAndOnlyFlatTransitionsChangeExposureIndex() {
        try (var state = new TradingRuntimeState()) {
            state.putUser(new UserRuntime(7));
            state.putPosition(11, position(7, 1, 10));
            var lane = state.onLane(7L, value -> value);
            var all = lane.positionKeysByUser.get(7);
            var byUser = lane.positionKeysBySymbolAndUser.get(1);
            var open = byUser.get(7);
            for (long quantity : new long[]{9, 5, -5, -1}) {
                state.replacePosition(11, position(7, 1, quantity));
                assertThat(lane.positionKeysByUser.get(7)).isSameAs(all);
                assertThat(lane.positionKeysBySymbolAndUser.get(1)).isSameAs(byUser);
                assertThat(byUser.get(7)).isSameAs(open);
                assertThat(open.toArray()).containsExactly(11);
            }
            state.putPosition(12, position(7, 1, 2));
            state.replacePosition(11, position(7, 1, 0));
            assertThat(all.toArray()).containsExactlyInAnyOrder(11, 12);
            assertThat(open.toArray()).containsExactly(12);
            state.putPosition(11, position(7, 1, 3));
            assertThat(lane.positionKeysByUser.get(7)).isSameAs(all);
            assertThat(open.toArray()).containsExactlyInAnyOrder(11, 12);
            state.replacePosition(11, position(7, 2, 3));
            assertThat(open.toArray()).containsExactly(12);
            assertThat(lane.positionKeysBySymbolAndUser.get(2).get(7).toArray()).containsExactly(11);
            state.removePosition(12, 7);
            assertThat(lane.positionKeysBySymbolAndUser.get(1)).isNull();
            state.replacePosition(11, position(7, 2, 0));
            assertThat(lane.positionKeysBySymbolAndUser.get(2)).isNull();
            assertThat(lane.positionKeysByUser.get(7)).isSameAs(all);
            assertThat(all.toArray()).containsExactly(11);
            state.removePosition(11, 7);
            assertThat(lane.positionKeysByUser.get(7)).isNull();
        }
    }

    private static PositionRuntime position(long user, int instrumentId, long quantity) {
        return new PositionRuntime(user, instrumentId, 1, CoreMarginMode.CROSS, CorePositionSide.NET,
                CoreStateTestFixtures.runtimeInstrument(), quantity, quantity == 0 ? 0 : 100,
                Math.abs(quantity) * 100, 0, Math.abs(quantity) * 10);
    }
}
