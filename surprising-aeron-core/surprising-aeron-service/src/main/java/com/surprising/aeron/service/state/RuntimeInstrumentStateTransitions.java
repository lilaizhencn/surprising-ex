package com.surprising.aeron.service.state;
import com.surprising.aeron.service.state.instrument.CoreInstrumentState;

import com.surprising.aeron.protocol.CoreMaintenanceCodec;
import com.surprising.aeron.protocol.UpsertInstrumentCommand;
import com.surprising.aeron.service.exception.CoreStateRejectedException;

/** Owns instrument configuration and maintenance transitions in runtime state. */
final class RuntimeInstrumentStateTransitions {

    private RuntimeInstrumentStateTransitions() {
    }

    static void upsert(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                       UpsertInstrumentCommand command) {
        if (runtime == null || identities == null || command == null) {
            throw new IllegalArgumentException("invalid runtime instrument update");
        }
        runtime.assertOwner();
        CoreInstrumentState instrument = CoreInstrumentState.from(runtime.productLine(), command);
        CoreInstrumentState current = runtime.instrument(instrument.symbol());
        if (current != null && instrument.lastChangeId() <= current.lastChangeId()) {
            if (instrument.withMaintenance(current.maintenance()).equals(current)) return;
            throw new CoreStateRejectedException("STALE_INSTRUMENT_CHANGE_ID", "instrument audit id must increase");
        }
        if (current != null && instrument.changeId() == current.changeId()) {
            var statusUpdate = current.withStatus(instrument.status(), instrument.lastChangeId());
            if (!instrument.withMaintenance(current.maintenance()).equals(statusUpdate)) {
                throw new CoreStateRejectedException("INVALID_COMMAND",
                        "calculation changes require a new audit reference");
            }
            runtime.putInstrument(statusUpdate);
            runtime.incrementCommandRevision();
            return;
        }
        if (current != null && instrument.changeId() < current.changeId()) {
            throw new CoreStateRejectedException("STALE_INSTRUMENT_CHANGE_ID",
                    "calculation audit id cannot decrease");
        }
        int symbolId = identities.symbolId(instrument.symbol());
        if (runtime.treasury().fundingProgress(symbolId) != null
                || runtime.treasury().lifecycleProgress(symbolId) != null) {
            throw new CoreStateRejectedException("LIFECYCLE_IN_PROGRESS", "instrument lifecycle is in progress");
        }
        identities.assetId(instrument.baseAsset());
        identities.assetId(instrument.quoteAsset());
        identities.assetId(instrument.settleAsset());
        if (current != null && runtime.hasPublishedInstrumentExposure(symbolId)) {
            throw new CoreStateRejectedException("INSTRUMENT_CHANGE_ID_IN_USE",
                    "cannot replace instrument version with open state");
        }
        runtime.putInstrument(current == null ? instrument : instrument.withMaintenance(current.maintenance()));
        runtime.incrementCommandRevision();
    }

    static void updateMaintenance(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                   CoreMaintenanceCodec.Command command) {
        if (runtime == null || identities == null || command == null) {
            throw new IllegalArgumentException("invalid runtime instrument maintenance update");
        }
        runtime.assertOwner();
        CoreInstrumentState instrument = runtime.instrument(command.symbol());
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
        runtime.putInstrument(instrument.withMaintenance(after));
        runtime.incrementCommandRevision();
    }
}
