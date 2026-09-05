package com.surprising.aeron.protocol;

import com.surprising.product.api.ProductLine;

public record CoreInputEvent(
        int schemaVersion,
        ProductLine productLine,
        CoreMessageType commandType,
        long userId,
        byte[] commandPayload) {

    public CoreInputEvent {
        if (schemaVersion <= 0 || productLine == null || commandType == null
                || commandType.kind() != WireMessageKind.COMMAND || commandPayload == null) {
            throw new IllegalArgumentException("invalid core input event");
        }
        commandPayload = commandPayload.clone();
    }

    @Override
    public byte[] commandPayload() {
        return commandPayload.clone();
    }
}
