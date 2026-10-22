package com.surprising.aeron.service.state.order;

import lombok.Getter;
import lombok.experimental.Accessors;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.state.model.AssetBalance;

/**
 * 不可变的业务冻结记录，剩余冻结等于总额扣掉累计释放和累计消耗。金额变化返回新记录并复用已校验身份文本；本对象只描述金额，真实账户余额移动由账户入口处理。
 */
public final class OrderReservation {
    /**  这份冻结对应的正数订单编号。 返回这份冻结对应的正数订单编号。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final long orderId;
    /**  已经校验的永久币对编号文本。 返回已经校验的永久币对编号文本。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final String instrumentId;
    /**  现货资产或衍生品保证金的冻结类别。 返回现货资产或衍生品保证金的冻结类别。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final ReservationKind kind;
    /**  已经规范化的冻结资产代码。 返回已经规范化的冻结资产代码。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final String asset;
    /**  本记录的冻结总额上限，不是当前剩余额度。 返回本记录的冻结总额上限，不是当前剩余额度。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final long reservedUnits;
    /**  已经从冻结中释放的累计金额。 返回已经从冻结中释放的累计金额。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final long releasedUnits;
    /**  已经因成交等业务消耗的累计冻结金额。 返回已经因成交等业务消耗的累计冻结金额。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final long consumedUnits;
    /**  建立冻结时的订单数量步数基数，不随剩余额度变化。 返回建立冻结时的订单数量步数基数，不随剩余额度变化。 */
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
        validateAmounts(orderId, kind, reservedUnits, releasedUnits, consumedUnits,
                orderQuantitySteps);
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
     * 按新的金额构造后状态，继续校验金额守恒并借用原记录已经校验的身份、资产和数量基数。
     * @param previous 提供已校验身份和数量基数的原冻结记录
     * @param reservedUnits 本记录的冻结总额上限，不是当前剩余额度
     * @param releasedUnits 已经从冻结中释放的累计金额
     * @param consumedUnits 已经因成交等业务消耗的累计冻结金额
     */
    private OrderReservation(OrderReservation previous, long reservedUnits, long releasedUnits, long consumedUnits) {
        validateAmounts(previous.orderId, previous.kind, reservedUnits,
                releasedUnits, consumedUnits, previous.orderQuantitySteps);
        orderId = previous.orderId;
        instrumentId = previous.instrumentId;
        kind = previous.kind;
        asset = previous.asset;
        this.reservedUnits = reservedUnits;
        this.releasedUnits = releasedUnits;
        this.consumedUnits = consumedUnits;
        orderQuantitySteps = previous.orderQuantitySteps;
    }

    /**
     * 要求订单与数量为正、冻结类别存在、总额为正且累计释放和消耗非负；两者精确和不能超过总额。
     * @param orderId 这份冻结对应的正数订单编号
     * @param kind 现货资产或衍生品保证金的冻结类别
     * @param reservedUnits 本记录的冻结总额上限，不是当前剩余额度
     * @param releasedUnits 已经从冻结中释放的累计金额
     * @param consumedUnits 已经因成交等业务消耗的累计冻结金额
     * @param orderQuantitySteps 建立冻结时的订单数量步数基数，不随剩余额度变化
     */
    private static void validateAmounts(long orderId, ReservationKind kind,
                                        long reservedUnits, long releasedUnits, long consumedUnits,
                                        long orderQuantitySteps) {
        if (orderId <= 0 || kind == null || reservedUnits <= 0
                || releasedUnits < 0 || consumedUnits < 0
                || Math.addExact(releasedUnits, consumedUnits) > reservedUnits || orderQuantitySteps <= 0) {
            throw new IllegalArgumentException("invalid order reservation");
        }
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
     * 创建尚未释放或消耗的新冻结记录，两项累计量从 0 开始。
     * @param orderId 这份冻结对应的正数订单编号
     * @param instrumentId 已经校验的永久币对编号文本
     * @param kind 现货资产或衍生品保证金的冻结类别
     * @param asset 已经规范化的冻结资产代码
     * @param reservedUnits 本记录的冻结总额上限，不是当前剩余额度
     * @param orderQuantitySteps 建立冻结时的订单数量步数基数，不随剩余额度变化
     */
    public static OrderReservation create(
            long orderId,
            String instrumentId,
            ReservationKind kind,
            String asset,
            long reservedUnits,
            long orderQuantitySteps) {
        return new OrderReservation(orderId, instrumentId, kind, asset,
                reservedUnits, 0, 0, orderQuantitySteps);
    }

    /**
     * 用精确算术从总额扣除累计释放及消耗，返回仍可使用的冻结额度。
     */
    public long remainingUnits() {
        return Math.subtractExact(reservedUnits, Math.addExact(releasedUnits, consumedUnits));
    }

    /**
     * 把全部剩余额度作为正数释放；已经没有余额时会沿用 release 的拒绝规则，不是空操作。
     */
    public OrderReservation releaseAll() {
        return release(remainingUnits());
    }

    /**
     * 要求释放金额为正且不超过剩余额度，返回累计释放增加的新记录，原记录不变。
     * @param units 本次要释放或消耗的正数金额，不能超过剩余额度
     */
    public OrderReservation release(long units) {
        if (units <= 0 || units > remainingUnits()) {
            throw new CoreStateRejectedException("INSUFFICIENT_ORDER_RESERVATION",
                    "order reservation is insufficient for release");
        }
        return new OrderReservation(this, reservedUnits, Math.addExact(releasedUnits, units), consumedUnits);
    }

    /**
     * 要求消耗金额为正且不超过剩余额度，返回累计消耗增加的新记录，原记录不变。
     * @param units 本次要释放或消耗的正数金额，不能超过剩余额度
     */
    public OrderReservation consume(long units) {
        if (units <= 0 || units > remainingUnits()) {
            throw new CoreStateRejectedException("INSUFFICIENT_ORDER_RESERVATION",
                    "order reservation is insufficient for fill");
        }
        return new OrderReservation(this, reservedUnits, releasedUnits, Math.addExact(consumedUnits, units));
    }

    /**
     * 替换冻结总额上限，必须仍为正且足以覆盖已有释放及消耗，原身份和数量基数保持相同。
     * @param newReservedUnits 调整后的冻结总额，不能低于已经释放和消耗的精确和
     */
    public OrderReservation replaceReservedUnits(long newReservedUnits) {
        if (newReservedUnits <= 0 || newReservedUnits < Math.addExact(releasedUnits, consumedUnits)) {
            throw new CoreStateRejectedException("INVALID_REPLACEMENT_RESERVATION",
                    "replacement reservation is below already settled units");
        }
        return new OrderReservation(this, newReservedUnits, releasedUnits, consumedUnits);
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
