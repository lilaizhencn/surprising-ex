package com.surprising.aeron.service.state.realtime;

import com.surprising.aeron.service.state.account.AccountLaneState;
import com.surprising.aeron.service.state.account.UserRuntime;
import com.surprising.aeron.service.state.model.CoreLeverageKey;
import com.surprising.aeron.service.state.model.CoreTriggerOrderState;
import com.surprising.aeron.service.state.order.OrderRuntime;
import com.surprising.aeron.service.state.order.ReservationRuntime;
import com.surprising.aeron.service.state.position.PositionRuntime;
import com.surprising.aeron.service.state.risk.*;
import com.surprising.aeron.service.state.risk.RiskSnapshotRuntime;
import java.util.*;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;

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

    public static RealtimeUserSnapshot capture(AccountLaneState lane, long userId) {
        lane.assertOwner();
        var balances = lane.userBalances(userId);
        var orderIds = lane.activeOrderIds(userId);
        var reservationIds = lane.reservationIds(userId);
        var positionKeys = lane.positionKeys(userId);
        var triggerIds = lane.triggerIds(userId);
        var leverageKeys = lane.leverageKeys(userId);
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
            orderIds.forEachLong(
                    id -> {
                        var v = lane.order(id);
                        if (v != null && !v.status().terminal()) o.add(v.publicationValue());
                    });
        var re = new ArrayList<ReservationRuntime>();
        if (reservationIds != null)
            reservationIds.forEachLong(
                    id -> {
                        var v = lane.reservation(id);
                        if (v != null) re.add(v.publicationValue());
                    });
        var p = new ArrayList<PositionRuntime>();
        if (positionKeys != null)
            positionKeys.forEach(
                    id -> {
                        var v = lane.position(id);
                        if (v != null) p.add(v.publicationValue());
                    });
        var risk = new ArrayList<RiskSnapshotRuntime>();
        if (positionKeys != null)
            positionKeys.forEach(
                    id -> {
                        var v = lane.riskSnapshot(id);
                        if (v != null) risk.add(v);
                    });
        var t = new ArrayList<CoreTriggerOrderState>();
        if (triggerIds != null)
            triggerIds.forEach(
                    id -> {
                        var v = lane.triggerOrder(id);
                        if (v != null && v.status().open()) t.add(v);
                    });
        var l = new ArrayList<LeverageValue>();
        if (leverageKeys != null)
            leverageKeys.forEach(k -> l.add(new LeverageValue(k, lane.leverage(k))));
        return new RealtimeUserSnapshot(
                lane.user(userId) == null ? null : lane.user(userId).publicationValue(),
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

    private static long size(org.agrona.collections.LongHashSet values) {
        return values == null ? 0 : values.size();
    }
}
