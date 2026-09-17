package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.service.state.model.AssetBalance;

/** Owns direct available-balance adjustments in the authoritative user state. */
final class BalanceStateTransitions {

    private BalanceStateTransitions() {
    }

    static TradingCoreState adjust(TradingCoreState state, long userId, BalanceAdjustmentCommand command) {
        requireUserId(userId);
        CoreUserState currentUser = state.users().getOrDefault(userId,
                CoreUserState.empty(state.productLine(), userId));
        String asset = AssetBalance.normalizeAsset(command.asset());
        AssetBalance currentBalance = currentUser.balances().getOrDefault(asset, new AssetBalance(asset, 0, 0));
        AssetBalance nextBalance = currentBalance.adjustAvailable(command.deltaUnits());

        var balances = StateMapSupport.delta(currentUser.balances());
        balances.put(asset, nextBalance);
        CoreUserState nextUser = currentUser.transition(Math.incrementExact(currentUser.revision()), balances,
                currentUser.reservations(), currentUser.positions(), currentUser.positionMode());
        return TradingCoreReducer.replaceUser(state, nextUser, state.orders());
    }

    private static void requireUserId(long userId) {
        if (userId <= 0) {
            throw new CoreStateRejectedException("INVALID_USER_ID", "userId must be positive");
        }
    }
}
