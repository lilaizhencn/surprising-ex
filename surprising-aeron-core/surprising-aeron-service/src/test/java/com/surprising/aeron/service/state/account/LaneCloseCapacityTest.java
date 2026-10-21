package com.surprising.aeron.service.state.account;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.service.state.CoreStateTestFixtures;
import com.surprising.aeron.service.state.model.CoreOrderStatus;
import com.surprising.aeron.service.state.order.OrderRuntime;
import com.surprising.aeron.service.state.position.PositionCloseCapacity;
import com.surprising.aeron.service.state.position.PositionRuntime;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LaneCloseCapacityTest {
    @Test
    void capacityUsesOnlyLiveCommittedOrdersInTheSamePositionAndCancelsNewestFirst() {
        var lane = new AccountLaneState(0, 256);
        var instrument = CoreStateTestFixtures.runtimeInstrument();
        lane.positions.put(99, new PositionRuntime(7, 3, 0, CoreMarginMode.CROSS, CorePositionSide.NET,
                instrument, 10, 100, 1000, 0, 100));
        lane.putOrder(order(201, 7, 3, CorePositionSide.NET, CoreOrderSide.SELL, true, 3, 10));
        lane.putOrder(order(202, 7, 3, CorePositionSide.NET, CoreOrderSide.SELL, true, 4, 20));
        lane.putOrder(order(203, 7, 3, CorePositionSide.NET, CoreOrderSide.SELL, false, 2, 30));
        // These orders must not contribute to this position's close capacity.
        lane.putOrder(order(204, 7, 4, CorePositionSide.NET, CoreOrderSide.SELL, true, 100, 40));
        lane.putOrder(order(205, 8, 3, CorePositionSide.NET, CoreOrderSide.SELL, true, 100, 40));
        lane.putOrder(order(206, 7, 3, CorePositionSide.LONG, CoreOrderSide.SELL, true, 100, 40));
        lane.putOrder(order(207, 7, 3, CorePositionSide.NET, CoreOrderSide.BUY, true, 100, 40));
        lane.putOrder(order(208, 7, 3, CorePositionSide.NET, CoreOrderSide.SELL, true, 100, 40)
                .withStatus(CoreOrderStatus.CANCELED, 41));
        lane.putOrder(order(209, 7, 3, CorePositionSide.NET, CoreOrderSide.SELL, true, 100, 40));
        lane.reservations.put(209, CoreStateTestFixtures.reservation(209, 7, 0, 100));
        lane.markPendingReservation(209, 1);
        lane.putOrder(order(210, 7, 3, CorePositionSide.NET, CoreOrderSide.SELL, true, 100, 50));

        var capacity = PositionCloseCapacity.inspectLane(
                lane, 7, 3, 99, CorePositionSide.NET, CoreOrderSide.SELL, 210);
        assertThat(capacity.requestedQuantitySteps()).isEqualTo(9);
        assertThat(capacity.availableQuantitySteps()).isEqualTo(1);
        assertThat(capacity.conflictsFor(5)).containsExactly(202L);
        assertThat(capacity.conflictsFor(6)).containsExactly(202L, 201L);
        assertThat(capacity.conflictsFor(1)).isEmpty();
    }

    private static OrderRuntime order(long id, long user, int symbol, CorePositionSide position,
            CoreOrderSide side, boolean reduceOnly, long quantity, long clusterPosition) {
        return new OrderRuntime(id, user, symbol, CoreStateTestFixtures.runtimeInstrument(), side, 100,
                reduceOnly, CoreMarginMode.CROSS, position, CoreOrderType.LIMIT, CoreTimeInForce.GTC,
                0, 0, quantity, 0, quantity, false).withCommitMetadata(1000, clusterPosition);
    }
}
