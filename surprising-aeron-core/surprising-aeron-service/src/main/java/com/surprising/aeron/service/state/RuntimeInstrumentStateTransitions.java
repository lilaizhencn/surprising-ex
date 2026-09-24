package com.surprising.aeron.service.state;
import com.surprising.aeron.service.state.instrument.CoreInstrument;

import com.surprising.aeron.protocol.CoreMaintenanceCodec;
import com.surprising.aeron.protocol.RegisterInstrumentCommand;
import com.surprising.aeron.service.exception.CoreStateRejectedException;

/** Owns instrument configuration and maintenance transitions in runtime state. */
public final class RuntimeInstrumentStateTransitions {

    private RuntimeInstrumentStateTransitions() {
    }

    public static void applyConfiguration(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                         RegisterInstrumentCommand command) {
        if (runtime == null || identities == null || command == null) {
            throw new IllegalArgumentException("invalid runtime instrument update");
        }
        runtime.assertOwner();
        CoreInstrument instrument = CoreInstrument.from(runtime.productLine(), command);
        int symbolId = identities.symbolId(instrument.symbol());
        if (runtime.treasury().fundingProgress(symbolId) != null
                || runtime.treasury().lifecycleProgress(symbolId) != null) {
            throw new CoreStateRejectedException("LIFECYCLE_IN_PROGRESS", "instrument lifecycle is in progress");
        }
        CoreInstrument current = runtime.instrument(instrument.symbol());
        if (current == null) {
            identities.assetId(instrument.baseAsset());
            identities.assetId(instrument.quoteAsset());
            identities.assetId(instrument.settleAsset());
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
                && before.settleScaleUnits() == after.settleScaleUnits()
                && before.expiryEpochMillis() == after.expiryEpochMillis()
                && before.optionType() == after.optionType()
                && before.strikePriceTicks() == after.strikePriceTicks();
    }

    public static void updateMaintenance(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                   CoreMaintenanceCodec.Command command) {
        if (runtime == null || identities == null || command == null) {
            throw new IllegalArgumentException("invalid runtime instrument maintenance update");
        }
        runtime.assertOwner();
        CoreInstrument instrument = runtime.instrument(command.symbol());
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
                || runtime.treasury().lifecycleSettlement(identities.symbolId(command.symbol())) != before.taskId()))) {
            throw new CoreStateRejectedException("INVALID_COMMAND",
                    "settlement maintenance cannot be released or repriced");
        }
        if (runtime.productLine() == com.surprising.product.api.ProductLine.SPOT
                && (after.mode() == com.surprising.aeron.protocol.CoreInstrumentMaintenance.Mode.REDUCE_ONLY
                || after.mode() == com.surprising.aeron.protocol.CoreInstrumentMaintenance.Mode.SETTLEMENT)) {
            throw new CoreStateRejectedException("PRODUCT_LINE_UNSUPPORTED",
                    "spot assets cannot be closed as positions");
        }
        int symbolId = identities.symbolId(command.symbol());
        if (after.mode() == com.surprising.aeron.protocol.CoreInstrumentMaintenance.Mode.SETTLEMENT
                && runtime.treasury().lifecycleSettlement(symbolId) != 0) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "instrument settlement is already complete");
        }
        if (after.mode() == com.surprising.aeron.protocol.CoreInstrumentMaintenance.Mode.SETTLEMENT
                && runtime.hasUnresolvedLiquidation(symbolId)) {
            throw new CoreStateRejectedException("LIFECYCLE_IN_PROGRESS",
                    "finish active liquidation, insurance and ADL work before fixed-price clearance");
        }
        if (runtime.treasury().fundingProgress(symbolId) != null
                || runtime.treasury().lifecycleProgress(symbolId) != null) {
            throw new CoreStateRejectedException("LIFECYCLE_IN_PROGRESS",
                    "finish the active lifecycle operation first");
        }
        runtime.updateInstrumentMaintenance(instrument, after);
        runtime.incrementCommandRevision();
    }
}
