package com.surprising.aeron.protocol;

import java.security.MessageDigest;
import java.security.DigestException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;

public final class CommandFingerprint {

    public static final int LENGTH = 32;
    private static final int CANONICAL_VERSION = 1;
    private static final int HEADER_LENGTH = Integer.BYTES * 8 + Long.BYTES;
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
        private final MessageDigest digest = newDigest();
        private final byte[] header = new byte[HEADER_LENGTH];

        public CommandFingerprint of(CoreMessage message) {
            prepareDigest(message);
            return new CommandFingerprint(digest.digest(), true);
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
            prepareDigest(message);
            try {
                if (digest.digest(target.value, 0, LENGTH) != LENGTH)
                    throw new IllegalStateException("invalid SHA-256 output length");
            } catch (DigestException failure) {
                throw new IllegalStateException("SHA-256 cannot write the fingerprint slot", failure);
            }
            target.initialized = true;
            return target;
        }

        private void prepareDigest(CoreMessage message) {
            Objects.requireNonNull(message, "message");
            CoreMessageHeader header = message.header();
            byte[] payload = message.payloadUnsafe();
            digest.reset();
            int offset = 0;
            offset = putInt(this.header, offset, CANONICAL_VERSION);
            offset = putInt(this.header, offset, header.schemaVersion());
            offset = putInt(this.header, offset, header.messageType().wireCode());
            offset = putInt(this.header, offset, ProductLineWireCode.encode(header.productLine()));
            offset = putInt(this.header, offset, header.route().shardCode());
            offset = putInt(this.header, offset, header.route().version());
            offset = putInt(this.header, offset, header.source().wireCode());
            offset = putLong(this.header, offset, header.userId());
            offset = putInt(this.header, offset, payload.length);
            if (offset != HEADER_LENGTH) throw new IllegalStateException("invalid fingerprint header length");
            digest.update(this.header, 0, offset);
            digest.update(payload);
        }
    }

    /**
     * 保留原始指纹；只在重试比对时识别已发布划转 v1/v2 的等价编码。
     * 不重写旧快照里的指纹，不允许把新增的其他收款人归并为本人划转。
     */
    public boolean matchesCommittedCommand(CoreMessage message, CommandFingerprint actual) {
        if (equals(actual)) return true;
        var type = message.header().messageType();
        if (type != CoreMessageType.TRANSFER_OUT && type != CoreMessageType.TRANSFER_IN) return false;
        byte[] payload = message.payloadUnsafe();
        try {
            var transfer = TradingCommandCodec.decodeTransferFunds(payload, message.header().userId());
            if (transfer.sourceUserId() != message.header().userId()
                    || transfer.targetUserId() != message.header().userId()) return false;
            int version = java.nio.ByteBuffer.wrap(payload).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt();
            byte[] alternate;
            if (version == 1) {
                alternate = new byte[payload.length + Long.BYTES * 2];
                java.nio.ByteBuffer.wrap(alternate).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        .putInt(2).putLong(transfer.transferId())
                        .putLong(transfer.sourceUserId()).putLong(transfer.targetUserId());
                System.arraycopy(payload, 12, alternate, 28, payload.length - 12);
            } else {
                alternate = new byte[payload.length - Long.BYTES * 2];
                java.nio.ByteBuffer.wrap(alternate).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        .putInt(1).putLong(transfer.transferId());
                System.arraycopy(payload, 28, alternate, 12, payload.length - 28);
            }
            return equals(of(new CoreMessage(message.header(), alternate)));
        } catch (IllegalArgumentException invalid) {
            return false;
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

}
