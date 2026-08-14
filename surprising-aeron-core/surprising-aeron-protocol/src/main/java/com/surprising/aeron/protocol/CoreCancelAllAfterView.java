package com.surprising.aeron.protocol;

public record CoreCancelAllAfterView(
        long userId,
        String symbolScope,
        long countdownMillis,
        CoreCancelAllAfterStatus status,
        long triggerAtEpochMillis,
        long updatedAtEpochMillis,
        int canceledOrders,
        int canceledTriggerOrders,
        long revision) {

    public CoreCancelAllAfterView {
        if (userId <= 0 || symbolScope == null || symbolScope.isBlank() || countdownMillis < 0 || status == null
                || triggerAtEpochMillis < 0 || updatedAtEpochMillis <= 0 || canceledOrders < 0
                || canceledTriggerOrders < 0 || revision <= 0) {
            throw new IllegalArgumentException("invalid cancel-all-after view");
        }
    }
}
