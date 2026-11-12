package com.surprising.aeron.service.instrument.command;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.protocol.CoreMaintenanceCodec;
import com.surprising.aeron.protocol.RegisterInstrumentCommand;
import com.surprising.aeron.service.core.AccountBook;
import com.surprising.aeron.service.core.identity.IdIndex;
import com.surprising.aeron.service.instrument.CoreInstrument;

/** 标的注册、配置修改及维护状态转换。 */
public final class InstrumentConfigurationCommands {
    private InstrumentConfigurationCommands() { }
    /** 检查输入和所有权，构造本产品线配置并阻止进行中的资金费或生命周期操作。 */
    public static void applyConfiguration(AccountBook accountBook, IdIndex identities,
                                         RegisterInstrumentCommand command) {
        if (accountBook == null || identities == null || command == null) {
            throw new IllegalArgumentException("invalid runtime instrument update");
        }
        accountBook.assertOwner();
        CoreInstrument instrument = CoreInstrument.from(accountBook.productLine(), command);
        int symbolId = identities.symbolId(instrument.instrumentIdValue());
        if (accountBook.systemFunds().fundingProgress(symbolId) != null
                || accountBook.systemFunds().lifecycleProgress(symbolId) != null) {
            throw new CoreStateRejectedException("LIFECYCLE_IN_PROGRESS", "instrument lifecycle is in progress");
        }
        CoreInstrument current = accountBook.instrument(instrument.instrumentId());
        if (current == null) {
            identities.assetId(instrument.baseAsset());
            identities.assetId(instrument.quoteAsset());
            identities.assetId(instrument.settleAsset());
            accountBook.requireRevisionCapacity();
            accountBook.registerInstrument(instrument);
        } else {
            if (!sameInstrumentIdentity(current, instrument)) {
                throw new CoreStateRejectedException("INSTRUMENT_IDENTITY_IMMUTABLE",
                        "contract identity and price units cannot change after registration");
            }
            if (current.configuration().equals(instrument.configuration())) return;
            identities.assetId(instrument.baseAsset());
            identities.assetId(instrument.quoteAsset());
            identities.assetId(instrument.settleAsset());
            accountBook.requireRevisionCapacity();
            accountBook.updateInstrumentConfiguration(current, instrument);
        }
        accountBook.incrementCommandRevision();
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

    /** 先检查标的并允许相同状态直接返回。 */
    public static void updateMaintenance(AccountBook accountBook, IdIndex identities,
                                   CoreMaintenanceCodec.Command command) {
        if (accountBook == null || identities == null || command == null) {
            throw new IllegalArgumentException("invalid runtime instrument maintenance update");
        }
        accountBook.assertOwner();
        CoreInstrument instrument = accountBook.instrument(command.instrumentId());
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
                || accountBook.systemFunds().lifecycleSettlement(identities.symbolId(command.instrumentId())) != before.taskId()))) {
            throw new CoreStateRejectedException("INVALID_COMMAND",
                    "settlement maintenance cannot be released or repriced");
        }
        if (accountBook.productLine() == com.surprising.product.api.ProductLine.SPOT
                && (after.mode() == com.surprising.aeron.protocol.CoreInstrumentMaintenance.Mode.REDUCE_ONLY
                || after.mode() == com.surprising.aeron.protocol.CoreInstrumentMaintenance.Mode.SETTLEMENT)) {
            throw new CoreStateRejectedException("PRODUCT_LINE_UNSUPPORTED",
                    "spot assets cannot be closed as positions");
        }
        int symbolId = identities.symbolId(command.instrumentId());
        if (after.mode() == com.surprising.aeron.protocol.CoreInstrumentMaintenance.Mode.SETTLEMENT
                && accountBook.systemFunds().lifecycleSettlement(symbolId) != 0) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "instrument settlement is already complete");
        }
        if (after.mode() == com.surprising.aeron.protocol.CoreInstrumentMaintenance.Mode.SETTLEMENT
                && accountBook.hasUnresolvedLiquidation(instrument.instrumentId(), symbolId)) {
            throw new CoreStateRejectedException("LIFECYCLE_IN_PROGRESS",
                    "finish active liquidation, insurance and ADL work before fixed-price clearance");
        }
        if (accountBook.systemFunds().fundingProgress(symbolId) != null
                || accountBook.systemFunds().lifecycleProgress(symbolId) != null) {
            throw new CoreStateRejectedException("LIFECYCLE_IN_PROGRESS",
                    "finish the active lifecycle operation first");
        }
        accountBook.requireRevisionCapacity();
        accountBook.updateInstrumentMaintenance(instrument, after);
        accountBook.incrementCommandRevision();
    }
}
