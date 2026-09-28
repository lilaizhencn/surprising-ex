package com.surprising.aeron.service.command.settlement;

import com.surprising.aeron.service.command.CommandResultContext;
import com.surprising.aeron.service.state.PositionUserIndex;
import com.surprising.aeron.service.state.TreasuryRuntime;
import com.surprising.aeron.service.state.index.ActiveOrderIndex;
import com.surprising.aeron.service.state.model.CoreOrderState;
import java.util.List;

/** Owner capabilities required by instrument lifecycle settlement commands. */
public interface SettlementCommandContext extends CommandResultContext {
    PositionUserIndex positionUserIndex();

    ActiveOrderIndex activeOrderIndex();

    TreasuryRuntime.LifecycleProgressRuntime lifecycleProgress(String instrumentId);

    LifecycleOrderPage settlementLifecycleOrders(long userId, String instrumentId, long cursorOrderId, int maxOrders);

    record LifecycleOrderPage(List<CoreOrderState> orders, long nextCursorOrderId) {
        public boolean more() {
            return nextCursorOrderId != 0;
        }
    }
}
