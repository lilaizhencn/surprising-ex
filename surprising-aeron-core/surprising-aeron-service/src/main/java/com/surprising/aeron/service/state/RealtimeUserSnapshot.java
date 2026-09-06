package com.surprising.aeron.service.state;

import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;

import java.util.*;

/** One user at an Account Lane FIFO fence; only immutable values leave the lane. */
public record RealtimeUserSnapshot(
        UserRuntime user,
        List<BalanceValue> balances,
        List<ReservationRuntime> reservations,
        List<PositionRuntime> positions,
        List<OrderRuntime> orders,
        List<CoreTriggerOrderState> triggers,
        List<LeverageValue> leverages,
        List<RiskSnapshotRuntime> risks) {
    public record BalanceValue(int assetId, long available, long locked) {}

    public record LeverageValue(CoreLeverageKey key, long value) {}

    static RealtimeUserSnapshot capture(AccountLaneState lane, long userId) {
        var balances = lane.balances.get(userId);
        var orderIds = lane.activeOrderIdsByUser.get(userId);
        var reservationIds = lane.reservationIdsByUser.get(userId);
        var positionKeys = lane.positionKeysByUser.get(userId);
        var triggerIds = lane.triggerIdsByUser.get(userId);
        var leverageKeys = lane.leverageKeysByUser.get(userId);
        long count =
                (balances == null ? 0 : balances.size())
                        + size(orderIds)
                        + size(reservationIds)
                        + size(positionKeys)
                        + size(triggerIds)
                        + (leverageKeys == null ? 0 : leverageKeys.size());
        if (count > 4096)
            throw new IllegalArgumentException("realtime user snapshot exceeds entity capacity");
        var b = new ArrayList<BalanceValue>();
        if (balances != null)
            balances.forEachValue(
                    v -> b.add(new BalanceValue(v.assetId(), v.availableUnits(), v.lockedUnits())));
        var o = new ArrayList<OrderRuntime>();
        if (orderIds != null)
            orderIds.forEach(
                    id -> {
                        var v = lane.orders.get(id);
                        if (v != null && !v.status().terminal()) o.add(v);
                    });
        var re = new ArrayList<ReservationRuntime>();
        if (reservationIds != null)
            reservationIds.forEach(
                    id -> {
                        var v = lane.reservations.get(id);
                        if (v != null) re.add(v);
                    });
        var p = new ArrayList<PositionRuntime>();
        if (positionKeys != null)
            positionKeys.forEach(
                    id -> {
                        var v = lane.positions.get(id);
                        if (v != null) p.add(v);
                    });
        var risk = new ArrayList<RiskSnapshotRuntime>();
        if (positionKeys != null)
            positionKeys.forEach(
                    id -> {
                        var v = lane.riskSnapshots.get(id);
                        if (v != null) risk.add(v);
                    });
        var t = new ArrayList<CoreTriggerOrderState>();
        if (triggerIds != null)
            triggerIds.forEach(
                    id -> {
                        var v = lane.triggerOrders.get(id);
                        if (v != null && v.status().open()) t.add(v);
                    });
        var l = new ArrayList<LeverageValue>();
        if (leverageKeys != null)
            leverageKeys.forEach(k -> l.add(new LeverageValue(k, lane.leverages.get(k))));
        return new RealtimeUserSnapshot(
                lane.users.get(userId),
                List.copyOf(b),
                List.copyOf(re),
                List.copyOf(p),
                List.copyOf(o),
                List.copyOf(t),
                List.copyOf(l),
                List.copyOf(risk));
    }

    private static long size(LongHashSet values) {
        return values == null ? 0 : values.size();
    }
}
