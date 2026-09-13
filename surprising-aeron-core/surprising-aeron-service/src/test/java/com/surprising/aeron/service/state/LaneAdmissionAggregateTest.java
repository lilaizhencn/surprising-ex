package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.state.model.CoreOrderStatus;
import org.junit.jupiter.api.Test;
import org.agrona.collections.Long2ObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import static org.assertj.core.api.Assertions.assertThat;

class LaneAdmissionAggregateTest {
    @Test void orderAndReservationChurnRetainsTablesAndUnrelatedLiveState() throws Exception {
        var lane = new AccountLaneState(0, 256);
        lane.bindOwner();
        var held = new OrderRuntime(1, 7, 3, 50);
        var heldReservation = new ReservationRuntime(1, 7, 3, 50);
        lane.putOrder(held);
        lane.reservations.put(1, heldReservation);
        lane.rebuildLocalHashes();
        long originalHash = lane.localStateHash();
        var values = Long2ObjectHashMap.class.getDeclaredField("values");
        values.setAccessible(true);
        Object orderStorage = values.get(lane.orders);
        Object reservationStorage = values.get(lane.reservations);
        for (long id = 2; id < 20_000; id++) {
            lane.putOrder(new OrderRuntime(id, 7, 3, 50));
            lane.reservations.put(id, new ReservationRuntime(id, 7, 3, 50));
            lane.removeOrder(id);
            lane.reservations.remove(id);
        }
        assertThat(values.get(lane.orders)).isSameAs(orderStorage);
        assertThat(values.get(lane.reservations)).isSameAs(reservationStorage);
        assertThat(lane.orders.size()).isOne();
        assertThat(lane.reservations.size()).isOne();
        assertThat(lane.orders.get(1)).isSameAs(held);
        assertThat(lane.reservations.get(1)).isSameAs(heldReservation);
        lane.rebuildLocalHashes();
        assertThat(lane.localStateHash()).isEqualTo(originalHash);
    }

    @Test
    void metadataPartialFillAndRollbackKeepSameAggregateForEveryScope() throws Exception {
        for (var position : CorePositionSide.values()) for (var side : CoreOrderSide.values())
            for (var margin : CoreMarginMode.values()) for (boolean reduce : new boolean[]{false, true}) {
                var lane = new AccountLaneState(0, 256);
                lane.bindOwner();
                var order = order(side, position, margin, reduce);
                lane.putOrder(order);
                var index = lane.admissionOrderIndex(3);
                var field = index.getClass().getDeclaredField("summariesByUser");
                field.setAccessible(true);
                var users = (Long2ObjectHashMap<?>) field.get(index);
                var symbols = (IntObjectHashMap<?>) users.get(7);
                Object aggregate = symbols.get(3);
                lane.putOrder(order.withCommitMetadata(10, 20));
                lane.putOrder(order.withExecution(60, 40, CoreOrderStatus.OPEN, 2));
                assertThat(users.get(7)).isSameAs(symbols);
                assertThat(symbols.get(3)).isSameAs(aggregate);
                var summary = index.inspect(7, "SYM", position, side, margin);
                assertThat(reduce ? summary.reduceOnlyQuantity() : summary.pendingQuantity()).isEqualTo(40);
                assertThat(summary.marginModeCount()).isEqualTo(1);
                lane.putOrder(order);
                assertThat(symbols.get(3)).isSameAs(aggregate);
                summary = index.inspect(7, "SYM", position, side, margin);
                assertThat(reduce ? summary.reduceOnlyQuantity() : summary.pendingQuantity()).isEqualTo(100);
                lane.putOrder(order.withStatus(CoreOrderStatus.CANCELED, 3));
                assertThat(users.get(7)).isNull();
                summary = index.inspect(7, "SYM", position, side, margin);
                assertThat(summary.pendingQuantity()).isZero();
                assertThat(summary.reduceOnlyQuantity()).isZero();
                assertThat(summary.marginModeCount()).isZero();
            }
    }

    @Test
    void quantityUpdatePreservesOtherOrdersInTheSameAggregate() {
        var lane = new AccountLaneState(0, 256);
        lane.bindOwner();
        var first = order(CoreOrderSide.BUY, CorePositionSide.NET, CoreMarginMode.CROSS, false);
        lane.putOrder(first);
        lane.putOrder(new OrderRuntime(2, 7, 3, 50));
        lane.putOrder(first.withExecution(60, 40, CoreOrderStatus.OPEN, 2));
        var index = lane.admissionOrderIndex(3);
        var summary = index.inspect(7, "SYM", CorePositionSide.NET, CoreOrderSide.BUY, CoreMarginMode.CROSS);
        assertThat(summary.pendingQuantity()).isEqualTo(90);
        assertThat(summary.marginModeCount()).isEqualTo(2);
        lane.putOrder(first.withStatus(CoreOrderStatus.CANCELED, 3));
        summary = index.inspect(7, "SYM", CorePositionSide.NET, CoreOrderSide.BUY, CoreMarginMode.CROSS);
        assertThat(summary.pendingQuantity()).isEqualTo(50);
        assertThat(summary.marginModeCount()).isEqualTo(1);
    }

    @Test
    void changedSideAndMarginMoveBetweenScopes() {
        var lane = new AccountLaneState(0, 256);
        lane.bindOwner();
        lane.putOrder(order(CoreOrderSide.BUY, CorePositionSide.NET, CoreMarginMode.CROSS, false));
        lane.putOrder(order(CoreOrderSide.SELL, CorePositionSide.NET, CoreMarginMode.ISOLATED, true));
        var index = lane.admissionOrderIndex(3);
        var before = index.inspect(7, "SYM", CorePositionSide.NET, CoreOrderSide.BUY, CoreMarginMode.CROSS);
        assertThat(before.pendingQuantity()).isZero();
        assertThat(before.marginModeCount()).isZero();
        var after = index.inspect(7, "SYM", CorePositionSide.NET, CoreOrderSide.SELL, CoreMarginMode.ISOLATED);
        assertThat(after.reduceOnlyQuantity()).isEqualTo(100);
        assertThat(after.marginModeCount()).isEqualTo(1);
    }

    private static OrderRuntime order(CoreOrderSide side, CorePositionSide position, CoreMarginMode margin, boolean reduce) {
        return new OrderRuntime(1, 7, 3, 1, side, 100, reduce, margin, position,
                CoreOrderType.LIMIT, CoreTimeInForce.GTC, 0, 0, 100, 0, 100, false);
    }
}
