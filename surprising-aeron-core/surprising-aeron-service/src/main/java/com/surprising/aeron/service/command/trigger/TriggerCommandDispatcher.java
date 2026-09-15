package com.surprising.aeron.service.command.trigger;

import com.surprising.aeron.protocol.CoreMessage;

/** Routes trigger and algorithm-order messages to the trigger lifecycle handler. */
public final class TriggerCommandDispatcher {
    private final TriggerOrderCommands commands;

    public TriggerCommandDispatcher(TriggerOrderCommands commands) {
        this.commands = java.util.Objects.requireNonNull(commands);
    }

    public boolean dispatch(CoreMessage message, long clusterTimestamp) {
        switch (message.header().messageType()) {
            case UPSERT_ALGO_ORDER -> commands.executeUpsertAlgoOrder(message, clusterTimestamp);
            case UPDATE_CANCEL_ALL_AFTER -> commands.executeUpdateCancelAllAfter(message, clusterTimestamp);
            case PLACE_TRIGGER_ORDER -> commands.executePlaceTriggerOrder(message, clusterTimestamp);
            case CANCEL_TRIGGER_ORDER -> commands.executeCancelTriggerOrder(message, clusterTimestamp);
            case CLAIM_TRIGGER_ORDER -> commands.executeClaimTriggerOrder(message, clusterTimestamp);
            case COMPLETE_TRIGGER_ORDER -> commands.executeCompleteTriggerOrder(message, clusterTimestamp);
            case UPDATE_TRIGGER_TRAILING -> commands.executeUpdateTriggerTrailing(message, clusterTimestamp);
            case EXPIRE_TRIGGER_ORDER -> commands.executeExpireTriggerOrder(message, clusterTimestamp);
            case RETRY_TRIGGER_ORDER -> commands.executeRetryTriggerOrder(message, clusterTimestamp);
            case EXECUTE_TRIGGER_ORDER -> commands.executeExecuteTriggerOrder(message, clusterTimestamp);
            default -> { return false; }
        }
        return true;
    }
}
