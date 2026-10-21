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
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class LaneAdmissionReuseTest {
    @Test void replacementPartialFillAndGroupReuseKeepExactPendingAndMarginCounts() {
        var lane = lane();
        lane.putOrder(order(11, 7, 0, CoreOrderSide.BUY, CorePositionSide.NET, CoreMarginMode.CROSS, false, 10));
        lane.putOrder(order(12, 7, 0, CoreOrderSide.SELL, CorePositionSide.NET, CoreMarginMode.ISOLATED, false, 6));
        lane.putOrder(order(13, 7, 0, CoreOrderSide.SELL, CorePositionSide.LONG, CoreMarginMode.CROSS, true, 4));
        summary(lane, 7, 0, CorePositionSide.NET, CoreOrderSide.BUY, CoreMarginMode.ISOLATED, 10, 0, 1);
        summary(lane, 7, 0, CorePositionSide.NET, CoreOrderSide.SELL, CoreMarginMode.CROSS, 6, 4, 1);
        lane.updateOrderInPlace(11, 4, 6, 0, CoreOrderStatus.OPEN, 2, 0, 0);
        summary(lane, 7, 0, CorePositionSide.NET, CoreOrderSide.BUY, CoreMarginMode.CROSS, 6, 0, 1);
        lane.putOrder(order(12, 7, 0, CoreOrderSide.SELL, CorePositionSide.NET, CoreMarginMode.CROSS, false, 3));
        summary(lane, 7, 0, CorePositionSide.NET, CoreOrderSide.SELL, CoreMarginMode.ISOLATED, 3, 4, 0);
        summary(lane, 7, 0, CorePositionSide.NET, CoreOrderSide.SELL, CoreMarginMode.CROSS, 3, 4, 2);
        lane.removeOrder(11); lane.removeOrder(12); lane.removeOrder(13);
        lane.putOrder(order(21, 8, 1, CoreOrderSide.SELL, CorePositionSide.SHORT, CoreMarginMode.ISOLATED, false, 9));
        summary(lane, 7, 0, CorePositionSide.NET, CoreOrderSide.BUY, CoreMarginMode.CROSS, 0, 0, 0);
        summary(lane, 8, 1, CorePositionSide.SHORT, CoreOrderSide.SELL, CoreMarginMode.ISOLATED, 9, 0, 1);
        summary(lane, 8, 1, CorePositionSide.NET, CoreOrderSide.SELL, CoreMarginMode.CROSS, 0, 0, 0);
    }

    @Test void zeroAndLargestSymbolIdsStayDistinctAcrossUsersAndRepeatedTerminalCycles() {
        var lane = lane();
        lane.putOrder(order(1, 7, Integer.MAX_VALUE, CoreOrderSide.SELL, CorePositionSide.NET, CoreMarginMode.ISOLATED, false, 17));
        for (int round = 0; round < 1_000; round++) {
            long id = 1_000_000L + round;
            lane.putOrder(order(id, 8, 0, CoreOrderSide.BUY, CorePositionSide.NET, CoreMarginMode.CROSS, false, 11));
            summary(lane, 8, 0, CorePositionSide.NET, CoreOrderSide.BUY, CoreMarginMode.CROSS, 11, 0, 1);
            lane.updateOrderInPlace(id, 11, 0, 0, CoreOrderStatus.FILLED, 2, 0, 0);
            lane.removeOrder(id);
            summary(lane, 8, 0, CorePositionSide.NET, CoreOrderSide.BUY, CoreMarginMode.CROSS, 0, 0, 0);
            summary(lane, 7, Integer.MAX_VALUE, CorePositionSide.NET, CoreOrderSide.SELL, CoreMarginMode.ISOLATED, 17, 0, 1);
        }
        assertThat(lane.orders.size()).isOne();
    }

    @Test void emptyCellCacheIsBoundedAndUserRemovalReleasesTheRetainedTable() throws Exception {
        var lane = lane();
        for (int symbol = 0; symbol < 600; symbol++)
            lane.putOrder(order(symbol + 1, 7, symbol, CoreOrderSide.BUY, CorePositionSide.NET, CoreMarginMode.CROSS, false, 1));
        for (int symbol = 0; symbol < 600; symbol++) lane.removeOrder(symbol + 1);
        Object index = field(lane, "admissionOrderIndex");
        var users = (org.agrona.collections.Long2ObjectHashMap<?>) field(index, "summariesByUser");
        assertThat(users.size()).isOne();
        assertThat((java.util.ArrayDeque<?>) field(index, "emptyAggregates")).hasSizeLessThanOrEqualTo(256);
        lane.removeUser(7);
        assertThat(users.size()).isZero();
        lane.registerUser(7);
        lane.putOrder(order(999, 7, 0, CoreOrderSide.SELL, CorePositionSide.SHORT, CoreMarginMode.ISOLATED, true, 3));
        summary(lane, 7, 0, CorePositionSide.SHORT, CoreOrderSide.SELL, CoreMarginMode.ISOLATED, 0, 3, 1);
        summary(lane, 7, 0, CorePositionSide.NET, CoreOrderSide.BUY, CoreMarginMode.CROSS, 0, 0, 0);
    }

    @Test void removingUserBeforeItsLastOrderKeepsTheIndexUntilThatOrderLeaves() throws Exception {
        var lane = lane();
        lane.putOrder(order(11, 7, 0, CoreOrderSide.BUY, CorePositionSide.NET, CoreMarginMode.CROSS, false, 10));
        lane.removeUser(7);
        summary(lane, 7, 0, CorePositionSide.NET, CoreOrderSide.BUY, CoreMarginMode.CROSS, 10, 0, 1);
        lane.removeOrder(11);
        var users = (org.agrona.collections.Long2ObjectHashMap<?>) field(field(lane, "admissionOrderIndex"), "summariesByUser");
        assertThat(users.size()).isZero();
    }

    private static AccountLaneState lane() {
        var lane = new AccountLaneState(0, 256);
        lane.bindOwner(); lane.registerUser(7); lane.registerUser(8);
        return lane;
    }

    private static OrderRuntime order(long id, long user, int symbol, CoreOrderSide side,
            CorePositionSide position, CoreMarginMode margin, boolean reduce, long quantity) {
        return new OrderRuntime(id, user, symbol, CoreStateTestFixtures.runtimeInstrument(), side, 100,
                reduce, margin, position, CoreOrderType.LIMIT, CoreTimeInForce.GTC, 0, 0,
                quantity, 0, quantity, false);
    }

    private static void summary(AccountLaneState lane, long user, int symbol, CorePositionSide position,
            CoreOrderSide side, CoreMarginMode margin, long pending, long reduce, int count) {
        var result = lane.admissionOrderIndex(symbol).inspect(user, "1", position, side, margin);
        assertThat(result.pendingQuantity()).isEqualTo(pending);
        assertThat(result.reduceOnlyQuantity()).isEqualTo(reduce);
        assertThat(result.marginModeCount()).isEqualTo(count);
    }

    private static Object field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true);
        return field.get(owner);
    }
}
