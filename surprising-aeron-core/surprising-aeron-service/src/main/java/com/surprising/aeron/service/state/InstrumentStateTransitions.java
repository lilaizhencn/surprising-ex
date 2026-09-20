package com.surprising.aeron.service.state;
import com.surprising.aeron.service.state.instrument.CoreInstrument;

import com.surprising.aeron.protocol.RegisterInstrumentCommand;
import com.surprising.aeron.service.exception.CoreStateRejectedException;
import java.util.Map;

/** Registers the immutable startup instrument set. */
final class InstrumentStateTransitions {

    private InstrumentStateTransitions() {
    }

    static TradingCoreState register(TradingCoreState state, RegisterInstrumentCommand command) {
        CoreInstrument instrument = CoreInstrument.from(state.productLine(), command);
        CoreInstrument current = state.instruments().get(instrument.symbol());
        if (current != null)
            throw new CoreStateRejectedException("INVALID_COMMAND", "instrument is already registered");
        Map<String, CoreInstrument> instruments = StateMapSupport.delta(state.instruments());
        instruments.put(instrument.symbol(), instrument);
        return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()),
                state.users(), state.orders(), instruments, state.riskState(), state.treasuryState(),
                state.leverages(), state.algoOrders(), state.cancelAllAfterTimers(), state.clientOrderIndex(),
                state.triggerOrders());
    }
}
