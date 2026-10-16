package com.surprising.aeron.protocol;

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
        private final byte[] header = new byte[HEADER_LENGTH];
        private byte[] scratch = new byte[512];

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

            int offset = 0;
            offset = putInt(this.header, offset, CANONICAL_VERSION);
            offset = putInt(this.header, offset, msgHeader.schemaVersion());
            offset = putInt(this.header, offset, msgHeader.messageType().wireCode());
            offset = putInt(this.header, offset, ProductLineWireCode.encode(msgHeader.productLine()));
            offset = putInt(this.header, offset, msgHeader.route().shardCode());
            offset = putInt(this.header, offset, msgHeader.route().version());
            offset = putInt(this.header, offset, msgHeader.source().wireCode());
            offset = putLong(this.header, offset, msgHeader.userId());
            offset = putInt(this.header, offset, payload.length);
            if (offset != HEADER_LENGTH) throw new IllegalStateException("invalid fingerprint header length");

            int totalLen = HEADER_LENGTH + payload.length;
            if (scratch.length < totalLen) {
                int nextCap = Math.max(scratch.length * 2, totalLen);
                scratch = new byte[nextCap];
            }
            System.arraycopy(this.header, 0, scratch, 0, HEADER_LENGTH);
            System.arraycopy(payload, 0, scratch, HEADER_LENGTH, payload.length);

            // Compute 256-bit hash using MurmurHash3_x64_128 with two independent seeds
            murmurHash3_x64_128(scratch, 0, totalLen, 0x9747b28cL, target, targetOffset);
            murmurHash3_x64_128(scratch, 0, totalLen, 0x1b873593L, target, targetOffset + 16);
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

    private static void murmurHash3_x64_128(byte[] data, int offset, int length, long seed, byte[] out, int outOffset) {
        long h1 = seed;
        long h2 = seed;

        final long c1 = 0x87c37b91114253d5L;
        final long c2 = 0x4cf5ad432745937fL;

        int nblocks = length / 16;
        for (int i = 0; i < nblocks; i++) {
            int blockOffset = offset + (i * 16);
            long k1 = getLongLE(data, blockOffset);
            long k2 = getLongLE(data, blockOffset + 8);

            k1 *= c1;
            k1 = Long.rotateLeft(k1, 31);
            k1 *= c2;
            h1 ^= k1;

            h1 = Long.rotateLeft(h1, 27);
            h1 += h2;
            h1 = h1 * 5 + 0x52dce729;

            k2 *= c2;
            k2 = Long.rotateLeft(k2, 33);
            k2 *= c1;
            h2 ^= k2;

            h2 = Long.rotateLeft(h2, 31);
            h2 += h1;
            h2 = h2 * 5 + 0x38495ab5;
        }

        long k1 = 0;
        long k2 = 0;
        int tailOffset = offset + (nblocks * 16);
        int remaining = length & 15;

        switch (remaining) {
            case 15: k2 ^= (long) (data[tailOffset + 14] & 0xff) << 48;
            case 14: k2 ^= (long) (data[tailOffset + 13] & 0xff) << 40;
            case 13: k2 ^= (long) (data[tailOffset + 12] & 0xff) << 32;
            case 12: k2 ^= (long) (data[tailOffset + 11] & 0xff) << 24;
            case 11: k2 ^= (long) (data[tailOffset + 10] & 0xff) << 16;
            case 10: k2 ^= (long) (data[tailOffset + 9] & 0xff) << 8;
            case 9:  k2 ^= (long) (data[tailOffset + 8] & 0xff);
                     k2 *= c2; k2 = Long.rotateLeft(k2, 33); k2 *= c1; h2 ^= k2;
            case 8:  k1 ^= (long) (data[tailOffset + 7] & 0xff) << 56;
            case 7:  k1 ^= (long) (data[tailOffset + 6] & 0xff) << 48;
            case 6:  k1 ^= (long) (data[tailOffset + 5] & 0xff) << 40;
            case 5:  k1 ^= (long) (data[tailOffset + 4] & 0xff) << 32;
            case 4:  k1 ^= (long) (data[tailOffset + 3] & 0xff) << 24;
            case 3:  k1 ^= (long) (data[tailOffset + 2] & 0xff) << 16;
            case 2:  k1 ^= (long) (data[tailOffset + 1] & 0xff) << 8;
            case 1:  k1 ^= (long) (data[tailOffset] & 0xff);
                     k1 *= c1; k1 = Long.rotateLeft(k1, 31); k1 *= c2; h1 ^= k1;
        }

        h1 ^= length;
        h2 ^= length;

        h1 += h2;
        h2 += h1;

        h1 = fmix64(h1);
        h2 = fmix64(h2);

        h1 += h2;
        h2 += h1;

        putLongLE(out, outOffset, h1);
        putLongLE(out, outOffset + 8, h2);
    }

    private static long fmix64(long k) {
        k ^= k >>> 33;
        k *= 0xff51afd7ed558ccdL;
        k ^= k >>> 33;
        k *= 0xc4ceb9fe1a85ec53L;
        k ^= k >>> 33;
        return k;
    }

    private static long getLongLE(byte[] data, int offset) {
        return ((long) data[offset] & 0xff)
                | (((long) data[offset + 1] & 0xff) << 8)
                | (((long) data[offset + 2] & 0xff) << 16)
                | (((long) data[offset + 3] & 0xff) << 24)
                | (((long) data[offset + 4] & 0xff) << 32)
                | (((long) data[offset + 5] & 0xff) << 40)
                | (((long) data[offset + 6] & 0xff) << 48)
                | (((long) data[offset + 7] & 0xff) << 56);
    }

    private static void putLongLE(byte[] target, int offset, long value) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >>> 8);
        target[offset + 2] = (byte) (value >>> 16);
        target[offset + 3] = (byte) (value >>> 24);
        target[offset + 4] = (byte) (value >>> 32);
        target[offset + 5] = (byte) (value >>> 40);
        target[offset + 6] = (byte) (value >>> 48);
        target[offset + 7] = (byte) (value >>> 56);
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
