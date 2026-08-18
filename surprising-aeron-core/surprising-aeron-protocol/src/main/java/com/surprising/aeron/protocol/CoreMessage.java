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

    public int payloadLength() {
        return payload.length;
    }

    public void copyPayloadTo(byte[] destination, int offset) {
        Objects.requireNonNull(destination, "destination");
        if (offset < 0 || offset > destination.length - payload.length) {
            throw new IndexOutOfBoundsException("payload does not fit destination");
        }
        System.arraycopy(payload, 0, destination, offset, payload.length);
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
