package com.surprising.aeron.protocol;

import java.util.UUID;
import java.util.List;

public record CoreExportEvent(
        long exportSequence,
        long appliedCommandCount,
        long businessStateHash,
        UUID commandId,
        CoreMessageType commandType,
        ResponseStatus commandStatus,
        CoreResultCode resultCode,
        long userId,
        byte[] commandPayload,
        List<CoreUserStateView> changedUsers,
        List<CoreOrderStateView> changedOrders,
        List<CoreExecutionView> executions) {

    public CoreExportEvent {
        if (exportSequence <= 0 || appliedCommandCount <= 0 || commandId == null || commandType == null
                || commandType.kind() != WireMessageKind.COMMAND || commandStatus == null || resultCode == null
                || commandPayload == null || changedUsers == null || changedOrders == null || executions == null) {
            throw new IllegalArgumentException("invalid core export event");
        }
        commandPayload = commandPayload.clone();
        changedUsers = List.copyOf(changedUsers);
        changedOrders = List.copyOf(changedOrders);
        executions = List.copyOf(executions);
    }

    public CoreExportEvent(long exportSequence, long appliedCommandCount, long businessStateHash,
                           UUID commandId, CoreMessageType commandType, ResponseStatus commandStatus,
                           CoreResultCode resultCode, long userId, byte[] commandPayload) {
        this(exportSequence, appliedCommandCount, businessStateHash, commandId, commandType,
                commandStatus, resultCode, userId, commandPayload, List.of(), List.of(), List.of());
    }

    @Override
    public byte[] commandPayload() {
        return commandPayload.clone();
    }
}
