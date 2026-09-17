package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreCancelAllAfterCommand;
import com.surprising.aeron.protocol.CoreCancelAllAfterStatus;
import com.surprising.aeron.service.state.model.CoreCancelAllAfterKey;
import com.surprising.aeron.service.state.model.CoreCancelAllAfterState;

/** Owns runtime cancel-all-after timer lifecycle and revision checks. */
final class RuntimeCancelAllAfterStateTransitions {

    private RuntimeCancelAllAfterStateTransitions() {
    }

    static void update(TradingRuntimeState runtime, long userId, CoreCancelAllAfterCommand command) {
        if (runtime == null || command == null || userId <= 0) {
            throw new IllegalArgumentException("invalid runtime cancel-all-after update");
        }
        runtime.assertOwner();
        if (command.userId() != userId) {
            throw new CoreStateRejectedException("CANCEL_ALL_AFTER_OWNER_MISMATCH",
                    "cancel-all-after timer belongs to another user");
        }
        CoreCancelAllAfterKey key = new CoreCancelAllAfterKey(userId, command.symbolScope());
        CoreCancelAllAfterState current = runtime.cancelAllAfterTimer(key);
        CoreCancelAllAfterState next;
        switch (command.action()) {
            case SET -> {
                CoreCancelAllAfterStatus status = command.countdownMillis() == 0
                        ? CoreCancelAllAfterStatus.DISABLED : CoreCancelAllAfterStatus.ACTIVE;
                if (status == CoreCancelAllAfterStatus.ACTIVE
                        && command.triggerAtEpochMillis() <= command.updatedAtEpochMillis()) {
                    throw new CoreStateRejectedException("INVALID_CANCEL_ALL_AFTER_TRIGGER",
                            "active cancel-all-after timer must trigger in the future");
                }
                next = new CoreCancelAllAfterState(userId, command.symbolScope(), command.countdownMillis(), status,
                        status == CoreCancelAllAfterStatus.ACTIVE ? command.triggerAtEpochMillis() : 0,
                        command.updatedAtEpochMillis(), 0, 0,
                        current == null ? 1 : Math.incrementExact(current.revision()));
            }
            case CLAIM -> {
                requireRevision(current, command);
                if (current.status() != CoreCancelAllAfterStatus.ACTIVE
                        || current.triggerAtEpochMillis() > command.updatedAtEpochMillis()) {
                    throw new CoreStateRejectedException("CANCEL_ALL_AFTER_NOT_DUE", "timer is not due");
                }
                next = new CoreCancelAllAfterState(userId, current.symbolScope(), current.countdownMillis(),
                        CoreCancelAllAfterStatus.TRIGGERING, current.triggerAtEpochMillis(),
                        command.updatedAtEpochMillis(), current.canceledOrders(), current.canceledTriggerOrders(),
                        Math.incrementExact(current.revision()));
            }
            case COMPLETE -> {
                requireRevision(current, command);
                if (current.status() != CoreCancelAllAfterStatus.TRIGGERING) {
                    throw new CoreStateRejectedException("CANCEL_ALL_AFTER_NOT_CLAIMED", "timer is not claimed");
                }
                next = new CoreCancelAllAfterState(userId, current.symbolScope(), current.countdownMillis(),
                        CoreCancelAllAfterStatus.TRIGGERED, current.triggerAtEpochMillis(),
                        command.updatedAtEpochMillis(), command.canceledOrders(), command.canceledTriggerOrders(),
                        Math.incrementExact(current.revision()));
            }
            case RETRY -> {
                requireRevision(current, command);
                if (current.status() != CoreCancelAllAfterStatus.TRIGGERING) {
                    throw new CoreStateRejectedException("CANCEL_ALL_AFTER_NOT_CLAIMED", "timer is not claimed");
                }
                next = new CoreCancelAllAfterState(userId, current.symbolScope(), current.countdownMillis(),
                        CoreCancelAllAfterStatus.ACTIVE, current.triggerAtEpochMillis(),
                        command.updatedAtEpochMillis(), current.canceledOrders(), current.canceledTriggerOrders(),
                        Math.incrementExact(current.revision()));
            }
            default -> throw new CoreStateRejectedException("INVALID_CANCEL_ALL_AFTER_ACTION", "unsupported action");
        }
        runtime.putCancelAllAfterTimer(key, next);
        runtime.incrementCommandRevision();
    }

    private static void requireRevision(CoreCancelAllAfterState current, CoreCancelAllAfterCommand command) {
        if (current == null) {
            throw new CoreStateRejectedException("CANCEL_ALL_AFTER_NOT_FOUND", "timer not found");
        }
        if (command.expectedRevision() != current.revision()) {
            throw new CoreStateRejectedException("STALE_CANCEL_ALL_AFTER_REVISION", "timer revision is stale");
        }
    }
}
