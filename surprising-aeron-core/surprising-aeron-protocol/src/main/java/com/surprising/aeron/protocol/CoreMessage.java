package com.surprising.aeron.protocol;

import java.util.Arrays;
import java.util.Objects;

public final class CoreMessage {

    private final CoreMessageHeader header;
    private final byte[] payload;

    public CoreMessage(CoreMessageHeader header, byte[] payload) {
        this(header, payload, false);
    }

    public static CoreMessage owned(CoreMessageHeader header, byte[] payload) {
        return new CoreMessage(header, payload, true);
    }

    private CoreMessage(CoreMessageHeader header, byte[] payload, boolean owned) {
        this.header = Objects.requireNonNull(header, "header");
        this.payload = owned
                ? Objects.requireNonNull(payload, "payload")
                : payload == null ? new byte[0] : Arrays.copyOf(payload, payload.length);
    }

    public CoreMessageHeader header() {
        return header;
    }

    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }

    public byte[] payloadUnsafe() {
        return payload;
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
        return this == other || other instanceof CoreMessage message
                && header.equals(message.header)
                && Arrays.equals(payload, message.payload);
    }

    @Override
    public int hashCode() {
        return 31 * header.hashCode() + Arrays.hashCode(payload);
    }

    @Override
    public String toString() {
        return "CoreMessage[header=" + header + ", payload=" + Arrays.toString(payload) + "]";
    }
}
