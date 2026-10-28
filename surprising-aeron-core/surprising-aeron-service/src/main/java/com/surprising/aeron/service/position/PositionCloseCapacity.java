package com.surprising.aeron.service.position;

import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.service.account.AccountLaneState;
import com.surprising.aeron.service.order.state.OrderRuntime;
import com.surprising.aeron.support.collection.ImmutableLongArrayList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** 所属账户 Lane 检查平仓容量，仅在超量时收集最新优先的只减仓撤单候选，不改变账户状态。 */
public final class PositionCloseCapacity {
    private static final Comparator<OrderRuntime> NEWEST_FIRST = Comparator.comparingLong(OrderRuntime::clusterPosition)
            .thenComparingLong(OrderRuntime::orderId).reversed();

    private PositionCloseCapacity() { }

    /** 普通平仓单和只减仓单都占用容量，只有已提交的只减仓挂单可以作为撤单候选。 */
    public static List<Long> conflictsFor(AccountLaneState lane, long userId, int symbolId,
            long positionKey, CorePositionSide positionSide, CoreOrderSide closeSide,
            long excludedOrderId, long incomingQuantitySteps) {
        if (incomingQuantitySteps <= 0) {
            throw new IllegalArgumentException("incoming close quantity must be positive");
        }
        PositionRuntime position = lane.position(positionKey);
        long positionQuantity = position == null ? 0 : Math.absExact(position.signedQuantitySteps());
        long requestedQuantity = 0;
        var ids = lane.activeOrderIds(userId);
        if (ids == null) return List.of();
        var iterator = ids.iterator();
        while (iterator.hasNext()) {
            long orderId = iterator.nextValue();
            if (orderId == excludedOrderId || lane.pendingReservation(orderId)) continue;
            OrderRuntime order = lane.order(orderId);
            if (matches(order, userId, symbolId, positionSide, closeSide)) {
                if (order.reduceOnly() && (orderId <= 0 || order.remainingQuantitySteps() <= 0
                        || order.clusterPosition() < 0)) {
                    throw new IllegalArgumentException("invalid close-capacity commitment");
                }
                requestedQuantity = Math.addExact(requestedQuantity, order.remainingQuantitySteps());
            }
        }
        long overflow = Math.subtractExact(Math.addExact(requestedQuantity, incomingQuantitySteps), positionQuantity);
        if (overflow <= 0) return List.of();

        // 两次遍历都在同一 Lane 调用内，权威订单不会在容量计算和候选收集之间改变。
        ArrayList<OrderRuntime> candidates = new ArrayList<>();
        iterator = ids.iterator();
        while (iterator.hasNext()) {
            long orderId = iterator.nextValue();
            if (orderId == excludedOrderId || lane.pendingReservation(orderId)) continue;
            OrderRuntime order = lane.order(orderId);
            if (matches(order, userId, symbolId, positionSide, closeSide) && order.reduceOnly()) {
                candidates.add(order);
            }
        }
        if (candidates.isEmpty()) return List.of();
        candidates.sort(NEWEST_FIRST);
        long[] conflicts = new long[candidates.size()];
        int count = 0;
        long released = 0;
        for (int index = 0; index < candidates.size(); index++) {
            OrderRuntime order = candidates.get(index);
            conflicts[count++] = order.orderId();
            released = Math.addExact(released, order.remainingQuantitySteps());
            if (released >= overflow) break;
        }
        return ImmutableLongArrayList.takeOwnership(count == conflicts.length
                ? conflicts : Arrays.copyOf(conflicts, count));
    }

    private static boolean matches(OrderRuntime order, long userId, int symbolId,
            CorePositionSide positionSide, CoreOrderSide closeSide) {
        return order != null && order.status() == CoreOrderStatus.OPEN && order.userId() == userId
                && order.symbolId() == symbolId && order.positionSide() == positionSide && order.side() == closeSide;
    }
}
