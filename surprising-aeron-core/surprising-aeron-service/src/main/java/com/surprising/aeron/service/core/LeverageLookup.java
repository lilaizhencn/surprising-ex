package com.surprising.aeron.service.core;

import com.surprising.aeron.model.CoreLeverageKey;
import com.surprising.aeron.protocol.CoreMarginMode;

/**
 * 查杠杆时复用的可变探针，模拟正式杠杆键的哈希与字段比较；
 * 只在所属执行线程查找，不作为映射中的持久键。
 */
public final class LeverageLookup {
    /** 本次杠杆查找的用户身份，下次 set 覆盖。 */
    long userId;
    /** 本次杠杆查找的外部标的 ID，clear 时释放引用。 */
    String instrumentId;
    /** 本次杠杆查找的保证金模式，clear 时释放引用。 */
    CoreMarginMode marginMode;

    /**
     * 替换本次查杠杆所用的用户、标的和保证金模式；这个可变探针只用于查找，不放入持久化映射。
     * @param userId 本次业务对应的用户身份，按固定拓扑路由到账户 Lane
     * @param instrumentId 本产品线的外部标的 ID
     * @param marginMode 用于区分杠杆业务键的保证金模式
     */
    public void set(long userId, String instrumentId, CoreMarginMode marginMode) {
        this.userId = userId;
        this.instrumentId = instrumentId;
        this.marginMode = marginMode;
    }

    /**
     * 释放上次查找保存的标的和模式引用；用户数值可留待下次 set 覆盖，不再用已清理探针查找。
     */
    public void clear() {
        instrumentId = null;
        marginMode = null;
    }

    /**
     * 按 CoreLeverageKey 的 record 字段顺序计算相同哈希，避免为每次成交查找创建新键。
     */
    @Override
    public int hashCode() {
        int hash = 31 * Long.hashCode(userId) + (instrumentId == null ? 0 : instrumentId.hashCode());
        return 31 * hash + (marginMode == null ? 0 : marginMode.hashCode());
    }

    /**
     * 只与正式 CoreLeverageKey 比较三个业务字段，让映射查找命中已有键；它不是可供任意对象双向比较的通用键。
     * @param other 要与当前探针比较的对象，只识别正式 CoreLeverageKey
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof CoreLeverageKey(long id, String storedInstrumentId, CoreMarginMode mode)
                && userId == id
                && (instrumentId != null && instrumentId.equals(storedInstrumentId))
                && marginMode == mode;
    }
}
