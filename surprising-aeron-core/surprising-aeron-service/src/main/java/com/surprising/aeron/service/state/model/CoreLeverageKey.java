package com.surprising.aeron.service.state.model;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.service.state.order.OrderReservation;

/**
 * 用户杠杆配置的查找键，按用户、合约和全仓或逐仓模式区分，防止不同保证金模式共用一项设置。
 * @param userId 这份状态所属的用户编号，必须为正数
 * @param instrumentId 币对或合约编号，用于关联该产品的配置与状态
 * @param marginMode 使用全仓还是逐仓保证金；两种模式的可用权益不能混算
 */
public record CoreLeverageKey(long userId, String instrumentId, CoreMarginMode marginMode)
        implements Comparable<CoreLeverageKey> {

    /**
     * 规范合约编号，并要求用户编号和保证金模式有效。
     */
    public CoreLeverageKey {
        instrumentId = OrderReservation.requireInstrumentId(instrumentId);
        if (userId <= 0 || marginMode == null) {
            throw new IllegalArgumentException("invalid leverage key");
        }
    }

    /**
     * 依次按用户编号、合约编号、保证金模式排序，为配置遍历和快照提供固定顺序。
     * @param other 需要比较排序位置的另一个杠杆配置键
     */
    @Override
    public int compareTo(CoreLeverageKey other) {
        // 用户编号的比较结果，不同用户直接按编号决定顺序。
        int userComparison = Long.compare(userId, other.userId);
        if (userComparison != 0) return userComparison;
        // 同一用户下合约编号的比较结果，相同合约才继续比较保证金模式。
        int symbolComparison = instrumentId.compareTo(other.instrumentId);
        return symbolComparison != 0 ? symbolComparison : marginMode.compareTo(other.marginMode);
    }
}
