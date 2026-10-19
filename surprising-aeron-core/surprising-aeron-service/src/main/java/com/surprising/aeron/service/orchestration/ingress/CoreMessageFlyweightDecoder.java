package com.surprising.aeron.service.orchestration.ingress;

import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageCodec;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreProtocol;
import com.surprising.aeron.protocol.CoreRoute;
import com.surprising.aeron.protocol.ProductLineWireCode;
import com.surprising.aeron.protocol.ProtocolException;
import com.surprising.aeron.protocol.WireMessageKind;
import java.nio.ByteOrder;
import java.util.UUID;
import org.agrona.DirectBuffer;

public final class CoreMessageFlyweightDecoder {
    public static final int DEFAULT_CACHE_SLOTS = 512;
    private static final int MAX_CACHED_FRAME_BYTES = 16_384;
    private final int cacheSlots;
    private final int cacheSlotsMask;
    /** Owned by one serial ingress callback or benchmark producer. */
    private final CoreMessage[] commandSlots;
    /** Capacity buckets for hot orders; exact lengths for administrative commands. Ingress owns metadata. */
    private final int[] lengthHeads = new int[MAX_CACHED_FRAME_BYTES + 1];
    private final int[] next;
    private final int[] previous;
    private final int[] lengths;
    private int replacementCursor;
    private final DirectMessageReader messageReader = new DirectMessageReader();

    public CoreMessageFlyweightDecoder() {
        this(DEFAULT_CACHE_SLOTS);
    }

    public CoreMessageFlyweightDecoder(int cacheSlots) {
        if (cacheSlots < 2 || (cacheSlots & (cacheSlots - 1)) != 0) {
            throw new IllegalArgumentException("cacheSlots must be a power of two: " + cacheSlots);
        }
        this.cacheSlots = cacheSlots;
        this.cacheSlotsMask = cacheSlots - 1;
        this.commandSlots = new CoreMessage[cacheSlots];
        this.next = new int[cacheSlots];
        this.previous = new int[cacheSlots];
        this.lengths = new int[cacheSlots];
        reset();
    }

    public void reset() {
        java.util.Arrays.fill(commandSlots, null);
        java.util.Arrays.fill(lengthHeads, -1);
        java.util.Arrays.fill(lengths, -1);
        replacementCursor = 0;
    }

    public DirectMessageReader reader(DirectBuffer buffer, int offset, int length) {
        return messageReader.wrap(buffer, offset, length);
    }

    /** Bounded reuse cache. Busy slots remain untouched, even when ingress exceeds the command window. */
    public CoreMessage decodeBorrowed(DirectBuffer buffer, int offset, int length) {
        DirectMessageReader reader = reader(buffer, offset, length);
        int capacity = payloadCapacity(reader.messageType(), reader.payloadLength());
        CoreMessage slot = length > MAX_CACHED_FRAME_BYTES ? null : acquire(capacity);
        if (slot == null) return decodeInto(buffer, reader, null);
        try { return decodeInto(buffer, reader, slot); }
        catch (RuntimeException invalid) { slot.releaseDecoderSlot(); throw invalid; }
    }

    private static int payloadCapacity(CoreMessageType type, int length) {
        return switch (type) {
            case PLACE_ORDER, PLACE_ORDER_BATCH, CANCEL_ORDER, CANCEL_ORDER_BATCH,
                    AMEND_ORDER, AMEND_ORDER_BATCH, REPLACE_ORDER ->
                    length == 0 || length > MAX_CACHED_FRAME_BYTES ? length
                            : Math.max(8, Integer.highestOneBit(length - 1) << 1);
            default -> length;
        };
    }

    private CoreMessage acquire(int payloadLength) {
        int head = lengthHeads[payloadLength];
        if (head >= 0) {
            int index = head;
            do {
                if (commandSlots[index].acquireDecoderSlot()) {
                    lengthHeads[payloadLength] = next[index];
                    return commandSlots[index];
                }
                index = next[index];
            } while (index != head);
        }
        // Replace only unleased slots. Power-of-two order buckets reuse variable text/frame sizes;
        // all payload storage remains bounded by cacheSlots * 16 KiB, independently of incoming sizes.
        for (int checked = 0; checked < cacheSlots; checked++) {
            int index = replacementCursor++ & cacheSlotsMask;
            CoreMessage slot = commandSlots[index];
            if (slot == null) commandSlots[index] = slot = CoreMessage.decoderSlot();
            if (!slot.acquireDecoderSlot()) continue;
            unlink(index);
            lengths[index] = payloadLength;
            int first = lengthHeads[payloadLength];
            if (first < 0) {
                next[index] = previous[index] = index;
            } else {
                int last = previous[first];
                next[last] = index;
                previous[index] = last;
                next[index] = first;
                previous[first] = index;
            }
            lengthHeads[payloadLength] = index;
            return slot;
        }
        return null;
    }

    private void unlink(int index) {
        int oldLength = lengths[index];
        if (oldLength < 0) return;
        if (next[index] == index) lengthHeads[oldLength] = -1;
        else {
            next[previous[index]] = next[index];
            previous[next[index]] = previous[index];
            if (lengthHeads[oldLength] == index) lengthHeads[oldLength] = next[index];
        }
    }

    public CoreMessage decode(DirectBuffer buffer, int offset, int length) {
        return decodeInto(buffer, reader(buffer, offset, length), null);
    }

    private CoreMessage decodeInto(DirectBuffer buffer, DirectMessageReader reader, CoreMessage slot) {
        long commandIdMsb = reader.commandIdMsb();
        long commandIdLsb = reader.commandIdLsb();
        CoreRoute route = CoreRoute.fromWireCodes(reader.shardCode(), reader.routeVersion());
        int payloadLen = reader.payloadLength();
        byte[] payload = slot != null ? slot.decoderPayloadBuffer(payloadLen, payloadCapacity(reader.messageType(), payloadLen))
                : payloadLen == 0 ? CoreMessage.EMPTY_PAYLOAD : new byte[payloadLen];
        if (payloadLen > 0) {
            buffer.getBytes(reader.payloadOffset(), payload, 0, payloadLen);
        }
        if (slot == null) return CoreMessage.owned(new CoreMessageHeader(
                reader.schemaVersion(), reader.kind(), reader.messageType(),
                commandIdMsb, commandIdLsb,
                reader.productLine(), route, reader.source(), reader.sourceId(),
                reader.sourceSequence(), reader.userId(), reader.submittedAtEpochMillis(),
                reader.correlationId()), payload);
        slot.decoderHeader(
                reader.schemaVersion(), reader.kind(), reader.messageType(),
                commandIdMsb, commandIdLsb,
                reader.productLine(), route, reader.source(), reader.sourceId(),
                reader.sourceSequence(), reader.userId(), reader.submittedAtEpochMillis(),
                reader.correlationId());
        return slot;
    }

    public static final class DirectMessageReader {
        private DirectBuffer buffer;
        private int offset;
        private int length;
        private int headerLength;
        private int payloadLength;
        private int payloadOffset;

        private int schemaVersion;
        private WireMessageKind kind;
        private com.surprising.product.api.ProductLine productLine;
        private CoreMessageType messageType;
        private CommandSource source;
        private int shardCode;
        private int routeVersion;
        private long commandIdMsb;
        private long commandIdLsb;
        private long sourceId;
        private long sourceSequence;
        private long userId;
        private long submittedAtEpochMillis;
        private long correlationId;

        public DirectMessageReader() {}

        public DirectMessageReader wrap(DirectBuffer buffer, int offset, int length) {
            if (buffer == null || offset < 0 || length < CoreProtocol.HEADER_LENGTH
                    || offset > buffer.capacity() - length) {
                throw new ProtocolException("message shorter than fixed header");
            }
            int cursor = offset;
            if (buffer.getInt(cursor, ByteOrder.LITTLE_ENDIAN) != CoreProtocol.MAGIC) {
                throw new ProtocolException("invalid protocol magic");
            }
            cursor += Integer.BYTES;
            this.schemaVersion = Short.toUnsignedInt(buffer.getShort(cursor, ByteOrder.LITTLE_ENDIAN));
            if (schemaVersion != CoreProtocol.SCHEMA_VERSION) {
                throw new ProtocolException("unsupported schema version: " + schemaVersion);
            }
            cursor += Short.BYTES;
            this.kind = WireMessageKind.fromWireCode(buffer.getByte(cursor));
            cursor += Byte.BYTES;
            this.productLine = ProductLineWireCode.decode(buffer.getByte(cursor));
            cursor += Byte.BYTES;
            this.messageType = CoreMessageType.fromWireCode(
                    Short.toUnsignedInt(buffer.getShort(cursor, ByteOrder.LITTLE_ENDIAN)));
            cursor += Short.BYTES;
            this.source = CommandSource.fromWireCode(buffer.getByte(cursor));
            cursor += Byte.BYTES;
            this.shardCode = buffer.getByte(cursor);
            cursor += Byte.BYTES;
            this.headerLength = Short.toUnsignedInt(buffer.getShort(cursor, ByteOrder.LITTLE_ENDIAN));
            cursor += Short.BYTES;
            this.routeVersion = Short.toUnsignedInt(buffer.getShort(cursor, ByteOrder.LITTLE_ENDIAN));
            cursor += Short.BYTES;

            if (headerLength != CoreProtocol.HEADER_LENGTH) {
                throw new ProtocolException("invalid header length: " + headerLength);
            }

            this.commandIdMsb = buffer.getLong(cursor, ByteOrder.LITTLE_ENDIAN);
            cursor += Long.BYTES;
            this.commandIdLsb = buffer.getLong(cursor, ByteOrder.LITTLE_ENDIAN);
            cursor += Long.BYTES;
            this.sourceId = buffer.getLong(cursor, ByteOrder.LITTLE_ENDIAN);
            cursor += Long.BYTES;
            this.sourceSequence = buffer.getLong(cursor, ByteOrder.LITTLE_ENDIAN);
            cursor += Long.BYTES;
            this.userId = buffer.getLong(cursor, ByteOrder.LITTLE_ENDIAN);
            cursor += Long.BYTES;
            this.submittedAtEpochMillis = buffer.getLong(cursor, ByteOrder.LITTLE_ENDIAN);
            cursor += Long.BYTES;
            this.correlationId = buffer.getLong(cursor, ByteOrder.LITTLE_ENDIAN);
            cursor += Long.BYTES;
            this.payloadLength = buffer.getInt(cursor, ByteOrder.LITTLE_ENDIAN);

            if (payloadLength < 0 || payloadLength > CoreMessageCodec.MAX_PAYLOAD_LENGTH
                    || headerLength + payloadLength != length) {
                throw new ProtocolException("invalid payload length: " + payloadLength);
            }

            this.buffer = buffer;
            this.offset = offset;
            this.length = length;
            this.payloadOffset = offset + headerLength;
            return this;
        }

        public DirectBuffer buffer() { return buffer; }
        public int offset() { return offset; }
        public int length() { return length; }
        public int headerLength() { return headerLength; }
        public int payloadOffset() { return payloadOffset; }
        public int payloadLength() { return payloadLength; }
        public int schemaVersion() { return schemaVersion; }
        public WireMessageKind kind() { return kind; }
        public com.surprising.product.api.ProductLine productLine() { return productLine; }
        public CoreMessageType messageType() { return messageType; }
        public CommandSource source() { return source; }
        public int shardCode() { return shardCode; }
        public int routeVersion() { return routeVersion; }
        public long commandIdMsb() { return commandIdMsb; }
        public long commandIdLsb() { return commandIdLsb; }
        public long sourceId() { return sourceId; }
        public long sourceSequence() { return sourceSequence; }
        public long userId() { return userId; }
        public long submittedAtEpochMillis() { return submittedAtEpochMillis; }
        public long correlationId() { return correlationId; }
    }
}
