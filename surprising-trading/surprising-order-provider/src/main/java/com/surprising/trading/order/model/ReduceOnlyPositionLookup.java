package com.surprising.trading.order.model;

import com.surprising.trading.api.model.OrderSide;
import java.util.Optional;

public interface ReduceOnlyPositionLookup {

    Optional<ReduceOnlyPosition> lockedPosition(long userId, String symbol);

    long lockedOpenReduceOnlySteps(long userId, String symbol, long instrumentVersion, OrderSide closeSide);
}
