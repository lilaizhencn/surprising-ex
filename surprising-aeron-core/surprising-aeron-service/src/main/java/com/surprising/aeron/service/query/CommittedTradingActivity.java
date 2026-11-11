package com.surprising.aeron.service.query;

import com.surprising.aeron.protocol.CoreTriggerOrderStatus;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import java.math.BigInteger;
import java.util.*;
import org.agrona.collections.LongHashSet;

/** 对已分离重放运行时做冷态活动汇总。 */
public record CommittedTradingActivity(long openOrders, long usersWithPositions, long openPositions,
        long pendingTriggers, long expiredPendingTriggers,
        List<Instrument> instruments) {
    /** 单个币对的挂单及非空持仓统计。数量使用任意精度整数求和，多空分别以正的绝对步数累计，避免多个大额账户合计时超过 long。 */
    public record Instrument(String instrumentId, long openOrders, BigInteger openQuantitySteps,
            long openPositions, long users, long longPositions, long shortPositions,
            BigInteger longQuantitySteps, BigInteger shortQuantitySteps) {}

    /** Owner 必要时临时取得账户读取权，逐 Lane 汇总未完成挂单、非空持仓和待触发条件单，按币对编号排序结果。 */
    public static CommittedTradingActivity capture(TradingAccountRuntime runtime, RuntimeIdentityRegistry identities, long now) {
        runtime.assertOwner();
        boolean acquired = !runtime.ownerLaneAccess();
        if (acquired) runtime.acquireOwnerLaneAccess();
        try {
            var values = new HashMap<Integer, Totals>();
            var users = new LongHashSet();
            long[] counts = new long[4];
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

    /** 本次冷查询内一个币对的临时累计桶，处理结束后只提取数值和去重用户数量，不保留任何账户实体。 */
    private static final class Totals {
        long orders, positions, longs, shorts;
        final LongHashSet users = new LongHashSet();
        BigInteger openQuantity = BigInteger.ZERO, longQuantity = BigInteger.ZERO, shortQuantity = BigInteger.ZERO;
    }
}
