package com.surprising.aeron.service.state.position;

import lombok.Getter;
import lombok.experimental.Accessors;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.service.state.instrument.CoreInstrument;

/**
 * 账户 Lane 持有的运行时持仓，平时原位更新整数业务字段；发布时复制字段或借用变更缓冲区槽位。配置引用不会深复制，mutable 用于选择复制与复用方式，线程所有权仍由调用方遵守。
 */
public final class PositionRuntime {
    /**  这份持仓所属的用户编号，持仓身份固定后不应改到其他用户。 返回这份持仓所属的用户编号，持仓身份固定后不应改到其他用户。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long userId;
    /**  这份持仓对应的非负币对整数编号，通过身份表映射到对外合约编号。 返回这份持仓对应的非负币对整数编号，通过身份表映射到对外合约编号。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private int symbolId;
    /**  保证金或结算资产的非负整数编号，金额字段都要按这项资产的单位理解。 返回保证金或结算资产的非负整数编号，金额字段都要按这项资产的单位理解。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private int assetId;
    /**  全仓或逐仓模式，决定持仓权益是否与同资产的其他全仓持仓共享。 返回全仓或逐仓模式，决定持仓权益是否与同资产的其他全仓持仓共享。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private CoreMarginMode marginMode;
    /**  净持仓、多头腿或空头腿，用于区分双向持仓身份。 返回净持仓、多头腿或空头腿，用于区分双向持仓身份。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private CorePositionSide positionSide;
    /**  所属合约的配置引用；发布持仓副本时沿用此引用，不深复制配置。 返回所属合约的配置引用；发布持仓副本时沿用此引用，不深复制配置。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private CoreInstrument instrument;
    /**  带方向的持仓数量步数，正数为多头、负数为空头、0 为空仓。 返回带方向的持仓数量步数，正数为多头、负数为空头、0 为空仓。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long signedQuantitySteps;
    /**  当前持仓的入场价格 tick，空仓时归零。 返回当前持仓的入场价格 tick，空仓时归零。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long entryPriceTicks;
    /**  入场价格与持仓绝对数量对应的累计价格量，空仓时归零。 返回入场价格与持仓绝对数量对应的累计价格量，空仓时归零。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long entryValueTicks;
    /**  已经实现的累计盈亏，正数盈利、负数亏损，单位为结算资产最小单位。 返回已经实现的累计盈亏，正数盈利、负数亏损，单位为结算资产最小单位。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long realizedPnlUnits;
    /**  分配给持仓的非负保证金金额，单位为保证金资产最小单位，空仓时为 0。 返回分配给持仓的非负保证金金额，单位为保证金资产最小单位，空仓时为 0。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long positionMarginUnits;
    /** 转换为 Lane 值或发布值时使用的复用标记：Lane 值为 true，发布副本为 false；它不是写方法的权限校验开关。 */
    private boolean mutable;
    /** 是否为变更缓冲区专用的可复用发布槽，只允许这种目标接收槽位复制或清理。 */
    private boolean publicationSlot;

    /**
     * 创建尚未填入业务字段的发布槽，标记为槽位用途，随后由所属变更缓冲区复制具体持仓数据。
     */
    private PositionRuntime() { publicationSlot = true; }

    /**
     * 校验并创建账户 Lane 的持仓对象，初始化全部身份和金额字段，并标记为可在 Lane 路径复用。
     * @param userId 这份状态所属的用户编号，必须为正数
     * @param symbolId 这份持仓对应的非负币对整数编号
     * @param assetId 保证金或结算资产的非负整数编号
     * @param marginMode 使用全仓还是逐仓保证金；两种模式的可用权益不能混算
     * @param positionSide 净持仓、多头腿或空头腿；双向持仓时用于区分同一合约的两条腿
     * @param instrument 该持仓的合约配置引用，更新时必须保持同一个业务合约
     * @param signedQuantitySteps 带方向的持仓数量步数，正数为多头、负数为空头、0 为无持仓
     * @param entryPriceTicks 当前持仓的入场价格 tick，无持仓时必须为 0
     * @param entryValueTicks 持仓入场价格与绝对数量对应的累计价格量，用于保留入场计算信息，无持仓时为 0
     * @param realizedPnlUnits 已经实现的累计盈亏，正数为盈利、负数为亏损，使用结算资产最小单位
     * @param positionMarginUnits 分配给这份持仓的保证金金额，使用保证金资产最小单位；无持仓时为 0
     */
    public PositionRuntime(long userId, int symbolId, int assetId, CoreMarginMode marginMode,
                           CorePositionSide positionSide, CoreInstrument instrument,
                           long signedQuantitySteps, long entryPriceTicks, long entryValueTicks,
                           long realizedPnlUnits, long positionMarginUnits) {
        validate(userId, symbolId, assetId, marginMode, positionSide, instrument,
                signedQuantitySteps, entryPriceTicks, entryValueTicks, positionMarginUnits);
        this.userId = userId; this.symbolId = symbolId; this.assetId = assetId;
        this.marginMode = marginMode; this.positionSide = positionSide;
        this.instrument = instrument; this.signedQuantitySteps = signedQuantitySteps;
        this.entryPriceTicks = entryPriceTicks; this.entryValueTicks = entryValueTicks;
        this.realizedPnlUnits = realizedPnlUnits; this.positionMarginUnits = positionMarginUnits;
        this.mutable = true;
    }

    /**
     * 检查持仓身份、配置和非负保证金；空仓不能残留入场价、入场价格量或保证金，有仓则必须具备正的入场信息。
     * @param userId 这份状态所属的用户编号，必须为正数
     * @param symbolId 这份持仓对应的非负币对整数编号
     * @param assetId 保证金或结算资产的非负整数编号
     * @param marginMode 使用全仓还是逐仓保证金；两种模式的可用权益不能混算
     * @param positionSide 净持仓、多头腿或空头腿；双向持仓时用于区分同一合约的两条腿
     * @param instrument 该持仓的合约配置引用，更新时必须保持同一个业务合约
     * @param signedQuantitySteps 带方向的持仓数量步数，正数为多头、负数为空头、0 为无持仓
     * @param entryPriceTicks 当前持仓的入场价格 tick，无持仓时必须为 0
     * @param entryValueTicks 持仓入场价格与绝对数量对应的累计价格量，用于保留入场计算信息，无持仓时为 0
     * @param positionMarginUnits 分配给这份持仓的保证金金额，使用保证金资产最小单位；无持仓时为 0
     */
    private static void validate(long userId, int symbolId, int assetId, CoreMarginMode marginMode,
                                 CorePositionSide positionSide, CoreInstrument instrument,
                                 long signedQuantitySteps, long entryPriceTicks, long entryValueTicks,
                                 long positionMarginUnits) {
        if (userId <= 0 || symbolId < 0 || assetId < 0 || marginMode == null || positionSide == null
                || instrument == null
                || positionMarginUnits < 0) throw new IllegalArgumentException("invalid runtime position");
        if (signedQuantitySteps == 0) {
            if (entryPriceTicks != 0 || entryValueTicks != 0 || positionMarginUnits != 0)
                throw new IllegalArgumentException("flat runtime position contains open state");
        } else if (entryPriceTicks <= 0 || entryValueTicks <= 0) {
            throw new IllegalArgumentException("open runtime position is incomplete");
        }
    }


    /**
     * 复制当前持仓的身份和金额字段，并把副本标记为发布值；配置仍引用同一对象，调用方应按发布后只读的约定使用。
     */
    public PositionRuntime snapshot() {
        // 字段已复制、配置引用沿用的持仓副本，返回前会标为发布值。
        PositionRuntime copy = new PositionRuntime(userId, symbolId, assetId, marginMode, positionSide,
                instrument, signedQuantitySteps, entryPriceTicks, entryValueTicks,
                realizedPnlUnits, positionMarginUnits);
        copy.mutable = false;
        return copy;
    }

    /**
     * 把全部身份和业务字段复制到指定发布槽，目标为空时创建槽位；普通持仓对象不能作为槽位接收方，返回引用只在缓冲区约定的使用期内有效。
     * @param target 变更缓冲区提供的发布槽，可为空以便首次创建，不能传入普通 Lane 持仓
     */
    public PositionRuntime copyIntoPublicationSlot(PositionRuntime target) {
        if (target == null) target = new PositionRuntime();
        if (!target.publicationSlot) throw new IllegalArgumentException("position target is not a publication slot");
        target.userId = userId;
        target.symbolId = symbolId;
        target.assetId = assetId;
        target.marginMode = marginMode;
        target.positionSide = positionSide;
        target.instrument = instrument;
        target.signedQuantitySteps = signedQuantitySteps;
        target.entryPriceTicks = entryPriceTicks;
        target.entryValueTicks = entryValueTicks;
        target.realizedPnlUnits = realizedPnlUnits;
        target.positionMarginUnits = positionMarginUnits;
        return target;
    }

    /**
     * 仅清理发布槽持有的配置和枚举引用，供槽位复用或释放引用；标量数值保留，不能再把清理后的槽当作完整持仓读取。
     */
    public void clearPublicationSlot() {
        if (!publicationSlot) throw new IllegalStateException("position is not a publication slot");
        instrument = null;
        marginMode = null;
        positionSide = null;
    }

    /**
     * 已经是 Lane 值时直接返回自身，否则用当前字段创建新的 Lane 持仓，避免把发布对象直接当作账户权威状态。
     */
    public PositionRuntime laneValue() {
        return mutable ? this : new PositionRuntime(userId, symbolId, assetId, marginMode, positionSide,
                instrument, signedQuantitySteps, entryPriceTicks, entryValueTicks, realizedPnlUnits,
                positionMarginUnits);
    }

    /**
     * Lane 值需要先生成发布副本；已经标记为发布值时直接复用当前引用，引用使用期由发布方约定。
     */
    public PositionRuntime publicationValue() { return mutable ? snapshot() : this; }

    /**
     * Owner 镜像收到新发布状态时，先核对用户、币对、资产、保证金模式和持仓腿身份，再原位更新金额；由持有该镜像的线程调用。
     * @param source 需要同步到当前 Owner 镜像的新发布持仓，其身份必须与当前对象一致
     */
    void copyStateFrom(PositionRuntime source) {
        if (source == null || userId != source.userId || symbolId != source.symbolId
                || assetId != source.assetId || marginMode != source.marginMode
                || positionSide != source.positionSide
                || (instrument != source.instrument && (source.instrument == null || !instrument.instrumentId().equals(source.instrument.instrumentId())))) {
            throw new IllegalArgumentException("position publication identity changed");
        }
        applyInPlace(instrument, source.signedQuantitySteps, source.entryPriceTicks,
                source.entryValueTicks, source.realizedPnlUnits, source.positionMarginUnits);
    }

    /**
     * 在原对象上更新数量、入场信息、盈亏及保证金；要求配置指向同一合约并通过持仓校验，调用方负责遵守线程所有权和发布引用使用期。
     * @param instrument 该持仓的合约配置引用，更新时必须保持同一个业务合约
     * @param signedQuantitySteps 带方向的持仓数量步数，正数为多头、负数为空头、0 为无持仓
     * @param entryPriceTicks 当前持仓的入场价格 tick，无持仓时必须为 0
     * @param entryValueTicks 持仓入场价格与绝对数量对应的累计价格量，用于保留入场计算信息，无持仓时为 0
     * @param realizedPnlUnits 已经实现的累计盈亏，正数为盈利、负数为亏损，使用结算资产最小单位
     * @param positionMarginUnits 分配给这份持仓的保证金金额，使用保证金资产最小单位；无持仓时为 0
     */
    public void applyInPlace(CoreInstrument instrument, long signedQuantitySteps, long entryPriceTicks,
                      long entryValueTicks, long realizedPnlUnits, long positionMarginUnits) {
        if (this.instrument != instrument && (instrument == null || !this.instrument.instrumentId().equals(instrument.instrumentId()))) {
            throw new IllegalArgumentException("position instrument mismatch");
        }
        validate(userId, symbolId, assetId, marginMode, positionSide, this.instrument,
                signedQuantitySteps, entryPriceTicks, entryValueTicks, positionMarginUnits);
        this.signedQuantitySteps = signedQuantitySteps;
        this.entryPriceTicks = entryPriceTicks;
        this.entryValueTicks = entryValueTicks;
        this.realizedPnlUnits = realizedPnlUnits;
        this.positionMarginUnits = positionMarginUnits;
    }

    /**
     * 按持仓身份及业务数值比较；配置引用不同时按合约编号比较，不比较 Lane 或发布槽标记。
     * @param other 要比较是否表示同一份业务持仓状态的对象
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof PositionRuntime /* 类型匹配后的另一份持仓，接下来逐项比较业务字段。 */ value)) return false;
        return userId == value.userId && symbolId == value.symbolId && assetId == value.assetId
                && (instrument == value.instrument || (instrument != null && value.instrument != null && instrument.instrumentId().equals(value.instrument.instrumentId())))
                && signedQuantitySteps == value.signedQuantitySteps
                && entryPriceTicks == value.entryPriceTicks && entryValueTicks == value.entryValueTicks
                && realizedPnlUnits == value.realizedPnlUnits && positionMarginUnits == value.positionMarginUnits
                && marginMode == value.marginMode && positionSide == value.positionSide;
    }

    /**
     * 用持仓身份、金额字段及配置对象身份计算散列值，不把 Lane 或发布槽标记计入。
     */
    @Override
    public int hashCode() {
        return java.util.Objects.hash(userId, symbolId, assetId, marginMode, positionSide,
                System.identityHashCode(instrument), signedQuantitySteps, entryPriceTicks, entryValueTicks,
                realizedPnlUnits, positionMarginUnits);
    }

    /**
     * 生成包含用户、币对、资产、持仓腿和带方向数量的简短文本，便于定位运行时持仓。
     */
    @Override
    public String toString() {
        return "PositionRuntime[userId=" + userId + ", symbolId=" + symbolId + ", assetId=" + assetId
                + ", positionSide=" + positionSide + ", signedQuantitySteps=" + signedQuantitySteps + "]";
    }
}
