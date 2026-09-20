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

    private static PositionRuntime position(long user, int symbol, long quantity) {
        return new PositionRuntime(user, symbol, 1, CoreMarginMode.CROSS, CorePositionSide.NET,
                CoreStateTestFixtures.runtimeInstrument(), quantity, quantity == 0 ? 0 : 100,
                Math.abs(quantity) * 100, 0, Math.abs(quantity) * 10);
    }
}
