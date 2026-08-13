package com.surprising.aeron.protocol;

import java.util.UUID;

public record CoreExportEvent(
        long exportSequence,
        long appliedCommandCount,
        long businessStateHash,
        UUID commandId,
        CoreMessageType commandType,
        ResponseStatus commandStatus,
        CoreResultCode resultCode,
        long userId,
        byte[] commandPayload) {

    public CoreExportEvent {
        if (exportSequence <= 0 || appliedCommandCount <= 0 || commandId == null || commandType == null
                || commandType.kind() != WireMessageKind.COMMAND || commandStatus == null || resultCode == null
                || commandPayload == null) {
            throw new IllegalArgumentException("invalid core export event");
        }
        commandPayload = commandPayload.clone();
    }

    @Override
    public byte[] commandPayload() {
        return commandPayload.clone();
    }
}
