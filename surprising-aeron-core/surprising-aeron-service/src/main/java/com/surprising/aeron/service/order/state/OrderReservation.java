package com.surprising.aeron.service.order.state;

import com.surprising.aeron.model.AssetBalance;
import com.surprising.aeron.protocol.ReservationKind;
import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * 不可变的业务冻结记录，剩余冻结等于总额扣掉累计释放和累计消耗。本对象保存导出、恢复和核对所需的金额事实，实际冻结和余额移动由原账户运行时处理。
 */
public final class OrderReservation {
    /** 这份冻结对应的正数订单编号。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final long orderId;
    /** 已经校验的永久币对编号文本。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final String instrumentId;
    /** 现货资产或衍生品保证金的冻结类别。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final ReservationKind kind;
    /** 已经规范化的冻结资产代码。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final String asset;
    /** 本记录的冻结总额上限，不是当前剩余额度。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final long reservedUnits;
    /** 已经从冻结中释放的累计金额。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final long releasedUnits;
    /** 已经因成交等业务消耗的累计冻结金额。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final long consumedUnits;
    /** 建立冻结时的订单数量步数基数，不随剩余额度变化。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final long orderQuantitySteps;

    /**
     * 校验正数身份、冻结总额和数量，以及累计释放加消耗不超过总额，再校验币对并规范化资产文本。
     * @param orderId 这份冻结对应的正数订单编号
     * @param instrumentId 已经校验的永久币对编号文本
     * @param kind 现货资产或衍生品保证金的冻结类别
     * @param asset 已经规范化的冻结资产代码
     * @param reservedUnits 本记录的冻结总额上限，不是当前剩余额度
     * @param releasedUnits 已经从冻结中释放的累计金额
     * @param consumedUnits 已经因成交等业务消耗的累计冻结金额
     * @param orderQuantitySteps 建立冻结时的订单数量步数基数，不随剩余额度变化
     */
    public OrderReservation(long orderId, String instrumentId, ReservationKind kind,
                            String asset, long reservedUnits, long releasedUnits, long consumedUnits,
                            long orderQuantitySteps) {
        if (orderId <= 0 || kind == null || reservedUnits <= 0
                || releasedUnits < 0 || consumedUnits < 0
                || Math.addExact(releasedUnits, consumedUnits) > reservedUnits || orderQuantitySteps <= 0) {
            throw new IllegalArgumentException("invalid order reservation");
        }
        this.orderId = orderId;
        this.instrumentId = requireInstrumentId(instrumentId);
        this.kind = kind;
        this.asset = AssetBalance.normalizeAsset(asset);
        this.reservedUnits = reservedUnits;
        this.releasedUnits = releasedUnits;
        this.consumedUnits = consumedUnits;
        this.orderQuantitySteps = orderQuantitySteps;
    }

    /**
     * 按所有身份、类别、资产和金额字段比较业务值，不依赖对象是否为同一实例。
     * @param other 需要比较的任意对象
     */
    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof OrderReservation /* 已经匹配为冻结记录类型的待比较值，逐个比较其业务字段。 */ value
                && orderId == value.orderId
                && kind == value.kind && reservedUnits == value.reservedUnits
                && releasedUnits == value.releasedUnits && consumedUnits == value.consumedUnits
                && orderQuantitySteps == value.orderQuantitySteps
                && instrumentId.equals(value.instrumentId) && asset.equals(value.asset);
    }

    /**
     * 按与 equals 一致的身份和金额字段组合哈希值。
     */
    @Override
    public int hashCode() {
        // 按固定字段顺序逐步累计的哈希值。
        int hash = Long.hashCode(orderId);
        hash = 31 * hash + instrumentId.hashCode();
        hash = 31 * hash + kind.hashCode();
        hash = 31 * hash + asset.hashCode();
        hash = 31 * hash + Long.hashCode(reservedUnits);
        hash = 31 * hash + Long.hashCode(releasedUnits);
        hash = 31 * hash + Long.hashCode(consumedUnits);
        return 31 * hash + Long.hashCode(orderQuantitySteps);
    }

    /**
     * 输出订单、币对、类别、资产和各金额，供日志与断言定位冻结差异。
     */
    @Override
    public String toString() {
        return "OrderReservation[orderId=" + orderId + ", instrumentId=" + instrumentId
                + ", kind=" + kind + ", asset=" + asset
                + ", reservedUnits=" + reservedUnits + ", releasedUnits=" + releasedUnits
                + ", consumedUnits=" + consumedUnits + ", orderQuantitySteps=" + orderQuantitySteps + "]";
    }

    /**
     * 用精确算术从总额扣除累计释放及消耗，返回仍可使用的冻结额度。
     */
    public long remainingUnits() {
        return Math.subtractExact(reservedUnits, Math.addExact(releasedUnits, consumedUnits));
    }

    /**
     * 校验永久正整数币对编号文本后原样返回，不重新生成或改写字符串。
     * @param value 需要校验的永久币对编号文本
     */
    public static String requireInstrumentId(String value) {
        com.surprising.product.api.utils.InstrumentIds.parse(value);
        return value;
    }
}
