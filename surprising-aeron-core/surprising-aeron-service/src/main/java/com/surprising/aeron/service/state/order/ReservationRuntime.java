package com.surprising.aeron.service.state.order;

import com.surprising.aeron.protocol.ReservationKind;

/**
 * 账户 Lane 的运行态冻结值，原位更新累计释放及消耗；另有独立快照和缓冲区发布槽用于交接。可变、订单槽和发布槽标记决定复制与复用路径，调用方仍须遵守 Lane 所有权和消费者租约。
 */
public final class ReservationRuntime {
    /** 本份冻结所属订单编号。 */
    private long orderId;
    /** 冻结资金所属用户编号。 */
    private long userId;
    /** 币对的运行时稠密编号。 */
    private int symbolId;
    /** 现货资产或衍生品保证金冻结类别。 */
    private ReservationKind kind;
    /** 冻结资产的运行时整数编号。 */
    private int assetId;
    /** 本记录的冻结总额上限，区别于 reservedUnits 返回的剩余额度。 */
    private long totalReservedUnits;
    /** 已经释放的累计金额。 */
    private long releasedUnits;
    /** 已经消耗的累计金额。 */
    private long consumedUnits;
    /** 该冻结建立时的订单数量步数基数。 */
    private long orderQuantitySteps;
    /** 是否作为可变 Lane 值使用的交接标记，发布值由调用方遵守只读约束。 */
    private boolean mutable;
    /** 是否为池化权威订单常驻的冻结槽，只有这类目标允许随该订单复用重置。 */
    private boolean orderSlot;
    /** 是否为物理变更缓冲独占的发布槽，消费者释放后才可覆盖复用。 */
    private boolean publicationSlot;

    /**
     * 校验身份、总额、累计量和订单数量，创建一个普通可变运行态冻结值。
     * @param orderId 本份冻结所属订单编号
     * @param userId 冻结资金所属用户编号
     * @param symbolId 币对的运行时稠密编号
     * @param kind 现货资产或衍生品保证金冻结类别
     * @param assetId 冻结资产的运行时整数编号
     * @param totalReservedUnits 本记录的冻结总额上限，区别于 reservedUnits 返回的剩余额度
     * @param releasedUnits 已经释放的累计金额
     * @param consumedUnits 已经消耗的累计金额
     * @param orderQuantitySteps 该冻结建立时的订单数量步数基数
     */
    public ReservationRuntime(long orderId, long userId, int symbolId, ReservationKind kind,
                              int assetId, long totalReservedUnits,
                              long releasedUnits, long consumedUnits, long orderQuantitySteps) {
        validate(orderId, userId, symbolId, kind, assetId, totalReservedUnits,
                releasedUnits, consumedUnits, orderQuantitySteps);
        this.orderId = orderId; this.userId = userId; this.symbolId = symbolId;
        this.kind = kind; this.assetId = assetId;
        this.totalReservedUnits = totalReservedUnits; this.releasedUnits = releasedUnits;
        this.consumedUnits = consumedUnits; this.orderQuantitySteps = orderQuantitySteps;
        this.mutable = true;
    }

    /**
     * 校验订单和用户正数、币对及资产编号非负、总额及数量为正，并要求累计释放加消耗的精确和不超过总额。
     * @param orderId 本份冻结所属订单编号
     * @param userId 冻结资金所属用户编号
     * @param symbolId 币对的运行时稠密编号
     * @param kind 现货资产或衍生品保证金冻结类别
     * @param assetId 冻结资产的运行时整数编号
     * @param totalReservedUnits 本记录的冻结总额上限，区别于 reservedUnits 返回的剩余额度
     * @param releasedUnits 已经释放的累计金额
     * @param consumedUnits 已经消耗的累计金额
     * @param orderQuantitySteps 该冻结建立时的订单数量步数基数
     */
    private static void validate(long orderId, long userId, int symbolId, ReservationKind kind,
                                 int assetId, long totalReservedUnits, long releasedUnits,
                                 long consumedUnits, long orderQuantitySteps) {
        if (orderId <= 0 || userId <= 0 || symbolId < 0 || kind == null
                || assetId < 0 || totalReservedUnits <= 0 || releasedUnits < 0 || consumedUnits < 0
                || Math.addExact(releasedUnits, consumedUnits) > totalReservedUnits || orderQuantitySteps <= 0) {
            throw new IllegalArgumentException("invalid runtime reservation");
        }
    }

    /**
     * 为权威订单常驻槽准备本次冻结：首次创建并标记订单槽，复用时要求目标确为可变订单槽，重绑身份并把累计量清零。
     * @param target 调用方持有并已允许复用的订单或发布槽，具体方法分别校验其槽类型；null 时创建
     * @param orderId 本份冻结所属订单编号
     * @param userId 冻结资金所属用户编号
     * @param symbolId 币对的运行时稠密编号
     * @param kind 现货资产或衍生品保证金冻结类别
     * @param assetId 冻结资产的运行时整数编号
     * @param reservedUnits 本次建立的正数冻结总额，注意此参数不同于同名剩余额度方法
     * @param quantitySteps 本订单冻结采用的原始数量步数基数
     */
    public static ReservationRuntime prepareForOrderSlot(ReservationRuntime target, long orderId, long userId,
            int symbolId, ReservationKind kind, int assetId, long reservedUnits, long quantitySteps) {
        validate(orderId, userId, symbolId, kind, assetId, reservedUnits, 0, 0, quantitySteps);
        if (target == null) {
            target = new ReservationRuntime(orderId, userId, symbolId, kind, assetId,
                    reservedUnits, 0, 0, quantitySteps);
            target.orderSlot = true;
            return target;
        }
        if (!target.orderSlot || !target.mutable)
            throw new IllegalArgumentException("reservation is not an authoritative order slot");
        target.orderId = orderId; target.userId = userId; target.symbolId = symbolId;
        target.kind = kind; target.assetId = assetId; target.totalReservedUnits = reservedUnits;
        target.releasedUnits = target.consumedUnits = 0;
        target.orderQuantitySteps = quantitySteps;
        return target;
    }

    /**
     * 返回本份冻结所属订单编号。
     */
    public long orderId() { return orderId; }
    /**
     * 返回冻结资金所属用户编号。
     */
    public long userId() { return userId; }
    /**
     * 返回币对的运行时稠密编号。
     */
    public int symbolId() { return symbolId; }
    /**
     * 返回现货资产或衍生品保证金冻结类别。
     */
    public ReservationKind kind() { return kind; }
    /**
     * 返回冻结资产的运行时整数编号。
     */
    public int assetId() { return assetId; }
    /**
     * 返回本记录的冻结总额上限，区别于 reservedUnits 返回的剩余额度。
     */
    public long totalReservedUnits() { return totalReservedUnits; }
    /**
     * 返回已经释放的累计金额。
     */
    public long releasedUnits() { return releasedUnits; }
    /**
     * 返回已经消耗的累计金额。
     */
    public long consumedUnits() { return consumedUnits; }
    /**
     * 返回该冻结建立时的订单数量步数基数。
     */
    public long orderQuantitySteps() { return orderQuantitySteps; }
    /**
     * 返回当前剩余额度，即总冻结减累计释放和消耗，不是最初冻结总额。
     */
    public long reservedUnits() { return Math.subtractExact(totalReservedUnits, Math.addExact(releasedUnits, consumedUnits)); }

    /**
     * 复制当前金额和身份为独立发布值，并标记为非可变用途；后续 Lane 修改原值不影响该副本。
     */
    public ReservationRuntime snapshot() {
        // 复制当前有效身份和金额的新值，标记为发布用途后返回。
        ReservationRuntime copy = new ReservationRuntime(orderId, userId, symbolId, kind, assetId,
                totalReservedUnits, releasedUnits, consumedUnits, orderQuantitySteps);
        copy.mutable = false;
        return copy;
    }

    /**
     * 复制当前状态到变更缓冲独占的发布槽；首次建立槽，后续只能覆盖非可变且非订单槽的发布目标，调用方先确认消费者已释放。
     * @param target 调用方持有并已允许复用的订单或发布槽，具体方法分别校验其槽类型；null 时创建
     */
    public ReservationRuntime copyIntoPublicationSlot(ReservationRuntime target) {
        if (target == null) {
            target = snapshot();
            target.publicationSlot = true;
            return target;
        }
        if (!target.publicationSlot || target.mutable || target.orderSlot)
            throw new IllegalArgumentException("reservation copy target is not a publication slot");
        target.orderId = orderId; target.userId = userId; target.symbolId = symbolId;
        target.kind = kind; target.assetId = assetId; target.totalReservedUnits = totalReservedUnits;
        target.releasedUnits = releasedUnits; target.consumedUnits = consumedUnits;
        target.orderQuantitySteps = orderQuantitySteps;
        return target;
    }

    /**
     * 只清理发布槽的订单、用户身份和类别引用，保留存储等待下一次覆盖；清理后不能当有效冻结读取。
     */
    public void clearPublicationSlot() {
        if (!publicationSlot || mutable || orderSlot)
            throw new IllegalStateException("reservation is not a publication slot");
        orderId = userId = 0;
        kind = null;
    }

    /**
     * 已有可变 Lane 值直接返回，发布值则复制成新的可变运行态值，不把发布对象当权威对象接管。
     */
    public ReservationRuntime laneValue() {
        return mutable ? this : new ReservationRuntime(orderId, userId, symbolId, kind, assetId,
                totalReservedUnits, releasedUnits, consumedUnits, orderQuantitySteps);
    }

    /**
     * 无论当前标记如何，都创建新的可变 Lane 值并复制身份及金额。
     */
    ReservationRuntime copyForLane() {
        return new ReservationRuntime(orderId, userId, symbolId, kind, assetId,
                totalReservedUnits, releasedUnits, consumedUnits, orderQuantitySteps);
    }

    /**
     * 可变值先复制快照，已有发布值直接返回，供原交接边界按只读契约使用。
     */
    public ReservationRuntime publicationValue() { return mutable ? snapshot() : this; }

    /**
     * 核对来源非空、订单和用户身份相同及新累计金额有效后，原位采用发布的释放和消耗计数；不改总额或身份。
     * @param source 带相同订单及用户身份的发布来源，用来核对应用目标
     * @param released 需要采用的新累计释放金额
     * @param consumed 需要采用的新累计消耗金额
     */
    void applyPublishedStateInPlace(ReservationRuntime source, long released, long consumed) {
        if (source == null || orderId != source.orderId || userId != source.userId
                || released < 0 || consumed < 0 || Math.addExact(released, consumed) > totalReservedUnits) {
            throw new IllegalStateException("invalid published reservation state");
        }
        releasedUnits = released;
        consumedUnits = consumed;
    }

    /**
     * 在所属 Lane 原位增加累计消耗，允许 0，负数或超过当前剩余额度立即拒绝。
     * @param units 本次要释放或消耗的非负金额，不得超过当前剩余额度
     */
    public void consumeInPlace(long units) {
        if (units < 0 || units > reservedUnits()) throw new IllegalArgumentException("invalid runtime consumption");
        consumedUnits = Math.addExact(consumedUnits, units);
    }

    /**
     * 在所属 Lane 原位增加累计释放，允许 0，负数或超过当前剩余额度立即拒绝。
     * @param units 本次要释放或消耗的非负金额，不得超过当前剩余额度
     */
    public void releaseInPlace(long units) {
        if (units < 0 || units > reservedUnits()) throw new IllegalArgumentException("invalid runtime release");
        releasedUnits = Math.addExact(releasedUnits, units);
    }

    /**
     * 把累计消耗单调推进到指定值，不能回退或使释放加消耗超过总额。
     * @param nextConsumed 新的累计消耗总数，不得小于当前值
     */
    public void setConsumedInPlace(long nextConsumed) {
        if (nextConsumed < consumedUnits || Math.addExact(releasedUnits, nextConsumed) > totalReservedUnits)
            throw new IllegalArgumentException("invalid runtime consumption");
        consumedUnits = nextConsumed;
    }

    /**
     * 只能保持或减少剩余额度；相同值返回自身，减少则把差额计入累计释放并返回新可变值。
     * @param remainingUnits 希望保留的新剩余额度，只能保持或减少
     */
    public ReservationRuntime withRemainingUnits(long remainingUnits) {
        if (remainingUnits < 0 || remainingUnits > reservedUnits())
            throw new IllegalArgumentException("invalid runtime reservation remainder");
        if (remainingUnits == reservedUnits()) return this;
        return new ReservationRuntime(orderId, userId, symbolId, kind, assetId,
                totalReservedUnits, Math.addExact(releasedUnits, reservedUnits() - remainingUnits),
                consumedUnits, orderQuantitySteps);
    }

    /**
     * 非原位释放路径：0 返回自身，正数返回累计释放增加的新可变值，原对象不变。
     * @param units 本次要释放或消耗的非负金额，不得超过当前剩余额度
     */
    public ReservationRuntime release(long units) {
        if (units < 0 || units > reservedUnits()) throw new IllegalArgumentException("invalid runtime release");
        if (units == 0) return this;
        return new ReservationRuntime(orderId, userId, symbolId, kind, assetId,
                totalReservedUnits, Math.addExact(releasedUnits, units), consumedUnits, orderQuantitySteps);
    }

    /**
     * 非原位消耗路径：0 返回自身，正数返回累计消耗增加的新可变值，原对象不变。
     * @param units 本次要释放或消耗的非负金额，不得超过当前剩余额度
     */
    public ReservationRuntime consume(long units) {
        if (units < 0 || units > reservedUnits()) throw new IllegalArgumentException("invalid runtime consumption");
        if (units == 0) return this;
        return new ReservationRuntime(orderId, userId, symbolId, kind, assetId,
                totalReservedUnits, releasedUnits, Math.addExact(consumedUnits, units), orderQuantitySteps);
    }

    /**
     * 只按身份、类别、数量和金额比较业务值，不把槽类型和可变用途标记作为业务差异。
     * @param other 需要进行业务值比较的任意对象
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ReservationRuntime /* 已经确认类型的待比较冻结值，只核对业务字段。 */ value)) return false;
        return orderId == value.orderId && userId == value.userId && symbolId == value.symbolId
                && assetId == value.assetId
                && totalReservedUnits == value.totalReservedUnits && releasedUnits == value.releasedUnits
                && consumedUnits == value.consumedUnits && orderQuantitySteps == value.orderQuantitySteps
                && kind == value.kind;
    }

    /**
     * 按与 equals 相同的业务字段组合哈希值，忽略槽类型标记。
     */
    @Override
    public int hashCode() {
        return java.util.Objects.hash(orderId, userId, symbolId, kind, assetId,
                totalReservedUnits, releasedUnits, consumedUnits, orderQuantitySteps);
    }

    /**
     * 输出订单、用户、资产和冻结金额，供诊断资金差异。
     */
    @Override
    public String toString() {
        return "ReservationRuntime[orderId=" + orderId + ", userId=" + userId + ", assetId=" + assetId
                + ", totalReservedUnits=" + totalReservedUnits + ", releasedUnits=" + releasedUnits
                + ", consumedUnits=" + consumedUnits + "]";
    }
}
