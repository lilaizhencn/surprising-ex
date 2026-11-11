package com.surprising.aeron.service.core;

import com.surprising.aeron.model.CoreLeverageKey;
import com.surprising.aeron.protocol.CoreMarginMode;

/** 查杠杆时复用的可变探针，模拟正式杠杆键的哈希与字段比较。只在所属执行线程查找，不作为映射中的持久键。 */
public final class LeverageLookup {
    long userId;
    String instrumentId;
    CoreMarginMode marginMode;

    /** 替换本次查杠杆所用的用户、标的和保证金模式。 */
    public void set(long userId, String instrumentId, CoreMarginMode marginMode) {
        this.userId = userId;
        this.instrumentId = instrumentId;
        this.marginMode = marginMode;
    }

    /** 释放上次查找保存的标的和模式引用。 */
    public void clear() {
        instrumentId = null;
        marginMode = null;
    }

    /** 按 CoreLeverageKey 的 record 字段顺序计算相同哈希，避免为每次成交查找创建新键。 */
    @Override
    public int hashCode() {
        int hash = 31 * Long.hashCode(userId) + (instrumentId == null ? 0 : instrumentId.hashCode());
        return 31 * hash + (marginMode == null ? 0 : marginMode.hashCode());
    }

    /** 只与正式 CoreLeverageKey 比较三个业务字段，让映射查找命中已有键。 */
    @Override
    public boolean equals(Object other) {
        return other instanceof CoreLeverageKey(long id, String storedInstrumentId, CoreMarginMode mode)
                && userId == id
                && (instrumentId != null && instrumentId.equals(storedInstrumentId))
                && marginMode == mode;
    }
}
