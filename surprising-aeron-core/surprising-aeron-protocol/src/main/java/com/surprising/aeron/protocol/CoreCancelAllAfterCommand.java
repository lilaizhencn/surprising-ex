package com.surprising.aeron.protocol;

public record CoreCancelAllAfterCommand(
        CoreCancelAllAfterAction action,
        String symbolScope,
        long countdownMillis,
        long triggerAtEpochMillis,
        long expectedRevision,
        int canceledOrders,
        int canceledTriggerOrders,
        long updatedAtEpochMillis) {

    public static CoreCancelAllAfterCommand set(String scope, long countdown, long triggerAt, long now) {
        return new CoreCancelAllAfterCommand(CoreCancelAllAfterAction.SET, scope, countdown, triggerAt, 0, 0, 0, now);
    }

    public static CoreCancelAllAfterCommand claim(String scope, long revision, long now) {
        return new CoreCancelAllAfterCommand(CoreCancelAllAfterAction.CLAIM, scope, 0, 0, revision, 0, 0, now);
    }

    public static CoreCancelAllAfterCommand complete(String scope, long revision,
                                                    int orders, int triggers, long now) {
        return new CoreCancelAllAfterCommand(CoreCancelAllAfterAction.COMPLETE, scope, 0, 0, revision, orders, triggers,
                now);
    }

    public static CoreCancelAllAfterCommand retry(String scope, long revision, long now) {
        return new CoreCancelAllAfterCommand(CoreCancelAllAfterAction.RETRY, scope, 0, 0, revision, 0, 0, now);
    }

    public CoreCancelAllAfterCommand {
        if (action == null || symbolScope == null || symbolScope.isBlank() || updatedAtEpochMillis <= 0)
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
