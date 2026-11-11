package com.surprising.aeron.support.ingress;

import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageCodec;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreProtocol;
import com.surprising.aeron.protocol.ProductLineWireCode;
import com.surprising.aeron.protocol.ProtocolException;
import com.surprising.aeron.protocol.WireMessageKind;
import java.nio.ByteOrder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import org.agrona.DirectBuffer;

/** 交易步骤 1：从 Aeron DirectBuffer 校验并解码 Core 帧。仍有解码或响应租约的槽不能重用，缓存满时创建独立消息而非覆盖在途输入。 */
public final class CoreMessageFlyweightDecoder {
    private static final int DEFAULT_CACHE_SLOTS = 512;
    private static final int MAX_CACHED_FRAME_BYTES = 16_384;
    private final int cacheSlots;
    private final int cacheSlotsMask;
    /** 各消息自身记录跨边界租约。 */
    private final CoreMessage[] commandSlots;
    private final int[] lengthHeads = new int[MAX_CACHED_FRAME_BYTES + 1];
    private final int[] next;
    private final int[] previous;
    private final int[] lengths;
    private int replacementCursor;
    private final DirectMessageReader messageReader = new DirectMessageReader();

    /** 使用默认五百一十二个缓存槽建立串行解码器。 */
    public CoreMessageFlyweightDecoder() {
        this(DEFAULT_CACHE_SLOTS);
    }

    /** 要求缓存槽数至少为二且为二次幂，创建长度桶及槽链表的元数据，再清空索引。 */
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

    /** 清空当前缓存槽引用和长度桶索引，重置替换游标。不逐条释放旧槽的外部租约，也不清复用读取器，调用方应在既有生命周期边界使用。 */
    public void reset() {
        java.util.Arrays.fill(commandSlots, null);
        java.util.Arrays.fill(lengthHeads, -1);
        java.util.Arrays.fill(lengths, -1);
        replacementCursor = 0;
    }

    /** 把本实例唯一读取器重新绑定到当前帧并校验头部。下次读取会覆盖这个读取器，不能跨帧保存它当作独立消息。 */
    public DirectMessageReader reader(DirectBuffer buffer, int offset, int length) {
        return messageReader.wrap(buffer, offset, length);
    }

    /** 先校验帧并选择载荷容量桶，缓存内只借可用槽。借槽后的解码失败立即归还解码租约，成功则由后续实际持有者归还。 */
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

    /** 先在目标长度桶查可取得租约的槽。 */
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

    /** 校验帧后复制有效载荷，创建独立头部和消息，不使用缓存槽租约。 */
    public CoreMessage decode(DirectBuffer buffer, int offset, int length) {
        return decodeInto(buffer, reader(buffer, offset, length), null);
    }

    private CoreMessage decodeInto(DirectBuffer buffer, DirectMessageReader reader, CoreMessage slot) {
        long commandIdMsb = reader.commandIdMsb();
        long commandIdLsb = reader.commandIdLsb();
        int payloadLen = reader.payloadLength();
        byte[] payload = slot != null ? slot.decoderPayloadBuffer(payloadLen, payloadCapacity(reader.messageType(), payloadLen))
                : payloadLen == 0 ? CoreMessage.EMPTY_PAYLOAD : new byte[payloadLen];
        if (payloadLen > 0) {
            buffer.getBytes(reader.payloadOffset(), payload, 0, payloadLen);
        }
        if (slot == null) return CoreMessage.owned(new CoreMessageHeader(reader.schemaVersion(), reader.messageType(),
                commandIdMsb, commandIdLsb, reader.productLine(), reader.source(), reader.sourceId(), reader.sourceSequence(),
                reader.userId(), reader.submittedAtEpochMillis(), reader.correlationId()), payload);
        slot.decoderHeader(reader.schemaVersion(), reader.messageType(), commandIdMsb, commandIdLsb, reader.productLine(),
                reader.source(), reader.sourceId(), reader.sourceSequence(), reader.userId(), reader.submittedAtEpochMillis(),
                reader.correlationId());
        return slot;
    }

    /** 同一个解码器反复使用的头部读取视图。字段随 wrap 更新，不能当作跨帧不可变值。构造约定：建立尚未绑定帧的读取器，必须成功 wrap 后再读取其字段。 */
    @NoArgsConstructor
    public static final class DirectMessageReader {
        @Accessors(fluent = true, chain = false)
        @Getter
        private int headerLength;
        @Accessors(fluent = true, chain = false)
        @Getter
        private int payloadLength;
        @Accessors(fluent = true, chain = false)
        @Getter
        private int payloadOffset;

        /** 帧头协议版本，必须等于当前支持版本。 */
        @Accessors(fluent = true, chain = false)
        @Getter
        private int schemaVersion;
        @Accessors(fluent = true, chain = false)
        @Getter
        private com.surprising.product.api.ProductLine productLine;
        @Accessors(fluent = true, chain = false)
        @Getter
        private CoreMessageType messageType;
        @Accessors(fluent = true, chain = false)
        @Getter
        private CommandSource source;
        @Accessors(fluent = true, chain = false)
        @Getter
        private long commandIdMsb;
        @Accessors(fluent = true, chain = false)
        @Getter
        private long commandIdLsb;
        @Accessors(fluent = true, chain = false)
        @Getter
        private long sourceId;
        /** 来源内部递增序号，供原幂等和顺序检查使用。 */
        @Accessors(fluent = true, chain = false)
        @Getter
        private long sourceSequence;
        @Accessors(fluent = true, chain = false)
        @Getter
        private long userId;
        @Accessors(fluent = true, chain = false)
        @Getter
        private long submittedAtEpochMillis;
        @Accessors(fluent = true, chain = false)
        @Getter
        private long correlationId;

        public WireMessageKind kind() { return messageType.kind(); }

        /** 先检查缓冲范围、魔数、版本和头长度，再按小端读取头字段及载荷长度。 */
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
            this.productLine = ProductLineWireCode.decode(buffer.getByte(cursor));
            cursor += Byte.BYTES;
            this.messageType = CoreMessageType.fromWireCode(
                    Short.toUnsignedInt(buffer.getShort(cursor, ByteOrder.LITTLE_ENDIAN)));
            cursor += Short.BYTES;
            this.source = CommandSource.fromWireCode(buffer.getByte(cursor));
            cursor += Byte.BYTES;
            this.headerLength = Short.toUnsignedInt(buffer.getShort(cursor, ByteOrder.LITTLE_ENDIAN));
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

            this.payloadOffset = offset + headerLength;
            return this;
        }

    }
}
