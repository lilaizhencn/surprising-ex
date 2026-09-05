package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreCancelAllAfterStatus;
import com.surprising.aeron.protocol.CoreCancelAllAfterView;

public record CoreCancelAllAfterState(
        long userId,
        String symbolScope,
        long countdownMillis,
        CoreCancelAllAfterStatus status,
        long triggerAtEpochMillis,
        long updatedAtEpochMillis,
        int canceledOrders,
        int canceledTriggerOrders,
        long revision) {

    public CoreCancelAllAfterState {
        new CoreCancelAllAfterView(userId, symbolScope, countdownMillis, status, triggerAtEpochMillis,
                updatedAtEpochMillis, canceledOrders, canceledTriggerOrders, revision);
        if (status == CoreCancelAllAfterStatus.ACTIVE && triggerAtEpochMillis == 0) {
            throw new IllegalArgumentException("active cancel-all-after timer requires trigger time");
        }
    }

    public CoreCancelAllAfterKey key() {
        return new CoreCancelAllAfterKey(userId, symbolScope);
    }

    public CoreCancelAllAfterView view() {
        return new CoreCancelAllAfterView(userId, symbolScope, countdownMillis, status, triggerAtEpochMillis,
                updatedAtEpochMillis, canceledOrders, canceledTriggerOrders, revision);
    }
}
