package com.surprising.aeron.protocol;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;

public final class CommandFingerprint {

    public static final int LENGTH = 32;
    private static final int CANONICAL_VERSION = 1;
    private static final ThreadLocal<MessageDigest> DIGEST = ThreadLocal.withInitial(CommandFingerprint::newDigest);
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
        MessageDigest digest = DIGEST.get();
        digest.reset();
        updateInt(digest, CANONICAL_VERSION);
        updateInt(digest, header.schemaVersion());
        updateInt(digest, header.messageType().wireCode());
        updateInt(digest, ProductLineWireCode.encode(header.productLine()));
        updateInt(digest, header.route().shardCode());
        updateInt(digest, header.route().version());
        updateInt(digest, header.source().wireCode());
        updateLong(digest, header.userId());
        updateInt(digest, payload.length);
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

    private static void updateInt(MessageDigest digest, int value) {
        updateLong(digest, Integer.toUnsignedLong(value), Integer.BYTES);
    }

    private static void updateLong(MessageDigest digest, long value) {
        updateLong(digest, value, Long.BYTES);
    }

    private static void updateLong(MessageDigest digest, long value, int bytes) {
        for (int shift = 0; shift < bytes * Byte.SIZE; shift += Byte.SIZE) {
            digest.update((byte) (value >>> shift));
        }
    }
}
