package com.surprising.trading.order.model;

import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.util.Optional;

public interface ReduceOnlyPositionLookup {

    default Optional<ReduceOnlyPosition> lockedPosition(long userId, String symbol, MarginMode marginMode) {
        return lockedPosition(userId, symbol, marginMode, PositionSide.NET);
    }

    default Optional<ReduceOnlyPosition> lockedPosition(long userId, String symbol, MarginMode marginMode,
                                                        PositionSide positionSide) {
        return lockedPosition(userId, symbol, marginMode);
    }

    default long lockedOpenReduceOnlySteps(long userId, String symbol, MarginMode marginMode, long instrumentVersion,
                                           OrderSide closeSide) {
        return lockedOpenReduceOnlySteps(userId, symbol, marginMode, instrumentVersion, PositionSide.NET, closeSide);
    }

    default long lockedOpenReduceOnlySteps(long userId, String symbol, MarginMode marginMode, long instrumentVersion,
                                           PositionSide positionSide, OrderSide closeSide) {
        return lockedOpenReduceOnlySteps(userId, symbol, marginMode, instrumentVersion, closeSide);
    }
}
