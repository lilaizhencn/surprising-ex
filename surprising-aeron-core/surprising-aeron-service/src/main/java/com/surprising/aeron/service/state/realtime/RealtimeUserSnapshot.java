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

/**
 * 在所属账户 Lane 的 FIFO 读取边界捕获一个用户的实时快照。capture 把可变用户、订单、预留和持仓转成发布值，再交出只读列表；记录的普通构造器本身不检查或复制外部列表，调用方仍须遵守捕获边界。
 * @param user 用户发布值，不存在用户时可空
 * @param balances 独立余额数字发布值列表
 * @param reservations 用户现有预留的独立发布值列表
 * @param positions 用户现有持仓的独立发布值列表
 * @param orders 用户非终态订单的独立发布值列表
 * @param triggers 用户仍 open 的不可变触发单值列表
 * @param leverages 用户杠杆键和值的列表
 * @param risks 按用户持仓键找到的风险快照列表
 */
public record RealtimeUserSnapshot(
        UserRuntime user,
        List<BalanceValue> balances,
        List<ReservationRuntime> reservations,
        List<PositionRuntime> positions,
        List<OrderRuntime> orders,
        List<CoreTriggerOrderState> triggers,
        List<LeverageValue> leverages,
        List<RiskSnapshotRuntime> risks) {
    /**
     * 只保存资产内部编号、可用和冻结数字的余额发布值，不持有可变余额对象。
     * @param assetId 已准备的内部资产编号
     * @param available 捕获时可用资产单位数
     * @param locked 捕获时冻结资产单位数
     */
    public record BalanceValue(int assetId, long available, long locked) {}

    /**
     * 用户标的保证金模式键及其当时杠杆值。
     * @param key 用户、标的及保证金模式组成的杠杆键
     * @param value 该键捕获时的杠杆值
     */
    public record LeverageValue(CoreLeverageKey key, long value) {}

    /**
     * 先核对当前线程持有 Lane，读取该用户各成员索引并限制候选总数不超过四千零九十六。复制余额数字与发布值，订单只留非终态、触发单只留 open，风险按持仓键读取；所有结果列表冻结后离开 Lane。调用方保证请求路由到该用户所属 Lane。
     * @param lane 用户所属且当前线程有读取权限的账户 Lane
     * @param userId 需要捕获快照的用户
     */
    public static RealtimeUserSnapshot capture(AccountLaneState lane, long userId) {
        lane.assertOwner();
        // 当前 Lane 中该用户的余额表引用，仅在捕获范围内读取
        var balances = lane.userBalances(userId);
        // 该用户现有活跃订单 ID 成员集合
        var orderIds = lane.activeOrderIds(userId);
        // 该用户现有预留 ID 集合
        var reservationIds = lane.reservationIds(userId);
        // 该用户现有确定性持仓键集合，也据此读取风险
        var positionKeys = lane.positionKeys(userId);
        // 该用户触发单 ID 集合，取正文后再筛 open
        var triggerIds = lane.triggerIds(userId);
        // 该用户现有杠杆业务键集合
        var leverageKeys = lane.leverageKeys(userId);
        // 余额、订单、预留、持仓、触发单和杠杆候选数之和，不额外加用户和风险项
        long count =
                (balances == null ? 0 : balances.size())
                        + size(orderIds)
                        + size(reservationIds)
                        + size(positionKeys)
                        + size(triggerIds)
                        + (leverageKeys == null ? 0 : leverageKeys.size());
        if (count > 4096)
            throw new IllegalArgumentException("realtime user snapshot exceeds entity capacity");
        // 独立余额数字结果列表
        var b = new ArrayList<BalanceValue>();
        if (balances != null)
            balances.forEachValue(
                    /* 当前在所属 Lane 读取、准备复制或筛选的原业务值 */ v -> b.add(new BalanceValue(v.assetId(), v.availableUnits(), v.lockedUnits())));
        // 非终态订单发布值结果列表
        var o = new ArrayList<OrderRuntime>();
        if (orderIds != null)
            orderIds.forEachLong(
                    /* 当前待从账户 Lane 查询的订单、预留、持仓或触发单 ID */ id -> {
                        // 当前在所属 Lane 读取、准备复制或筛选的原业务值
                        var v = lane.order(id);
                        if (v != null && !v.status().terminal()) o.add(v.publicationValue());
                    });
        // 现有预留发布值结果列表
        var re = new ArrayList<ReservationRuntime>();
        if (reservationIds != null)
            reservationIds.forEachLong(
                    /* 当前待从账户 Lane 查询的订单、预留、持仓或触发单 ID */ id -> {
                        // 当前在所属 Lane 读取、准备复制或筛选的原业务值
                        var v = lane.reservation(id);
                        if (v != null) re.add(v.publicationValue());
                    });
        // 现有持仓发布值结果列表
        var p = new ArrayList<PositionRuntime>();
        if (positionKeys != null)
            positionKeys.forEach(
                    /* 当前待从账户 Lane 查询的订单、预留、持仓或触发单 ID */ id -> {
                        // 当前在所属 Lane 读取、准备复制或筛选的原业务值
                        var v = lane.position(id);
                        if (v != null) p.add(v.publicationValue());
                    });
        // 按当前持仓键读取的不可变风险快照结果列表
        var risk = new ArrayList<RiskSnapshotRuntime>();
        if (positionKeys != null)
            positionKeys.forEach(
                    /* 当前待从账户 Lane 查询的订单、预留、持仓或触发单 ID */ id -> {
                        // 当前在所属 Lane 读取、准备复制或筛选的原业务值
                        var v = lane.riskSnapshot(id);
                        if (v != null) risk.add(v);
                    });
        // 仍 open 的触发单结果列表
        var t = new ArrayList<CoreTriggerOrderState>();
        if (triggerIds != null)
            triggerIds.forEach(
                    /* 当前待从账户 Lane 查询的订单、预留、持仓或触发单 ID */ id -> {
                        // 当前在所属 Lane 读取、准备复制或筛选的原业务值
                        var v = lane.triggerOrder(id);
                        if (v != null && v.status().open()) t.add(v);
                    });
        // 杠杆键值结果列表
        var l = new ArrayList<LeverageValue>();
        if (leverageKeys != null)
            leverageKeys.forEach(/* 当前用户杠杆业务键 */ k -> l.add(new LeverageValue(k, lane.leverage(k))));
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

    /**
     * 读取 Eclipse primitive 长整数集合大小，空引用按零计入候选预算。
     * @param values 只用来计算候选数的 primitive 长整数集合，可空
     */
    private static long size(LongHashSet values) {
        return values == null ? 0 : values.size();
    }

    /**
     * 读取 Agrona primitive 长整数集合大小，空引用按零计入候选预算。
     * @param values 只用来计算候选数的 primitive 长整数集合，可空
     */
    private static long size(org.agrona.collections.LongHashSet values) {
        return values == null ? 0 : values.size();
    }
}
