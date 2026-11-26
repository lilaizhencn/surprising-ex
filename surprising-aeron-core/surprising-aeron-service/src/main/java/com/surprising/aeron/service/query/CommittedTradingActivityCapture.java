package com.surprising.aeron.service.query;

import com.surprising.aeron.protocol.CoreTriggerOrderStatus;
import com.surprising.aeron.protocol.export.CommittedTradingActivity;
import com.surprising.aeron.service.core.AccountBook;
import com.surprising.aeron.service.core.identity.IdIndex;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import org.agrona.collections.LongHashSet;

/** Owner 线程采集当前交易活动汇总。 */
public final class CommittedTradingActivityCapture {
    private CommittedTradingActivityCapture() {}

    public static CommittedTradingActivity capture(AccountBook accountBook, IdIndex identities, long now) {
        accountBook.assertOwner();
        boolean acquired = !accountBook.ownerLaneAccess();
        if (acquired) accountBook.acquireOwnerLaneAccess();
        try {
            var values = new HashMap<Integer, Totals>();
            var users = new LongHashSet();
            long[] counts = new long[4];
            for (int laneId = 0; laneId < accountBook.accountLaneCount(); laneId++) {
                accountBook.onLaneReadOnly(laneId, lane -> {
                    lane.forEachOrderEntry((id, order) -> {
                        if (order.status().terminal() || order.remainingQuantitySteps() <= 0) return;
                        var t = values.computeIfAbsent(order.symbolId(), ignored -> new Totals());
                        t.orders++;
                        t.openQuantity = t.openQuantity.add(BigInteger.valueOf(order.remainingQuantitySteps()));
                        counts[0]++;
                    });
                    lane.forEachPositionValue(position -> {
                        if (position.signedQuantitySteps() == 0) return;
                        var t = values.computeIfAbsent(position.symbolId(), ignored -> new Totals());
                        t.positions++;
                        t.users.add(position.userId());
                        users.add(position.userId());
                        counts[1]++;
                        var quantity = BigInteger.valueOf(position.signedQuantitySteps());
                        if (quantity.signum() > 0) {
                            t.longs++;
                            t.longQuantity = t.longQuantity.add(quantity);
                        } else {
                            t.shorts++;
                            t.shortQuantity = t.shortQuantity.subtract(quantity);
                        }
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
            var instruments = new ArrayList<CommittedTradingActivity.Instrument>(values.size());
            values.forEach((symbol, t) -> instruments.add(new CommittedTradingActivity.Instrument(
                    identities.instrumentId(symbol), t.orders, t.openQuantity,
                    t.positions, t.users.size(), t.longs, t.shorts, t.longQuantity, t.shortQuantity)));
            instruments.sort(Comparator.comparing(CommittedTradingActivity.Instrument::instrumentId));
            return new CommittedTradingActivity(counts[0], users.size(), counts[1], counts[2], counts[3], List.copyOf(instruments));
        } finally {
            if (acquired) accountBook.releaseOwnerLaneAccess();
        }
    }

    private static final class Totals {
        long orders, positions, longs, shorts;
        final LongHashSet users = new LongHashSet();
        BigInteger openQuantity = BigInteger.ZERO, longQuantity = BigInteger.ZERO, shortQuantity = BigInteger.ZERO;
    }
}
