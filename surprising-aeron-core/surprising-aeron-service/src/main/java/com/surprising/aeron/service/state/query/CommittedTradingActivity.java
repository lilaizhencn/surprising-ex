package com.surprising.aeron.service.state.query;

import com.surprising.aeron.protocol.CoreTriggerOrderStatus;
import com.surprising.aeron.service.state.TradingRuntimeState;
import com.surprising.aeron.service.state.identity.RuntimeIdentityRegistry;
import java.math.BigInteger;
import java.util.*;
import org.agrona.collections.LongHashSet;

/** Cold read of the detached replay only. No entity is copied or retained after the read lease. */
public record CommittedTradingActivity(long openOrders, long usersWithPositions, long openPositions,
        long pendingTriggers, long expiredPendingTriggers,
        List<Instrument> instruments) {
    public record Instrument(String instrumentId, long openOrders, BigInteger openQuantitySteps,
            long openPositions, long users, long longPositions, long shortPositions,
            BigInteger longQuantitySteps, BigInteger shortQuantitySteps) {}

    public static CommittedTradingActivity capture(TradingRuntimeState runtime, RuntimeIdentityRegistry identities, long now) {
        runtime.assertOwner();
        boolean acquired = !runtime.ownerLaneAccess();
        if (acquired) runtime.acquireOwnerLaneAccess();
        try {
            var values = new HashMap<Integer, Totals>();
            var users = new LongHashSet();
            long[] counts = new long[4]; // open orders, positions, pending triggers, expired pending
            for (int laneId = 0; laneId < runtime.accountLaneCount(); laneId++) {
                runtime.onLaneReadOnly(laneId, lane -> {
                    lane.forEachOrderEntry((id, order) -> {
                        if (order.status().terminal() || order.remainingQuantitySteps() <= 0) return;
                        var t = values.computeIfAbsent(order.symbolId(), ignored -> new Totals());
                        t.orders++; t.openQuantity = t.openQuantity.add(BigInteger.valueOf(order.remainingQuantitySteps()));
                        counts[0]++;
                    });
                    lane.forEachPositionValue(position -> {
                        if (position.signedQuantitySteps() == 0) return;
                        var t = values.computeIfAbsent(position.symbolId(), ignored -> new Totals());
                        t.positions++; t.users.add(position.userId()); users.add(position.userId()); counts[1]++;
                        var quantity = BigInteger.valueOf(position.signedQuantitySteps());
                        if (quantity.signum() > 0) { t.longs++; t.longQuantity = t.longQuantity.add(quantity); }
                        else { t.shorts++; t.shortQuantity = t.shortQuantity.subtract(quantity); }
                    });
                    lane.forEachTriggerOrderValue(trigger -> {
                        if (trigger.status() == CoreTriggerOrderStatus.PENDING) {
                            counts[2]++;
                            if (trigger.expiresAtEpochMillis() > 0 && trigger.expiresAtEpochMillis() <= now) counts[3]++;
                        }
                    });
                    return null;
                });
            }
            var instruments = new ArrayList<Instrument>(values.size());
            values.forEach((symbol, t) -> instruments.add(new Instrument(identities.instrumentId(symbol), t.orders, t.openQuantity,
                    t.positions, t.users.size(), t.longs, t.shorts, t.longQuantity, t.shortQuantity)));
            instruments.sort(Comparator.comparing(Instrument::instrumentId));
            return new CommittedTradingActivity(counts[0], users.size(), counts[1], counts[2], counts[3], List.copyOf(instruments));
        } finally {
            if (acquired) runtime.releaseOwnerLaneAccess();
        }
    }

    private static final class Totals {
        long orders, positions, longs, shorts;
        final LongHashSet users = new LongHashSet();
        BigInteger openQuantity = BigInteger.ZERO, longQuantity = BigInteger.ZERO, shortQuantity = BigInteger.ZERO;
    }
}
