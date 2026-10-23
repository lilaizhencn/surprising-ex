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

    public static CoreCancelAllAfterCommand set(long userId, String scope, long countdown, long triggerAt, long now) {
        return new CoreCancelAllAfterCommand(CoreCancelAllAfterAction.SET, userId, scope, countdown, triggerAt, 0, 0, 0, now);
    }

    public static CoreCancelAllAfterCommand claim(long userId, String scope, long revision, long now) {
        return new CoreCancelAllAfterCommand(CoreCancelAllAfterAction.CLAIM, userId, scope, 0, 0, revision, 0, 0, now);
    }

    public static CoreCancelAllAfterCommand complete(long userId, String scope, long revision,
                                                    int orders, int triggers, long now) {
        return new CoreCancelAllAfterCommand(CoreCancelAllAfterAction.COMPLETE, userId, scope, 0, 0, revision, orders, triggers, now);
    }

    public static CoreCancelAllAfterCommand retry(long userId, String scope, long revision, long now) {
        return new CoreCancelAllAfterCommand(CoreCancelAllAfterAction.RETRY, userId, scope, 0, 0, revision, 0, 0, now);
    }

    public CoreCancelAllAfterCommand {
        if (action == null || userId <= 0 || symbolScope == null || symbolScope.isBlank() || updatedAtEpochMillis <= 0)
            throw new IllegalArgumentException("invalid cancel-all-after command");
        switch (action) {
            case SET -> {
                if (countdownMillis < 0 || triggerAtEpochMillis < 0)
                    throw new IllegalArgumentException("invalid cancel-all-after timer");
                expectedRevision = 0;
                canceledOrders = canceledTriggerOrders = 0;
            }
            case CLAIM, RETRY, COMPLETE -> {
                if (expectedRevision < 0 || action == CoreCancelAllAfterAction.COMPLETE
                        && (canceledOrders < 0 || canceledTriggerOrders < 0))
                    throw new IllegalArgumentException("invalid cancel-all-after revision or counts");
                countdownMillis = triggerAtEpochMillis = 0;
                if (action != CoreCancelAllAfterAction.COMPLETE) canceledOrders = canceledTriggerOrders = 0;
            }
        }
    }
}
