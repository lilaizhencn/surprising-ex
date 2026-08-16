package com.surprising.aeron.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;

public final class CommandFingerprint {

    public static final int LENGTH = 32;
    private static final int CANONICAL_VERSION = 1;
    private final byte[] value;

    private CommandFingerprint(byte[] value) {
        if (value.length != LENGTH) {
            throw new IllegalArgumentException("command fingerprint must be " + LENGTH + " bytes");
        }
        this.value = value.clone();
    }

    public static CommandFingerprint of(CoreMessage message) {
        Objects.requireNonNull(message, "message");
        CoreMessageHeader header = message.header();
        byte[] payload = message.payload();
        ByteBuffer canonical = ByteBuffer.allocate(Integer.BYTES * 8 + Long.BYTES + payload.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        canonical.putInt(CANONICAL_VERSION);
        canonical.putInt(header.schemaVersion());
        canonical.putInt(header.messageType().wireCode());
        canonical.putInt(ProductLineWireCode.encode(header.productLine()));
        canonical.putInt(header.route().shardCode());
        canonical.putInt(header.route().version());
        canonical.putInt(header.source().wireCode());
        canonical.putLong(header.userId());
        canonical.putInt(payload.length);
        canonical.put(payload);
        return digest(canonical.array());
    }

    public static CommandFingerprint fromBytes(byte[] value) {
        if (value == null) {
            throw new ProtocolException("command fingerprint is required");
        }
        return new CommandFingerprint(value);
    }

    public byte[] bytes() {
        return value.clone();
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof CommandFingerprint fingerprint
                && Arrays.equals(value, fingerprint.value);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(value);
    }

    @Override
    public String toString() {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte byteValue : value) {
            result.append(String.format("%02x", Byte.toUnsignedInt(byteValue)));
        }
        return result.toString();
    }

    private static CommandFingerprint digest(byte[] canonical) {
        try {
            return new CommandFingerprint(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK does not provide SHA-256", exception);
        }
    }
}
