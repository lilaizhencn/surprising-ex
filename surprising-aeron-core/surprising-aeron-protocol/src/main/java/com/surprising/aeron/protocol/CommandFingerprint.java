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
        private final FastHasher hasher = new FastHasher();
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
            offset = putInt(this.header, offset, msgHeader.route().shardCode());
            offset = putInt(this.header, offset, msgHeader.route().version());
            offset = putInt(this.header, offset, msgHeader.source().wireCode());
            offset = putLong(this.header, offset, msgHeader.userId());
            offset = putInt(this.header, offset, payloadLength);
            if (offset != HEADER_LENGTH) throw new IllegalStateException("invalid fingerprint header length");

            hasher.reset(HEADER_LENGTH + payloadLength);
            hasher.update(this.header, 0, HEADER_LENGTH);
            hasher.update(payload, 0, payloadLength);
            hasher.finish(target, targetOffset);
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

    private static final class FastHasher {
        private long h0, h1, h2, h3;
        private int lane;

        void reset(long totalLen) {
            h0 = 0x9e3779b97f4a7c15L ^ totalLen;
            h1 = 0xbf58476d1ce4e5b9L ^ (totalLen << 16);
            h2 = 0x94d049bb133111ebL ^ (totalLen << 32);
            h3 = 0x9b64c2b7e19d365fL ^ (totalLen << 48);
            lane = 0;
        }

        void update(byte[] data, int offset, int length) {
            if (data == null || length <= 0) return;
            int end = offset + length;
            int i = offset;
            while (i + 7 < end) {
                long k = getLong(data, i);
                mixLane(k);
                i += 8;
            }
            if (i < end) {
                long k = 0;
                int shift = 0;
                while (i < end) {
                    k |= ((long) (data[i] & 0xFF)) << shift;
                    shift += 8;
                    i++;
                }
                mixLane(k);
            }
        }

        private void mixLane(long k) {
            switch (lane) {
                case 0 -> {
                    h0 = Long.rotateLeft(h0 ^ (k * 0xff51afd7ed558ccdL), 31) * 0xc4ceb9fe1a85ec53L;
                    lane = 1;
                }
                case 1 -> {
                    h1 = Long.rotateLeft(h1 ^ (k * 0xc4ceb9fe1a85ec53L), 33) * 0xff51afd7ed558ccdL;
                    lane = 2;
                }
                case 2 -> {
                    h2 = Long.rotateLeft(h2 ^ (k * 0x9e3779b97f4a7c15L), 29) * 0xbf58476d1ce4e5b9L;
                    lane = 3;
                }
                default -> {
                    h3 = Long.rotateLeft(h3 ^ (k * 0xbf58476d1ce4e5b9L), 37) * 0x94d049bb133111ebL;
                    lane = 0;
                }
            }
        }

        void finish(byte[] target, int targetOffset) {
            long f0 = mix64(h0 ^ h2);
            long f1 = mix64(h1 ^ h3);
            long f2 = mix64(h2 ^ f0);
            long f3 = mix64(h3 ^ f1);

            putLong(target, targetOffset, f0);
            putLong(target, targetOffset + 8, f1);
            putLong(target, targetOffset + 16, f2);
            putLong(target, targetOffset + 24, f3);
        }

        private static long mix64(long k) {
            k ^= k >>> 33;
            k *= 0xff51afd7ed558ccdL;
            k ^= k >>> 33;
            k *= 0xc4ceb9fe1a85ec53L;
            k ^= k >>> 33;
            return k;
        }

        private static long getLong(byte[] b, int off) {
            return ((long) b[off] & 0xFF)
                    | (((long) b[off + 1] & 0xFF) << 8)
                    | (((long) b[off + 2] & 0xFF) << 16)
                    | (((long) b[off + 3] & 0xFF) << 24)
                    | (((long) b[off + 4] & 0xFF) << 32)
                    | (((long) b[off + 5] & 0xFF) << 40)
                    | (((long) b[off + 6] & 0xFF) << 48)
                    | (((long) b[off + 7] & 0xFF) << 56);
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
