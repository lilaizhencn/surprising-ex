package com.surprising.aeron.service.state.admission;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.service.state.ResolvedPlaceOrder;

/** Owner-confined view of active orders used by order admission. */
public interface AdmissionOrderIndex {
    AdmissionSummary inspect(long userId, String symbol, CorePositionSide positionSide,
                             CoreOrderSide side, CoreMarginMode conflictingMarginMode);

    default void admitted(long userId, ResolvedPlaceOrder order) {
    }
}
