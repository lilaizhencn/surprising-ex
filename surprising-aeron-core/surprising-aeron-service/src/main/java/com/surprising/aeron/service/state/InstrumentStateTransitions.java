package com.surprising.aeron.service.state;
import com.surprising.aeron.service.state.instrument.CoreInstrumentState;

import com.surprising.aeron.protocol.UpsertInstrumentCommand;
import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.state.model.CoreOrderStatus;

import java.util.Map;

/** Owns version-checked instrument configuration changes. */
final class InstrumentStateTransitions {

    private InstrumentStateTransitions() {
    }

    static TradingCoreState upsert(TradingCoreState state, UpsertInstrumentCommand command) {
        CoreInstrumentState instrument = CoreInstrumentState.from(state.productLine(), command);
        CoreInstrumentState current = state.instruments().get(instrument.symbol());
        if (current != null && instrument.lastChangeId() <= current.lastChangeId()) {
            if (instrument.withMaintenance(current.maintenance()).equals(current)) {
                return state;
            }
            throw new CoreStateRejectedException("STALE_INSTRUMENT_CHANGE_ID", "instrument audit id must increase");
        }
        if (current != null && instrument.changeId() == current.changeId()) {
            var statusUpdate = current.withStatus(instrument.status(), instrument.lastChangeId());
            if (!instrument.withMaintenance(current.maintenance()).equals(statusUpdate)) {
                throw new CoreStateRejectedException("INVALID_COMMAND",
                        "calculation changes require a new audit reference");
            }
            Map<String, CoreInstrumentState> updated = StateMapSupport.delta(state.instruments());
            updated.put(instrument.symbol(), statusUpdate);
            return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()), state.users(),
                    state.orders(), updated, state.riskState(), state.treasuryState(), state.leverages(),
                    state.algoOrders(), state.cancelAllAfterTimers(), state.clientOrderIndex(), state.triggerOrders());
        }
        if (current != null && instrument.changeId() < current.changeId()) {
            throw new CoreStateRejectedException("STALE_INSTRUMENT_CHANGE_ID",
                    "calculation audit id cannot decrease");
        }
        boolean openOrder = state.orders().values().stream()
                .anyMatch(order -> order.status() == CoreOrderStatus.OPEN
                        && order.symbol().equals(instrument.symbol()));
        boolean openPosition = state.users().values().stream()
                .flatMap(user -> user.positions().values().stream())
                .anyMatch(position -> position.symbol().equals(instrument.symbol())
                        && position.signedQuantitySteps() != 0);
        if (current != null && (openOrder || openPosition)) {
            throw new CoreStateRejectedException("INSTRUMENT_CHANGE_ID_IN_USE",
                    "cannot replace instrument version with open state");
        }
        Map<String, CoreInstrumentState> instruments = StateMapSupport.delta(state.instruments());
        instruments.put(instrument.symbol(), current == null ? instrument : instrument.withMaintenance(current.maintenance()));
        return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()),
                state.users(), state.orders(), instruments, state.riskState(), state.treasuryState(),
                state.leverages(), state.algoOrders(), state.cancelAllAfterTimers(), state.clientOrderIndex(),
                state.triggerOrders());
    }
}
