package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.AdjustPositionMarginCommand;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.UpdatePositionModeCommand;
import com.surprising.aeron.service.state.model.AssetBalance;
import com.surprising.aeron.service.state.model.CoreOrderStatus;
import com.surprising.aeron.service.state.model.CorePositionState;

import static com.surprising.aeron.service.state.ReducerSettlementSupport.requireBalance;
import static com.surprising.aeron.service.state.ReducerSettlementSupport.userOrders;

/** Owns derivative position-mode changes and isolated-margin transfers. */
final class PositionStateTransitions {

    private PositionStateTransitions() {
    }

    static TradingCoreState updateMode(
            TradingCoreState state, long userId, UpdatePositionModeCommand command) {
        requireUserId(userId);
        if (!state.productLine().isDerivative()) {
            throw new CoreStateRejectedException("PRODUCT_LINE_UNSUPPORTED",
                    "position mode requires derivative product line");
        }
        CoreUserState user = state.users().getOrDefault(userId,
                CoreUserState.empty(state.productLine(), userId));
        if (user.positionMode() == command.positionMode()) {
            return state;
        }
        boolean openPosition = user.positions().values().stream()
                .anyMatch(position -> position.signedQuantitySteps() != 0);
        boolean openOrder = userOrders(state, user).stream()
                .anyMatch(order -> order.status() == CoreOrderStatus.OPEN);
        if (openPosition || openOrder || user.reservations().values().stream()
                .anyMatch(reservation -> reservation.remainingUnits() != 0)) {
            throw new CoreStateRejectedException("POSITION_MODE_SWITCH_BLOCKED",
                    "open positions or orders block position mode update");
        }
        CoreUserState nextUser = user.transition(Math.incrementExact(user.revision()),
                user.balances(), user.reservations(), user.positions(), command.positionMode());
        return TradingCoreReducer.replaceUser(state, nextUser, state.orders());
    }

    static TradingCoreState adjustMargin(
            TradingCoreState state, long userId, AdjustPositionMarginCommand command) {
        requireUserId(userId);
        if (command.marginMode() != CoreMarginMode.ISOLATED || command.amountUnits() == 0) {
            throw new CoreStateRejectedException("POSITION_MARGIN_ADJUSTMENT_INVALID",
                    "only isolated position margin can be adjusted");
        }
        CoreUserState user = state.user(userId);
        if (user == null) {
            throw new CoreStateRejectedException("POSITION_NOT_FOUND", "position does not exist");
        }
        String symbol = OrderReservation.normalizeSymbol(command.symbol());
        String key = command.positionSide().hedgeSide() ? symbol + ':' + command.positionSide().name() : symbol;
        CorePositionState position = user.positions().get(key);
        if (position == null || position.signedQuantitySteps() == 0
                || position.marginMode() != command.marginMode()
                || position.positionSide() != command.positionSide()) {
            throw new CoreStateRejectedException("POSITION_NOT_FOUND", "isolated position does not exist");
        }
        long units = Math.absExact(command.amountUnits());
        AssetBalance balance = requireBalance(user, position.marginAsset());
        long nextMargin;
        AssetBalance nextBalance;
        if (command.amountUnits() > 0) {
            nextBalance = balance.reserve(units);
            nextMargin = Math.addExact(position.positionMarginUnits(), units);
        } else {
            if (position.positionMarginUnits() < units) {
                throw new CoreStateRejectedException("POSITION_MARGIN_INSUFFICIENT",
                        "position margin is insufficient");
            }
            nextBalance = balance.release(units);
            nextMargin = Math.subtractExact(position.positionMarginUnits(), units);
        }
        var balances = StateMapSupport.delta(user.balances());
        balances.put(nextBalance.asset(), nextBalance);
        var positions = StateMapSupport.delta(user.positions());
        positions.put(key, new CorePositionState(position.symbol(), position.marginAsset(), position.marginMode(),
                position.positionSide(), position.instrumentChangeId(), position.signedQuantitySteps(),
                position.entryPriceTicks(), position.entryValueTicks(), position.realizedPnlUnits(), nextMargin));
        CoreUserState nextUser = user.transition(Math.incrementExact(user.revision()),
                balances, user.reservations(), positions, user.positionMode());
        return TradingCoreReducer.replaceUser(state, nextUser, state.orders());
    }

    private static void requireUserId(long userId) {
        if (userId <= 0) {
            throw new CoreStateRejectedException("INVALID_USER_ID", "userId must be positive");
        }
    }
}
