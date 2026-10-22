package com.surprising.aeron.service.query.state;

import com.surprising.aeron.protocol.CoreTriggerOrderStatus;
import com.surprising.aeron.service.core.TradingRuntimeState;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import java.math.BigInteger;
import java.util.*;
import org.agrona.collections.LongHashSet;

/**
 * 对已分离重放运行时做冷态活动汇总。只在读取权限范围内遍历订单、持仓和条件单，结果保留计数及每币对汇总，不把账户实体引用带出读权限使用期。
 * @param openOrders 本统计范围内仍有效且有未成交数量的普通订单条数
 * @param usersWithPositions 全体币对中至少有一份非空持仓的去重用户数
 * @param openPositions 本统计范围内非空持仓记录数，双向持仓腿分别计数
 * @param pendingTriggers 仍处于 PENDING 的条件单数量
 * @param expiredPendingTriggers 仍为 PENDING 但到期时间已不晚于 now 的条件单数量，是待触发总数的子集
 * @param instruments 按外部币对编号排序的单币对统计列表
 */
public record CommittedTradingActivity(long openOrders, long usersWithPositions, long openPositions,
        long pendingTriggers, long expiredPendingTriggers,
        List<Instrument> instruments) {
    /**
     * 单个币对的挂单及非空持仓统计。数量使用任意精度整数求和，多空分别以正的绝对步数累计，避免多个大额账户合计时超过 long。
     * @param instrumentId 当前统计币对的外部编号
     * @param openOrders 本统计范围内仍有效且有未成交数量的普通订单条数
     * @param openQuantitySteps 该币对所有有效挂单剩余数量之和，单位为数量步数
     * @param openPositions 本统计范围内非空持仓记录数，双向持仓腿分别计数
     * @param users 该币对至少有一份非空持仓的去重用户数
     * @param longPositions 该币对带方向数量为正的持仓记录数
     * @param shortPositions 该币对带方向数量为负的持仓记录数
     * @param longQuantitySteps 该币对多头数量的正步数合计
     * @param shortQuantitySteps 该币对空头绝对数量的正步数合计
     */
    public record Instrument(String instrumentId, long openOrders, BigInteger openQuantitySteps,
            long openPositions, long users, long longPositions, long shortPositions,
            BigInteger longQuantitySteps, BigInteger shortQuantitySteps) {}

    /**
     * Owner 必要时临时取得账户读取权，逐 Lane 汇总未完成挂单、非空持仓和待触发条件单，按币对编号排序结果；finally 只释放本方法临时取得的读取权。
     * @param runtime 由调用方提供的已分离重放运行时，本入口要求当前线程为 Owner
     * @param identities 币对整数编号到外部编号的身份映射
     * @param now 统计待触发条件单是否已到期的 Unix 毫秒时间戳
     */
    public static CommittedTradingActivity capture(TradingRuntimeState runtime, RuntimeIdentityRegistry identities, long now) {
        runtime.assertOwner();
        // 进入方法时是否尚无账户读取权，只有本方法临时取得的权限才在 finally 释放。
        boolean acquired = !runtime.ownerLaneAccess();
        if (acquired) runtime.acquireOwnerLaneAccess();
        try {
            // 本次查询按整数币对编号保存的临时累计桶映射。
            var values = new HashMap<Integer, Totals>();
            // 跨所有币对去重的有仓用户编号集合，同一用户多份仓位只计一次。
            var users = new LongHashSet();
            // 跨币对的四类总数，回调通过固定下标累计，不复制订单或持仓对象。
            long[] counts = new long[4]; // 下标依次为有效挂单、非空持仓、待触发条件单、已到期但仍待触发条件单。
            for (/* 当前读取的账户 Lane 编号，按 Lane 顺序逐个汇总。 */ int laneId = 0; laneId < runtime.accountLaneCount(); laneId++) {
                runtime.onLaneReadOnly(laneId, /* 当前只读账户 Lane 入口，本回调只遍历其现有订单、持仓及条件单。 */ lane -> {
                    lane.forEachOrderEntry((/* 订单表回调给出的订单编号，是否计数以同时传入的订单状态为准。 */ id, /* 当前 Lane 的一笔订单，终态或没有剩余量的订单不计挂单。 */ order) -> {
                        if (order.status().terminal() || order.remainingQuantitySteps() <= 0) return;
                        // 当前币对的临时汇总桶，随所遍历的订单或持仓累加对应数量和条数。
                        var t = values.computeIfAbsent(order.symbolId(), /* 首次建立累计桶时的币对整数映射键，回调只创建空统计桶。 */ ignored -> new Totals());
                        t.orders++; t.openQuantity = t.openQuantity.add(BigInteger.valueOf(order.remainingQuantitySteps()));
                        counts[0]++;
                    });
                    lane.forEachPositionValue(/* 当前 Lane 的持仓，非零数量才累计用户、多空方向和绝对步数。 */ position -> {
                        if (position.signedQuantitySteps() == 0) return;
                        // 当前币对的临时汇总桶，随所遍历的订单或持仓累加对应数量和条数。
                        var t = values.computeIfAbsent(position.symbolId(), /* 首次建立累计桶时的币对整数映射键，回调只创建空统计桶。 */ ignored -> new Totals());
                        t.positions++; t.users.add(position.userId()); users.add(position.userId()); counts[1]++;
                        // 把带方向持仓步数转换成的大整数，多头加正值，空头减负值以累计正绝对量。
                        var quantity = BigInteger.valueOf(position.signedQuantitySteps());
                        if (quantity.signum() > 0) { t.longs++; t.longQuantity = t.longQuantity.add(quantity); }
                        else { t.shorts++; t.shortQuantity = t.shortQuantity.subtract(quantity); }
                    });
                    lane.forEachTriggerOrderValue(/* 当前 Lane 的条件单，仅 PENDING 参与待触发和已到期待触发统计。 */ trigger -> {
                        if (trigger.status() == CoreTriggerOrderStatus.PENDING) {
                            counts[2]++;
                            if (trigger.expiresAtEpochMillis() > 0 && trigger.expiresAtEpochMillis() <= now) counts[3]++;
                        }
                    });
                    return null;
                });
            }
            // 从每币对临时桶提取的结果列表，按外部币对编号排序后转为只读列表。
            var instruments = new ArrayList<Instrument>(values.size());
            values.forEach((/* 当前累计桶的币对整数编号，生成输出前换成外部业务编号。 */ symbol, /* 当前币对的临时汇总桶，随所遍历的订单或持仓累加对应数量和条数。 */ t) -> instruments.add(new Instrument(identities.instrumentId(symbol), t.orders, t.openQuantity,
                    t.positions, t.users.size(), t.longs, t.shorts, t.longQuantity, t.shortQuantity)));
            instruments.sort(Comparator.comparing(Instrument::instrumentId));
            return new CommittedTradingActivity(counts[0], users.size(), counts[1], counts[2], counts[3], List.copyOf(instruments));
        } finally {
            if (acquired) runtime.releaseOwnerLaneAccess();
        }
    }

    /**
     * 本次冷查询内一个币对的临时累计桶，处理结束后只提取数值和去重用户数量，不保留任何账户实体。
     */
    private static final class Totals {
        /**
         * orders：当前币对有效未成交订单条数。
         * positions：当前币对非空持仓记录条数。
         * longs：当前币对多头记录条数。
         * shorts：当前币对空头记录条数。
         */
        long orders, positions, longs, shorts;
        /** 当前币对有非空持仓的去重用户编号集合。 */
        final LongHashSet users = new LongHashSet();
        /**
         * openQuantity：当前币对所有有效挂单剩余步数的大整数合计。
         * longQuantity：当前币对多头数量步数的大整数合计。
         * shortQuantity：当前币对空头绝对数量步数的大整数合计。
         */
        BigInteger openQuantity = BigInteger.ZERO, longQuantity = BigInteger.ZERO, shortQuantity = BigInteger.ZERO;
    }
}
