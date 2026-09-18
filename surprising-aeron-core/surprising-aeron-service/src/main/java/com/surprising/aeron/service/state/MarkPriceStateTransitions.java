package com.surprising.aeron.service.state;
import com.surprising.aeron.service.state.instrument.CoreInstrumentState;

import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
import com.surprising.aeron.protocol.CoreRiskScanControlView;
import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.state.model.CoreMarkPriceState;
import com.surprising.aeron.service.state.model.CoreRiskState;
import com.surprising.aeron.service.state.model.RiskLaneProgress;

import java.util.Map;

/** Owns mark-price writes and the risk-scan invalidation they schedule. */
final class MarkPriceStateTransitions {

    private MarkPriceStateTransitions() {
    }

    static TradingCoreState apply(TradingCoreState state, ApplyMarkPriceCommand command) {
        CoreInstrumentState instrument = requireInstrument(state, command.symbol(), command.instrumentChangeId());
        CoreMarkPriceState current = state.riskState().markPrices().get(instrument.symbol());
        if (current != null && command.priceSequence() <= current.priceSequence()) {
            throw new CoreStateRejectedException("STALE_MARK_PRICE", "mark price sequence must increase");
        }
        Map<String, CoreMarkPriceState> marks = StateMapSupport.delta(state.riskState().markPrices());
        if (instrument.contractType().isOption()
                && (command.indexPriceTicks() <= 0 || command.forwardPriceTicks() <= 0)) {
            throw new CoreStateRejectedException("OPTION_RISK_PRICE_MISSING",
                    "option mark requires index and same-expiry forward prices");
        }
        marks.put(instrument.symbol(), new CoreMarkPriceState(instrument.symbol(), instrument.changeId(),
                command.markPriceTicks(), command.indexPriceTicks(), command.forwardPriceTicks(),
                command.priceSequence(), command.generatedAtEpochMillis()));
        Map<String, CoreRiskState.RiskScan> scans = StateMapSupport.delta(state.riskState().scans());
        CoreRiskState.RiskScan currentScan = scans.get(instrument.symbol());
        long scanStart = currentScan != null && !currentScan.riskComplete()
                ? currentScan.scanStartPriceSequence() : command.priceSequence();
        long lastUserId = currentScan != null && !currentScan.riskComplete() ? currentScan.lastUserId() : 0;
        int accountLaneId = currentScan != null && !currentScan.riskComplete()
                ? currentScan.accountLaneId() : 0;
        CoreRiskScanControlView scanControl = state.riskState().scanControl();
        CoreRiskState.RiskScan markedScan = new CoreRiskState.RiskScan(instrument.symbol(), accountLaneId,
                command.priceSequence(), scanStart, lastUserId, !scanControl.enabled(),
                0, 0, "-", 0, 0, 0, 0, 0,
                true, 0, 0, 0, 0, 0, 0, 0, 0,
                currentScan == null ? 0 : currentScan.lastScheduledRevision());
        if (scanControl.enabled() && currentScan != null && !currentScan.riskComplete()
                && !currentScan.laneProgress().isEmpty()) {
            var laneProgress = new java.util.ArrayList<RiskLaneProgress>(currentScan.laneProgress().size());
            for (var lane : currentScan.laneProgress()) {
                laneProgress.add(new RiskLaneProgress(lane.lastUserId(), lane.complete(), 0, 0, "-", 0,
                        0, 0, 0, 0));
            }
            markedScan = markedScan.withLaneProgress(laneProgress);
        }
        scans.put(instrument.symbol(), markedScan);
        CoreRiskState risk = new CoreRiskState(marks, state.riskState().snapshots(),
                state.riskState().liquidations(), scans, state.riskState().nextLiquidationId(), scanControl,
                Math.incrementExact(state.revision()));
        return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()),
                state.users(), state.orders(), state.instruments(), risk, state.treasuryState(),
                state.leverages(), state.algoOrders(), state.cancelAllAfterTimers(), state.clientOrderIndex(),
                state.triggerOrders());
    }

    private static CoreInstrumentState requireInstrument(TradingCoreState state, String symbol, long version) {
        CoreInstrumentState instrument = state.instruments().get(OrderReservation.normalizeSymbol(symbol));
        if (instrument == null) {
            throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument state is missing");
        }
        if (instrument.changeId() != version) {
            throw new CoreStateRejectedException("INSTRUMENT_CHANGE_ID_CONFLICT", "instrument version differs");
        }
        return instrument;
    }
}
