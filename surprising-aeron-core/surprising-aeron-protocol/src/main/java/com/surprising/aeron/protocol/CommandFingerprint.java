package com.surprising.aeron.protocol;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;

public final class CommandFingerprint {

    public static final int LENGTH = 32;
    private static final int CANONICAL_VERSION = 1;
    private static final int HEADER_LENGTH = Integer.BYTES * 8 + Long.BYTES;
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);
    private final byte[] value;

    private CommandFingerprint(byte[] value) {
        this(value, false);
    }

    private CommandFingerprint(byte[] value, boolean owned) {
        if (value.length != LENGTH) {
            throw new IllegalArgumentException("command fingerprint must be " + LENGTH + " bytes");
        }
        this.value = owned ? value : value.clone();
    }

    public static CommandFingerprint of(CoreMessage message) {
        Objects.requireNonNull(message, "message");
        CoreMessageHeader header = message.header();
        byte[] payload = message.payloadUnsafe();
        Scratch scratch = SCRATCH.get();
        MessageDigest digest = scratch.digest;
        digest.reset();
        int offset = 0;
        offset = putInt(scratch.header, offset, CANONICAL_VERSION);
        offset = putInt(scratch.header, offset, header.schemaVersion());
        offset = putInt(scratch.header, offset, header.messageType().wireCode());
        offset = putInt(scratch.header, offset, ProductLineWireCode.encode(header.productLine()));
        offset = putInt(scratch.header, offset, header.route().shardCode());
        offset = putInt(scratch.header, offset, header.route().version());
        offset = putInt(scratch.header, offset, header.source().wireCode());
        offset = putLong(scratch.header, offset, header.userId());
        offset = putInt(scratch.header, offset, payload.length);
        if (offset != HEADER_LENGTH) throw new IllegalStateException("invalid fingerprint header length");
        digest.update(scratch.header, 0, offset);
        digest.update(payload);
        return new CommandFingerprint(digest.digest(), true);
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

    public byte byteAt(int index) {
        return value[index];
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

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK does not provide SHA-256", exception);
        }
    }

    private static int putInt(byte[] target, int offset, int value) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >>> 8);
        target[offset + 2] = (byte) (value >>> 16);
        target[offset + 3] = (byte) (value >>> 24);
        return offset + Integer.BYTES;
    }

    private static int putLong(byte[] target, int offset, long value) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >>> 8);
        target[offset + 2] = (byte) (value >>> 16);
        target[offset + 3] = (byte) (value >>> 24);
        target[offset + 4] = (byte) (value >>> 32);
        target[offset + 5] = (byte) (value >>> 40);
        target[offset + 6] = (byte) (value >>> 48);
        target[offset + 7] = (byte) (value >>> 56);
        return offset + Long.BYTES;
    }

    private static final class Scratch {
        private final MessageDigest digest = newDigest();
        private final byte[] header = new byte[HEADER_LENGTH];
    }
}
