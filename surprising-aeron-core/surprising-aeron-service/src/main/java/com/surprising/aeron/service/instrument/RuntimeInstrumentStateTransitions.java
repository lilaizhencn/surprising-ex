package com.surprising.aeron.service.instrument;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.protocol.CoreMaintenanceCodec;
import com.surprising.aeron.protocol.RegisterInstrumentCommand;
import com.surprising.aeron.service.core.TradingRuntimeState;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 标的配置与维护任务的状态转换入口。在已有运行时所有权内检查产品线、生命周期冲突和任务归属，沿用原标的外壳修改；真正发生变化后才增加业务修订。 构造约定：只提供静态状态转换，不创建独立状态或线程。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RuntimeInstrumentStateTransitions {

    /**
     * 检查输入和所有权，构造本产品线配置并阻止进行中的资金费或生命周期操作；新标的登记资产身份后注册，旧标的先核对固定合约身份，相同配置直接返回，变化配置保留外壳更新并增加修订。身份表查询发生在部分拒绝检查之前，不保证失败时身份表完全不变。
     * @param runtime 本产品线权威运行时，入口检查当前调用具有既有所有权
     * @param identities 本运行时的标的和资产整数身份表
     * @param command 已解码且由协议构造器检查基础边界的注册或更新配置
     */
    public static void applyConfiguration(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                         RegisterInstrumentCommand command) {
        if (runtime == null || identities == null || command == null) {
            throw new IllegalArgumentException("invalid runtime instrument update");
        }
        runtime.assertOwner();
        // 本次已核对所属产品线的注册或更新候选标的
        CoreInstrument instrument = CoreInstrument.from(runtime.productLine(), command);
        // 身份表解析得到的内部标的索引，供财库进度及强平索引查询
        int symbolId = identities.symbolId(instrument.instrumentIdValue());
        if (runtime.treasury().fundingProgress(symbolId) != null
                || runtime.treasury().lifecycleProgress(symbolId) != null) {
            throw new CoreStateRejectedException("LIFECYCLE_IN_PROGRESS", "instrument lifecycle is in progress");
        }
        // 该标的已有的权威外壳，更新时继续保留该对象
        CoreInstrument current = runtime.instrument(instrument.instrumentId());
        if (current == null) {
            identities.assetId(instrument.baseAsset());
            identities.assetId(instrument.quoteAsset());
            identities.assetId(instrument.settleAsset());
            runtime.requireRevisionCapacity();
            runtime.registerInstrument(instrument);
        } else {
            if (!sameInstrumentIdentity(current, instrument)) {
                throw new CoreStateRejectedException("INSTRUMENT_IDENTITY_IMMUTABLE",
                        "contract identity and price units cannot change after registration");
            }
            if (current.configuration().equals(instrument.configuration())) return;
            identities.assetId(instrument.baseAsset());
            identities.assetId(instrument.quoteAsset());
            identities.assetId(instrument.settleAsset());
            runtime.requireRevisionCapacity();
            runtime.updateInstrumentConfiguration(current, instrument);
        }
        runtime.incrementCommandRevision();
    }

    /**
     * 比较注册后不可修改的合约类型、三种资产、名义乘数、价格及数量单位、结算比例、到期和期权要素；费率、风险参数及交易开关不在这一身份比较内。
     * @param current 已注册的原标的外壳
     * @param updated 本次待应用的新标的配置外壳
     */
    private static boolean sameInstrumentIdentity(CoreInstrument current, CoreInstrument updated) {
        // 原标的不可变配置，作为固定身份比较基线
        var before = current.configuration();
        // 候选不可变配置，只允许固定身份以外的配置变化
        var after = updated.configuration();
        return before.contractType() == after.contractType()
                && before.baseAsset().equals(after.baseAsset())
                && before.quoteAsset().equals(after.quoteAsset())
                && before.settleAsset().equals(after.settleAsset())
                && before.notionalMultiplierUnits() == after.notionalMultiplierUnits()
                && before.priceTickUnits() == after.priceTickUnits()
                && before.quantityStepUnits() == after.quantityStepUnits()
                && before.settleScaleUnits() == after.settleScaleUnits()
                && before.expiryEpochMillis() == after.expiryEpochMillis()
                && before.optionType() == after.optionType()
                && before.strikePriceTicks() == after.strikePriceTicks();
    }

    /**
     * 先检查标的并允许相同状态直接返回；状态变化时核对任务归属、清仓到关闭的完整条件、现货限制、未完强平及生命周期冲突，再更新维护并增加修订。相同状态不重复执行后续转换校验。
     * @param runtime 本产品线权威运行时，入口检查当前调用具有既有所有权
     * @param identities 本运行时的标的和资产整数身份表
     * @param command 带预期原任务 ID 和目标维护状态的已解码命令
     */
    public static void updateMaintenance(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                   CoreMaintenanceCodec.Command command) {
        if (runtime == null || identities == null || command == null) {
            throw new IllegalArgumentException("invalid runtime instrument maintenance update");
        }
        runtime.assertOwner();
        // 本次维护命令要修改的已注册标的原外壳
        CoreInstrument instrument = runtime.instrument(command.instrumentId());
        if (instrument == null) {
            throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument state is missing");
        }
        // 原维护模式、任务 ID 和清仓价，用于校验任务归属及合法转换
        var before = instrument.maintenance();
        // 命令要求的下一维护状态，校验通过后才接入原标的
        var after = command.state();
        if (before.equals(after)) return;
        if (after.mode() == com.surprising.aeron.protocol.CoreInstrumentMaintenance.Mode.CLOSED
                && before.mode() != com.surprising.aeron.protocol.CoreInstrumentMaintenance.Mode.SETTLEMENT) {
            throw new CoreStateRejectedException("INVALID_COMMAND",
                    "closed requires completed fixed-price clearance");
        }
        if (before.taskId() != command.expectedTaskId()
                || (before.taskId() != 0 && after.taskId() != 0 && before.taskId() != after.taskId())) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "another maintenance task owns this instrument");
        }
        if (before.mode() == com.surprising.aeron.protocol.CoreInstrumentMaintenance.Mode.CLOSED
                || (before.mode() == com.surprising.aeron.protocol.CoreInstrumentMaintenance.Mode.SETTLEMENT
                && (after.mode() != com.surprising.aeron.protocol.CoreInstrumentMaintenance.Mode.CLOSED
                || after.settlementPriceTicks() != before.settlementPriceTicks()
                || runtime.treasury().lifecycleSettlement(identities.symbolId(command.instrumentId())) != before.taskId()))) {
            throw new CoreStateRejectedException("INVALID_COMMAND",
                    "settlement maintenance cannot be released or repriced");
        }
        if (runtime.productLine() == com.surprising.product.api.ProductLine.SPOT
                && (after.mode() == com.surprising.aeron.protocol.CoreInstrumentMaintenance.Mode.REDUCE_ONLY
                || after.mode() == com.surprising.aeron.protocol.CoreInstrumentMaintenance.Mode.SETTLEMENT)) {
            throw new CoreStateRejectedException("PRODUCT_LINE_UNSUPPORTED",
                    "spot assets cannot be closed as positions");
        }
        // 身份表解析得到的内部标的索引，供财库进度及强平索引查询
        int symbolId = identities.symbolId(command.instrumentId());
        if (after.mode() == com.surprising.aeron.protocol.CoreInstrumentMaintenance.Mode.SETTLEMENT
                && runtime.treasury().lifecycleSettlement(symbolId) != 0) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "instrument settlement is already complete");
        }
        if (after.mode() == com.surprising.aeron.protocol.CoreInstrumentMaintenance.Mode.SETTLEMENT
                && runtime.hasUnresolvedLiquidation(instrument.instrumentId(), symbolId)) {
            throw new CoreStateRejectedException("LIFECYCLE_IN_PROGRESS",
                    "finish active liquidation, insurance and ADL work before fixed-price clearance");
        }
        if (runtime.treasury().fundingProgress(symbolId) != null
                || runtime.treasury().lifecycleProgress(symbolId) != null) {
            throw new CoreStateRejectedException("LIFECYCLE_IN_PROGRESS",
                    "finish the active lifecycle operation first");
        }
        runtime.requireRevisionCapacity();
        runtime.updateInstrumentMaintenance(instrument, after);
        runtime.incrementCommandRevision();
    }
}
