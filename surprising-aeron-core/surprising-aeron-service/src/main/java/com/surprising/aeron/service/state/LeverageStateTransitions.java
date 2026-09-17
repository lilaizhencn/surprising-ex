package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.UpdateLeverageCommand;
import com.surprising.aeron.service.state.math.CoreContractMath;
import com.surprising.aeron.service.state.model.CoreLeverageKey;
import com.surprising.aeron.service.state.model.CoreOrderStatus;

import static com.surprising.aeron.service.state.ReducerSettlementSupport.userOrders;

/** Owns derivative leverage settings and their exposure checks. */
final class LeverageStateTransitions {

    private LeverageStateTransitions() {
    }

    static TradingCoreState update(TradingCoreState state, long userId, UpdateLeverageCommand command) {
        requireUserId(userId);
        if (!state.productLine().isDerivative()) {
            throw new CoreStateRejectedException("PRODUCT_LINE_UNSUPPORTED",
                    "leverage requires derivative product line");
        }
        CoreInstrumentState instrument = state.instruments().get(OrderReservation.normalizeSymbol(command.symbol()));
        if (instrument == null) {
            throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument does not exist");
        }
        if (instrument.contractType().isOption()) {
            throw new CoreStateRejectedException("OPTION_LEVERAGE_UNSUPPORTED",
                    "non-portfolio option margin is not leverage based");
        }
        long requestedRate = CoreContractMath.initialMarginRateFromLeverage(command.leveragePpm());
        long minimumRate = Math.max(instrument.initialMarginRatePpm(),
                CoreContractMath.riskBracket(instrument, 0).initialMarginRatePpm());
        if (requestedRate < minimumRate) {
            throw new CoreStateRejectedException("LEVERAGE_EXCEEDS_INSTRUMENT_LIMIT",
                    "leverage exceeds instrument maximum");
        }
        CoreUserState user = state.users().getOrDefault(userId,
                CoreUserState.empty(state.productLine(), userId));
        boolean openState = userOrders(state, user).stream().anyMatch(order ->
                        order.symbol().equals(instrument.symbol())
                                && order.marginMode() == command.marginMode()
                                && order.status() == CoreOrderStatus.OPEN)
                || user.positions().values().stream().anyMatch(position ->
                        position.symbol().equals(instrument.symbol())
                                && position.marginMode() == command.marginMode()
                                && position.signedQuantitySteps() != 0);
        CoreLeverageKey key = new CoreLeverageKey(userId, instrument.symbol(), command.marginMode());
        Long current = state.leverages().get(key);
        if (openState && (current == null || current.longValue() != command.leveragePpm())) {
            throw new CoreStateRejectedException("LEVERAGE_UPDATE_BLOCKED", "open orders or positions exist");
        }
        if (current != null && current.longValue() == command.leveragePpm()) {
            return state;
        }
        var leverages = StateMapSupport.delta(state.leverages());
        leverages.put(key, command.leveragePpm());
        return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()),
                state.users(), state.orders(), state.instruments(), state.riskState(), state.treasuryState(),
                leverages, state.algoOrders(), state.cancelAllAfterTimers(), state.clientOrderIndex(),
                state.triggerOrders());
    }

    private static void requireUserId(long userId) {
        if (userId <= 0) {
            throw new CoreStateRejectedException("INVALID_USER_ID", "userId must be positive");
        }
    }
}
