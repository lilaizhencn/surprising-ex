package com.surprising.aeron.service.instrument.command;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.protocol.CoreMaintenanceCodec;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.RegisterInstrumentCommand;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.instrument.CoreInstrument;

/**
 * 标的注册、配置修改及维护状态命令入口。解码请求后执行标的状态转换，成功返回后请求提交发布；产品线隔离和具体拒绝条件在此处理。
 */
public final class InstrumentConfigurationCommands {
    /** 本产品线唯一交易 Owner，持有标的、身份表和提交发布入口。 */
    private final TradingCoreRuntime owner;

    /**
     * 保存非空 Owner 引用，不创建或启动工作线程。
     * @param owner 本产品线交易 Owner，提供命令上下文、权威状态与提交入口
     */
    public InstrumentConfigurationCommands(TradingCoreRuntime owner) { this.owner = java.util.Objects.requireNonNull(owner); }

    /**
     * 解码标的参数并应用注册或更新，转换正常返回后请求发布；相同配置是否需要增加修订由转换方法决定。
     * @param message 当前借用的请求头和载荷，解码完成前保持缓冲有效
     */
    public void executeRegisterInstrument(CoreMessage message) {
        var command = TradingCommandCodec.decodeRegisterInstrument(message.payloadUnsafe(), owner.runtimeState().productLine());
        applyConfiguration(owner.runtimeState(), owner.identities(), command);
        owner.requestCommitPublication();
    }

    /**
     * 解码带任务归属的维护命令，交给状态转换检查并修改维护状态，成功后请求发布。
     * @param message 当前借用的请求头和载荷，解码完成前保持缓冲有效
     */
    public void executeUpdateInstrumentMaintenance(CoreMessage message) {
        updateMaintenance(owner.runtimeState(), owner.identities(),
                CoreMaintenanceCodec.decodeCommand(message.payloadUnsafe()));
        owner.requestCommitPublication();
    }

    /**
     * 检查输入和所有权，构造本产品线配置并阻止进行中的资金费或生命周期操作；新标的登记资产身份后注册，旧标的先核对固定合约身份，相同配置直接返回，变化配置保留外壳更新并增加修订。
     */
    public static void applyConfiguration(TradingAccountRuntime runtime, RuntimeIdentityRegistry identities,
                                         RegisterInstrumentCommand command) {
        if (runtime == null || identities == null || command == null) {
            throw new IllegalArgumentException("invalid runtime instrument update");
        }
        runtime.assertOwner();
        CoreInstrument instrument = CoreInstrument.from(runtime.productLine(), command);
        int symbolId = identities.symbolId(instrument.instrumentIdValue());
        if (runtime.treasury().fundingProgress(symbolId) != null
                || runtime.treasury().lifecycleProgress(symbolId) != null) {
            throw new CoreStateRejectedException("LIFECYCLE_IN_PROGRESS", "instrument lifecycle is in progress");
        }
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

    private static boolean sameInstrumentIdentity(CoreInstrument current, CoreInstrument updated) {
        var before = current.configuration();
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
     * 先检查标的并允许相同状态直接返回；状态变化时核对任务归属、清仓到关闭的完整条件、现货限制、未完强平及生命周期冲突，再更新维护并增加修订。
     */
    public static void updateMaintenance(TradingAccountRuntime runtime, RuntimeIdentityRegistry identities,
                                   CoreMaintenanceCodec.Command command) {
        if (runtime == null || identities == null || command == null) {
            throw new IllegalArgumentException("invalid runtime instrument maintenance update");
        }
        runtime.assertOwner();
        CoreInstrument instrument = runtime.instrument(command.instrumentId());
        if (instrument == null) {
            throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument state is missing");
        }
        var before = instrument.maintenance();
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
