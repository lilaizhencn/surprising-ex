package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.product.api.ProductLine;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CoreUserStateTest {

    @Test
    void reservationTransitionsMaintainExplainedLocksIncrementally() {
        CoreUserState user = fundedUser(1_000, 0);
        OrderReservation reservation = reservation(11, 300);

        Map<Long, OrderReservation> reservations = StateMapSupport.delta(user.reservations());
        reservations.put(reservation.orderId(), reservation);
        Map<String, AssetBalance> balances = StateMapSupport.delta(user.balances());
        balances.put("USDT", new AssetBalance("USDT", 700, 300));
        CoreUserState reserved = user.transition(1, balances, reservations, user.positions(), user.positionMode());

        Map<Long, OrderReservation> releasedReservations = StateMapSupport.delta(reserved.reservations());
        releasedReservations.put(reservation.orderId(), reservation.releaseAll());
        Map<String, AssetBalance> releasedBalances = StateMapSupport.delta(reserved.balances());
        releasedBalances.put("USDT", new AssetBalance("USDT", 1_000, 0));
        CoreUserState released = reserved.transition(2, releasedBalances, releasedReservations,
                reserved.positions(), reserved.positionMode());

        Map<Long, OrderReservation> removedReservations = StateMapSupport.delta(released.reservations());
        removedReservations.remove(reservation.orderId());
        CoreUserState removed = released.transition(3, released.balances(), removedReservations,
                released.positions(), released.positionMode());

        assertThat(reserved.reservations().get(11L).remainingUnits()).isEqualTo(300);
        assertThat(released.reservations().get(11L).remainingUnits()).isZero();
        assertThat(removed.reservations()).isEmpty();
        assertThat(removed.totalUnits("USDT")).isEqualTo(1_000);
    }

    @Test
    void positionMarginTransitionsValidateOnlyAffectedAssetLocks() {
        CoreUserState user = fundedUser(800, 200);
        CorePositionState position = position(200);
        Map<String, CorePositionState> positions = StateMapSupport.delta(user.positions());
        positions.put(position.key(), position);
        CoreUserState opened = user.transition(1, user.balances(), user.reservations(), positions,
                user.positionMode());

        Map<String, CorePositionState> reducedPositions = StateMapSupport.delta(opened.positions());
        reducedPositions.put(position.key(), position(75));
        Map<String, AssetBalance> balances = StateMapSupport.delta(opened.balances());
        balances.put("USDT", new AssetBalance("USDT", 925, 75));
        CoreUserState reduced = opened.transition(2, balances, opened.reservations(), reducedPositions,
                opened.positionMode());

        Map<String, CorePositionState> closedPositions = StateMapSupport.delta(reduced.positions());
        closedPositions.remove(position.key());
        Map<String, AssetBalance> closedBalances = StateMapSupport.delta(reduced.balances());
        closedBalances.put("USDT", new AssetBalance("USDT", 1_000, 0));
        CoreUserState closed = reduced.transition(3, closedBalances, reduced.reservations(), closedPositions,
                reduced.positionMode());

        assertThat(reduced.positions().get("BTC-USDT").positionMarginUnits()).isEqualTo(75);
        assertThat(reduced.balances().get("USDT").lockedUnits()).isEqualTo(75);
        assertThat(closed.positions()).isEmpty();
        assertThat(closed.balances().get("USDT").lockedUnits()).isZero();
    }

    @Test
    void rejectsBalanceBelowIncrementalExplainedLocks() {
        CoreUserState user = fundedUser(700, 300);
        Map<Long, OrderReservation> reservations = StateMapSupport.delta(user.reservations());
        reservations.put(11L, reservation(11, 300));
        CoreUserState reserved = user.transition(1, user.balances(), reservations, user.positions(),
                user.positionMode());
        Map<String, AssetBalance> balances = StateMapSupport.delta(reserved.balances());
        balances.put("USDT", new AssetBalance("USDT", 900, 100));

        assertThatThrownBy(() -> reserved.transition(2, balances, reserved.reservations(), reserved.positions(),
                reserved.positionMode()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reservation locks exceed balance");
    }

    @Test
    void rejectsNestedStateWithoutDirectDeltaLineage() {
        CoreUserState user = fundedUser(1_000, 0);
        Map<String, AssetBalance> replacement = Map.of("USDT", new AssetBalance("USDT", 900, 100));

        assertThatThrownBy(() -> user.transition(1, replacement, user.reservations(), user.positions(),
                user.positionMode()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("balances lineage is unavailable");

        CoreUserState coldReplacement = new CoreUserState(user.productLine(), user.userId(), 1,
                replacement, user.reservations(), user.positions(), user.positionMode());
        assertThatThrownBy(() -> coldReplacement.requireIncrementalLineage(user))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("user balances lineage is unavailable");
    }

    @Test
    void acceptsMultipleUserTransitionsWithinOneCommandLineage() {
        CoreUserState user = fundedUser(1_000, 0);
        Map<String, AssetBalance> reservedBalances = StateMapSupport.delta(user.balances());
        reservedBalances.put("USDT", new AssetBalance("USDT", 700, 300));
        Map<Long, OrderReservation> reservedOrders = StateMapSupport.delta(user.reservations());
        reservedOrders.put(11L, reservation(11, 300));
        CoreUserState reserved = user.transition(1, reservedBalances, reservedOrders, user.positions(),
                user.positionMode());

        Map<String, AssetBalance> settledBalances = StateMapSupport.delta(reserved.balances());
        settledBalances.put("USDT", new AssetBalance("USDT", 1_000, 0));
        Map<Long, OrderReservation> settledOrders = StateMapSupport.delta(reserved.reservations());
        settledOrders.put(11L, reservedOrders.get(11L).releaseAll());
        CoreUserState settled = reserved.transition(2, settledBalances, settledOrders, reserved.positions(),
                reserved.positionMode());

        assertThat(settled).satisfies(value -> value.requireIncrementalLineage(user));
    }

    @Test
    void derivedLocksDoNotChangeSnapshotValueSemantics() {
        CoreUserState original = new CoreUserState(ProductLine.LINEAR_PERPETUAL, 7, 3,
                Map.of("USDT", new AssetBalance("USDT", 700, 300)),
                Map.of(11L, reservation(11, 100)),
                Map.of("BTC-USDT", position(200)), CorePositionMode.ONE_WAY);
        CoreUserState decoded = new CoreUserState(original.productLine(), original.userId(), original.revision(),
                original.balances(), original.reservations(), original.positions(), original.positionMode());

        assertThat(decoded).isEqualTo(original);
        assertThat(decoded.hashCode()).isEqualTo(original.hashCode());
    }

    private static CoreUserState fundedUser(long availableUnits, long lockedUnits) {
        return new CoreUserState(ProductLine.LINEAR_PERPETUAL, 7, 0,
                Map.of("USDT", new AssetBalance("USDT", availableUnits, lockedUnits)),
                Map.of(), Map.of(), CorePositionMode.ONE_WAY);
    }

    private static OrderReservation reservation(long orderId, long units) {
        return OrderReservation.create(orderId, "BTC-USDT", 1, ReservationKind.DERIVATIVE_MARGIN,
                "USDT", units, 1);
    }

    private static CorePositionState position(long marginUnits) {
        return new CorePositionState("BTC-USDT", "USDT", CoreMarginMode.CROSS, CorePositionSide.NET,
                1, 1, 100, 100, 0, marginUnits);
    }
}
