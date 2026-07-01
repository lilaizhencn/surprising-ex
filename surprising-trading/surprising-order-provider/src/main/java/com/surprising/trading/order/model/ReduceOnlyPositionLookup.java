package com.surprising.trading.order.model;

import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.MarginMode;
import java.util.Optional;

public interface ReduceOnlyPositionLookup {

    Optional<ReduceOnlyPosition> lockedPosition(long userId, String symbol, MarginMode marginMode);

    long lockedOpenReduceOnlySteps(long userId, String symbol, MarginMode marginMode, long instrumentVersion,
                                   OrderSide closeSide);
}
