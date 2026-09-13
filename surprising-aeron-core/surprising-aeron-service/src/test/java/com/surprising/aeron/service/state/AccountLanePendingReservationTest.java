package com.surprising.aeron.service.state;

import org.junit.jupiter.api.Test;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;
import static org.assertj.core.api.Assertions.*;

class AccountLanePendingReservationTest {
    @Test
    void completedUsersLeaveNoEntriesAndAssetsReuseTheirTables() throws Exception {
        var lane = new AccountLaneState(0, 256);
        lane.bindOwner();
        var field = AccountLaneState.class.getDeclaredField("pendingReservedUnitsByAsset");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var assets = (IntObjectHashMap<LongLongHashMap>) field.get(lane);
        LongLongHashMap first = null;
        for (long user = Long.MAX_VALUE - 2048; user < Long.MAX_VALUE; user++) {
            for (int asset = 0; asset < 2; asset++) {
                long id = asset + 1;
                lane.reservations.put(id, new ReservationRuntime(id, user, asset, 19));
                lane.markPendingReservation(id, 1);
                assertThat(lane.pendingReservedUnits(user, asset)).isEqualTo(19);
                lane.completePendingReservation(id, 1);
                lane.reservations.remove(id);
                assertThat(lane.pendingReservationCount(user)).isZero();
                assertThat(lane.pendingReservedUnits(user, asset)).isZero();
            }
            if (first == null) first = assets.get(0);
            assertThat(assets.get(0)).isSameAs(first);
            assertThat(assets.size()).isEqualTo(2);
            assertThat(assets.get(0).isEmpty()).isTrue();
            assertThat(assets.get(1).isEmpty()).isTrue();
        }
        assertThat(lane.pendingReservationCount()).isZero();
    }

    @Test
    void assetMigrationOverflowAndReverseReplacementPreserveOtherReservations() {
        var lane = new AccountLaneState(0, 256);
        lane.bindOwner();
        var previous = new ReservationRuntime(1, 7, 0, 40);
        var other = new ReservationRuntime(2, 7, 1, Long.MAX_VALUE - 10);
        lane.reservations.put(1, previous);
        lane.reservations.put(2, other);
        lane.markPendingReservation(1, 1);
        lane.markPendingReservation(2, 1);
        assertThatThrownBy(() -> lane.replacePendingReservation(previous,
                new ReservationRuntime(1, 7, 1, 11))).isInstanceOf(ArithmeticException.class);
        assertThat(lane.pendingReservedUnits(7, 0)).isEqualTo(40);
        assertThat(lane.pendingReservedUnits(7, 1)).isEqualTo(Long.MAX_VALUE - 10);
        var replacement = new ReservationRuntime(1, 7, 1, 10);
        lane.replacePendingReservation(previous, replacement);
        lane.reservations.put(1, replacement);
        assertThat(lane.pendingReservedUnits(7, 0)).isZero();
        assertThat(lane.pendingReservedUnits(7, 1)).isEqualTo(Long.MAX_VALUE);
        lane.replacePendingReservation(replacement, previous);
        lane.reservations.put(1, previous);
        lane.completePendingReservation(1, 1);
        lane.completePendingReservation(2, 1);
        assertThat(lane.pendingReservedUnits(7, 0)).isZero();
        assertThat(lane.pendingReservedUnits(7, 1)).isZero();
        assertThat(lane.pendingReservationCount()).isZero();
    }
}
