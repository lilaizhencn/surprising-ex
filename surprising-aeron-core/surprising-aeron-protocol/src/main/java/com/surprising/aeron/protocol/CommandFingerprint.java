package com.surprising.aeron.protocol;

import java.security.DigestException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;

public final class CommandFingerprint {

    public static final int LENGTH = 32;
    private static final int CANONICAL_VERSION = 2;
    private static final int HEADER_LENGTH = Integer.BYTES * 6 + Long.BYTES;
    private final byte[] value;
    private final boolean slotValue;
    private boolean initialized;

    private CommandFingerprint(byte[] value) {
        this(value, false);
    }

    private CommandFingerprint(byte[] value, boolean owned) {
        this(value, owned, false);
    }

    private CommandFingerprint(byte[] value, boolean owned, boolean slotValue) {
        if (value.length != LENGTH) {
            throw new IllegalArgumentException("command fingerprint must be " + LENGTH + " bytes");
        }
        this.value = owned ? value : value.clone();
        this.slotValue = slotValue;
        initialized = !slotValue;
    }

    /** The owning decoder or result record may overwrite this metadata only between its leases. */
    public static CommandFingerprint reusableSlot() {
        return new CommandFingerprint(new byte[LENGTH], true, true);
    }

    public boolean isSlotValue() { return slotValue; }

    public CommandFingerprint copyIntoSlot(CommandFingerprint target) {
        requireInitialized();
        if (target == null) target = reusableSlot();
        if (!target.slotValue) throw new IllegalArgumentException("fingerprint target is not a reusable slot");
        System.arraycopy(value, 0, target.value, 0, LENGTH);
        target.initialized = true;
        return target;
    }

    public CommandFingerprint immutableValue() {
        requireInitialized();
        return slotValue ? new CommandFingerprint(value) : this;
    }

    void clearSlot() {
        if (!slotValue) throw new IllegalStateException("fingerprint is not a reusable slot");
        initialized = false;
    }

    private void requireInitialized() {
        if (slotValue && !initialized) throw new IllegalStateException("fingerprint slot is not leased");
    }

    public static CommandFingerprint of(CoreMessage message) {
        return new Calculator().of(message);
    }

    /** One serial producer owns the digest/header; only ofBorrowed opts into an input lease. */
    public static final class Calculator {
        private final MessageDigest digest = sha256();
        private final byte[] header = new byte[HEADER_LENGTH];

        public CommandFingerprint of(CoreMessage message) {
            byte[] value = new byte[LENGTH];
            computeDigest(message, value, 0);
            return new CommandFingerprint(value, true);
        }

        /**
         * Explicit input-lease API for the serial Aeron producer. The payload/header must remain
         * unchanged for the lease. Retainers copy into their own slot or request immutableValue().
         */
        public CommandFingerprint ofBorrowed(CoreMessage message) {
            Objects.requireNonNull(message, "message");
            CommandFingerprint target = message.fingerprintSlot();
            if (target == null) return of(message);
            if (target.initialized) return target;
            computeDigest(message, target.value, 0);
            target.initialized = true;
            return target;
        }

        private void computeDigest(CoreMessage message, byte[] target, int targetOffset) {
            Objects.requireNonNull(message, "message");
            CoreMessageHeader msgHeader = message.header();
            byte[] payload = message.payloadUnsafe();
            int payloadLength = message.payloadLength();

            int offset = 0;
            offset = putInt(this.header, offset, CANONICAL_VERSION);
            offset = putInt(this.header, offset, msgHeader.schemaVersion());
            offset = putInt(this.header, offset, msgHeader.messageType().wireCode());
            offset = putInt(this.header, offset, ProductLineWireCode.encode(msgHeader.productLine()));
            offset = putInt(this.header, offset, msgHeader.source().wireCode());
            offset = putLong(this.header, offset, msgHeader.userId());
            offset = putInt(this.header, offset, payloadLength);
            if (offset != HEADER_LENGTH) throw new IllegalStateException("invalid fingerprint header length");

            // Canonical-v2 is SHA-256 over these exact header and payload bytes. Snapshots and
            // committed receipts retain this identity; changing only the hash breaks retry safety.
            digest.reset();
            digest.update(this.header, 0, HEADER_LENGTH);
            digest.update(payload, 0, payloadLength);
            try {
                digest.digest(target, targetOffset, LENGTH);
            } catch (DigestException impossible) {
                throw new IllegalStateException("invalid fingerprint destination", impossible);
            }
        }
    }

    public static CommandFingerprint fromBytes(byte[] value) {
        if (value == null) {
            throw new ProtocolException("command fingerprint is required");
        }
        return new CommandFingerprint(value);
    }

    public byte[] bytes() {
        requireInitialized();
        return value.clone();
    }

    public byte byteAt(int index) {
        requireInitialized();
        return value[index];
    }

    @Override
    public boolean equals(Object other) {
        requireInitialized();
        if (other instanceof CommandFingerprint fingerprint) fingerprint.requireInitialized();
        return this == other || other instanceof CommandFingerprint fingerprint
                && Arrays.equals(value, fingerprint.value);
    }

    @Override
    public int hashCode() {
        requireInitialized();
        return Arrays.hashCode(value);
    }

    @Override
    public String toString() {
        requireInitialized();
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte byteValue : value) {
            result.append(String.format("%02x", Byte.toUnsignedInt(byteValue)));
        }
        return result.toString();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new ExceptionInInitializerError(impossible);
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
}
