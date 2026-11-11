package com.surprising.aeron.model;

import com.surprising.aeron.protocol.CoreMarginMode;

/** 用户杠杆配置的查找键，按用户、合约和全仓或逐仓模式区分，防止不同保证金模式共用一项设置。 */
public record CoreLeverageKey(long userId, String instrumentId, CoreMarginMode marginMode)
        implements Comparable<CoreLeverageKey> {

    public CoreLeverageKey {
        instrumentId = InstrumentId.requireValid(instrumentId);
        if (userId <= 0 || marginMode == null) {
            throw new IllegalArgumentException("invalid leverage key");
        }
    }

    /** 依次按用户编号、合约编号、保证金模式排序，为配置遍历和快照提供固定顺序。 */
    @Override
    public int compareTo(CoreLeverageKey other) {
        int userComparison = Long.compare(userId, other.userId);
        if (userComparison != 0) return userComparison;
        int symbolComparison = instrumentId.compareTo(other.instrumentId);
        return symbolComparison != 0 ? symbolComparison : marginMode.compareTo(other.marginMode);
    }
}
