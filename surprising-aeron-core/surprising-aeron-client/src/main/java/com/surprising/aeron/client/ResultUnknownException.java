package com.surprising.aeron.client;

import java.util.UUID;

public final class ResultUnknownException extends RuntimeException {

    private final UUID commandId;

    public ResultUnknownException(UUID commandId, String message) {
        super(message);
        this.commandId = commandId;
    }

    public UUID commandId() {
        return commandId;
    }
}
