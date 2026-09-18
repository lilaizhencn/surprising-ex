package com.surprising.aeron.service.state;
import com.surprising.aeron.service.state.instrument.CoreInstrumentState;

import com.surprising.aeron.protocol.CoreSettlementProgressView;
import com.surprising.aeron.protocol.SettleInstrumentCommand;
import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.state.index.ActiveOrderIndex;
import com.surprising.aeron.service.state.model.CoreOrderState;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Owns the delivery/option settlement bridge and its lifecycle cancellation progress. */
final class SettlementStateTransitions {

    private SettlementStateTransitions() {
    }

    static TradingCoreReducer.SettlementApplication apply(
            TradingCoreState state, SettleInstrumentCommand command, Iterable<Long> indexedUserIds,
            UUID chunkCommandId, ActiveOrderIndex activeOrderIndex) {
        // Simulation uses the same financial kernel as authoritative settlement.
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = RuntimeStateProjector.project(state, identities);
        try {
            CoreSettlementProgressView progress = RuntimeLifecycleSettlement.apply(state, command, indexedUserIds,
                    chunkCommandId, activeOrderIndex, runtime, identities);
            if (runtime.revision() == state.revision()) {
                return new TradingCoreReducer.SettlementApplication(state, progress);
            }
            return new TradingCoreReducer.SettlementApplication(
                    RuntimeStateMaterializer.materialize(runtime, identities), progress);
        } finally {
            runtime.close();
        }
    }

    static TradingCoreState advanceOrderCancellation(
            TradingCoreState state, SettleInstrumentCommand command, Collection<CoreOrderState> orders,
            long nextCursorOrderId, UUID chunkCommandId) {
        if (nextCursorOrderId <= 0 || chunkCommandId == null) {
            throw new IllegalArgumentException("settlement cursor must advance");
        }
        CoreInstrumentState instrument = requireInstrument(state, command.symbol(), command.instrumentChangeId());
        if (instrument.contractType().isOption()) {
            com.surprising.aeron.service.state.math.OptionContractMath.optionSettlementCashUnits(
                    instrument, command.settlementPriceTicks());
        }
        CoreTreasuryState.LifecycleProgress progress = state.treasuryState().lifecycleProgress(command.symbol());
        if (progress != null && (progress.settlementId() != command.settlementId()
                || progress.instrumentChangeId() != command.instrumentChangeId()
                || progress.settlementPriceTicks() != command.settlementPriceTicks()
                || progress.optionCashUnitsPerContract() != command.optionCashUnitsPerContract()
                || progress.ordersComplete() || progress.nextCursorOrderId() != command.cursorOrderId()
                || progress.nextCursorUserId() != command.cursorUserId())) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "settlement cursor does not match progress");
        }
        if (progress == null && (command.cursorOrderId() != 0 || command.cursorUserId() != 0)) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "settlement cursor must start at zero");
        }
        TradingCoreState canceled = OrderStateTransitions.cancelOrders(
                state, orders == null ? List.of() : List.copyOf(orders));
        CoreTreasuryState nextTreasury = canceled.treasuryState().withLifecycleProgress(command.symbol(),
                new CoreTreasuryState.LifecycleProgress(command.settlementId(), command.instrumentChangeId(),
                        command.settlementPriceTicks(), command.optionCashUnitsPerContract(), false,
                        nextCursorOrderId, 0, chunkCommandId));
        return new TradingCoreState(canceled.productLine(), Math.incrementExact(canceled.revision()),
                canceled.users(), canceled.orders(), canceled.instruments(), canceled.riskState(), nextTreasury,
                canceled.leverages(), canceled.algoOrders(), canceled.cancelAllAfterTimers(),
                canceled.clientOrderIndex(), canceled.triggerOrders());
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
