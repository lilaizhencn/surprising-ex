package com.surprising.aeron.protocol;

public record CoreCancelAllAfterCommand(
        CoreCancelAllAfterAction action,
        long userId,
        String symbolScope,
        long countdownMillis,
        long triggerAtEpochMillis,
        long expectedRevision,
        int canceledOrders,
        int canceledTriggerOrders,
        long updatedAtEpochMillis) {

    public CoreCancelAllAfterCommand {
        if (action == null || userId <= 0 || symbolScope == null || symbolScope.isBlank()
                || countdownMillis < 0 || triggerAtEpochMillis < 0 || expectedRevision < 0
                || canceledOrders < 0 || canceledTriggerOrders < 0 || updatedAtEpochMillis <= 0) {
            throw new IllegalArgumentException("invalid cancel-all-after command");
        }
    }
}
