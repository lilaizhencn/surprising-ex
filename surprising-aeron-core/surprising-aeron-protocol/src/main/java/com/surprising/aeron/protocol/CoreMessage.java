package com.surprising.aeron.protocol;

import java.util.Arrays;
import java.util.Objects;

public record CoreMessage(CoreMessageHeader header, byte[] payload) {

    public CoreMessage {
        Objects.requireNonNull(header, "header");
        payload = payload == null ? new byte[0] : Arrays.copyOf(payload, payload.length);
    }

    @Override
    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof CoreMessage message
                && header.equals(message.header)
                && Arrays.equals(payload, message.payload);
    }

    @Override
    public int hashCode() {
        return 31 * header.hashCode() + Arrays.hashCode(payload);
    }
}
