package com.surprising.aeron.support.ingress;

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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import org.agrona.DirectBuffer;

/**
 * 交易步骤 1：从 Aeron DirectBuffer 校验并解码 Core 帧。一个串行输入方独占本解码器及复用读取器；可借出固定缓存槽，也可创建独立消息。仍有解码或响应租约的槽不能重用，缓存满时创建独立消息而非覆盖在途输入。
 */
public final class CoreMessageFlyweightDecoder {
    /** 默认最多缓存五百一十二个复用消息槽。 */
    public static final int DEFAULT_CACHE_SLOTS = 512;
    /** 允许进入复用缓存的帧长度上限十六 KiB，也是长度桶数组上界。 */
    private static final int MAX_CACHED_FRAME_BYTES = 16_384;
    /** 固定缓存槽数量，构造时校验二次幂。 */
    private final int cacheSlots;
    /** 环形替换游标的二次幂掩码。 */
    private final int cacheSlotsMask;
    /** 串行输入方持有的缓存消息数组；各消息自身记录跨边界租约。 */
    private final CoreMessage[] commandSlots;
    /** 按载荷容量或精确长度索引的桶头，负一表示空桶。 */
    private final int[] lengthHeads = new int[MAX_CACHED_FRAME_BYTES + 1];
    /** 每个槽在所属长度桶中的下一槽下标。 */
    private final int[] next;
    /** 每个槽在所属长度桶中的上一槽下标。 */
    private final int[] previous;
    /** 每个槽当前所属的载荷长度桶，负一表示尚未挂桶。 */
    private final int[] lengths;
    /** 寻找可替换空闲槽的循环游标。 */
    private int replacementCursor;
    /** 本实例唯一复用头读取器，由当前串行输入方使用。 */
    private final DirectMessageReader messageReader = new DirectMessageReader();

    /**
     * 使用默认五百一十二个缓存槽建立串行解码器。
     */
    public CoreMessageFlyweightDecoder() {
        this(DEFAULT_CACHE_SLOTS);
    }

    /**
     * 要求缓存槽数至少为二且为二次幂，创建长度桶及槽链表的元数据，再清空索引。
     * @param cacheSlots 至少为二的二次幂缓存槽数量
     */
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

    /**
     * 清空当前缓存槽引用和长度桶索引，重置替换游标；不逐条释放旧槽的外部租约，也不清复用读取器，调用方应在既有生命周期边界使用。
     */
    public void reset() {
        java.util.Arrays.fill(commandSlots, null);
        java.util.Arrays.fill(lengthHeads, -1);
        java.util.Arrays.fill(lengths, -1);
        replacementCursor = 0;
    }

    /**
     * 把本实例唯一读取器重新绑定到当前帧并校验头部；下次读取会覆盖这个读取器，不能跨帧保存它当作独立消息。
     * @param buffer 包含当前完整 Core 帧的原输入缓冲
     * @param offset 帧在缓冲中的起始字节偏移
     * @param length 当前完整帧长度
     */
    public DirectMessageReader reader(DirectBuffer buffer, int offset, int length) {
        return messageReader.wrap(buffer, offset, length);
    }

    /**
     * 先校验帧并选择载荷容量桶，缓存内只借可用槽；超大帧或槽全部占用时创建独立消息。借槽后的解码失败立即归还解码租约，成功则由后续实际持有者归还。
     * @param buffer 包含当前完整 Core 帧的原输入缓冲
     * @param offset 帧在缓冲中的起始字节偏移
     * @param length 当前完整帧长度
     */
    public CoreMessage decodeBorrowed(DirectBuffer buffer, int offset, int length) {
        // 本实例绑定到当前帧的复用头读取器
        DirectMessageReader reader = reader(buffer, offset, length);
        // 本类型有效载荷对应的缓存容量桶
        int capacity = payloadCapacity(reader.messageType(), reader.payloadLength());
        // 本轮尝试取得解码租约的消息槽，空表示创建独立消息
        CoreMessage slot = length > MAX_CACHED_FRAME_BYTES ? null : acquire(capacity);
        if (slot == null) return decodeInto(buffer, reader, null);
        try { return decodeInto(buffer, reader, slot); }
        catch (/* 借槽后解码失败的运行时异常，先归还解码租约再抛出 */ RuntimeException invalid) { slot.releaseDecoderSlot(); throw invalid; }
    }

    /**
     * 普通及批量订单类使用至少八字节的二次幂载荷容量，零长或超缓存上限保留原长；其他管理命令按实际载荷长度分桶。
     * @param type 需要决定容量桶的业务消息类型
     * @param length 当前有效载荷长度，不是含头整帧长度
     */
    private static int payloadCapacity(CoreMessageType type, int length) {
        return switch (type) {
            case PLACE_ORDER, PLACE_ORDER_BATCH, CANCEL_ORDER, CANCEL_ORDER_BATCH,
                    AMEND_ORDER, AMEND_ORDER_BATCH, REPLACE_ORDER ->
                    length == 0 || length > MAX_CACHED_FRAME_BYTES ? length
                            : Math.max(8, Integer.highestOneBit(length - 1) << 1);
            default -> length;
        };
    }

    /**
     * 先在目标长度桶查可取得租约的槽；没有则按替换游标扫描固定槽，借到后改挂目标桶。所有槽都被借用就返回 null，不修改被占用的消息。
     * @param payloadLength 需要查找的载荷容量桶下标
     */
    private CoreMessage acquire(int payloadLength) {
        // 目标载荷长度桶的起始槽，负一表示空桶
        int head = lengthHeads[payloadLength];
        if (head >= 0) {
            // 当前检查或重新挂桶的缓存槽下标
            int index = head;
            do {
                if (commandSlots[index].acquireDecoderSlot()) {
                    lengthHeads[payloadLength] = next[index];
                    return commandSlots[index];
                }
                index = next[index];
            } while (index != head);
        }
        // 只替换未被借用的槽；订单的二次幂容量桶可复用不同有效长度的载荷。
        // 缓存载荷容量受槽数乘十六 KiB 限制，超大输入使用独立消息而不扩大缓存。
        for (/* 本轮已扫描的替换槽数量，上限为缓存槽数 */ int checked = 0; checked < cacheSlots; checked++) {
            // 当前检查或重新挂桶的缓存槽下标
            int index = replacementCursor++ & cacheSlotsMask;
            // 当前替换位置的缓存槽，尚未分配时在这里建立新的解码槽
            CoreMessage slot = commandSlots[index];
            if (slot == null) commandSlots[index] = slot = CoreMessage.decoderSlot();
            if (!slot.acquireDecoderSlot()) continue;
            unlink(index);
            lengths[index] = payloadLength;
            // 新目标长度桶当前首槽
            int first = lengthHeads[payloadLength];
            if (first < 0) {
                next[index] = previous[index] = index;
            } else {
                // 新目标长度桶当前尾槽，用来把新槽接入循环链表
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

    /**
     * 把指定槽从原载荷长度的双向循环链表移除；未入桶槽直接返回，单槽桶清空桶头。
     * @param index 需要脱离原长度桶的缓存槽下标
     */
    private void unlink(int index) {
        // 待移除槽此前所属的载荷长度桶，负一表示未入桶
        int oldLength = lengths[index];
        if (oldLength < 0) return;
        if (next[index] == index) lengthHeads[oldLength] = -1;
        else {
            next[previous[index]] = next[index];
            previous[next[index]] = previous[index];
            if (lengthHeads[oldLength] == index) lengthHeads[oldLength] = next[index];
        }
    }

    /**
     * 校验帧后复制有效载荷，创建独立头部和消息，不使用缓存槽租约。
     * @param buffer 包含当前完整 Core 帧的原输入缓冲
     * @param offset 帧在缓冲中的起始字节偏移
     * @param length 当前完整帧长度
     */
    public CoreMessage decode(DirectBuffer buffer, int offset, int length) {
        return decodeInto(buffer, reader(buffer, offset, length), null);
    }

    /**
     * 构造并校验路由，复制当前有效载荷；有缓存槽就写入该槽及其头，没有则返回独立消息。只复制有效长度，缓存容量尾部不进入载荷或指纹。
     * @param buffer 包含当前完整 Core 帧的原输入缓冲
     * @param reader 已经成功绑定当前帧的本实例读取器
     * @param slot 已取得解码租约的可空缓存槽，空则创建独立消息
     */
    private CoreMessage decodeInto(DirectBuffer buffer, DirectMessageReader reader, CoreMessage slot) {
        // 从当前帧读取的命令 UUID 高位
        long commandIdMsb = reader.commandIdMsb();
        // 从当前帧读取的命令 UUID 低位
        long commandIdLsb = reader.commandIdLsb();
        // 由当前头部分片和版本构造的已校验路由
        CoreRoute route = CoreRoute.fromWireCodes(reader.shardCode(), reader.routeVersion());
        // 当前实际有效载荷长度，不包括缓存容量尾部
        int payloadLen = reader.payloadLength();
        // 要保存当前有效字节的缓存或独立数组
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

    /** 同一个解码器反复使用的头部读取视图；字段随 wrap 更新，不能当作跨帧不可变值。wrap 失败不保证此前或部分字段保持原样。 构造约定：建立尚未绑定帧的读取器，必须成功 wrap 后再读取其字段。 */
    @NoArgsConstructor
    public static final class DirectMessageReader {
        /**  最近一次成功 wrap 的原始帧缓冲，读取器只借用它。 借出最近一次成功绑定的原缓冲，不复制帧字节。 */
        @Accessors(fluent = true, chain = false)
        @Getter
        private DirectBuffer buffer;
        /**  当前帧在原缓冲中的起始字节偏移。 读取当前帧的缓冲起始偏移。 */
        @Accessors(fluent = true, chain = false)
        @Getter
        private int offset;
        /**  当前完整帧的字节长度。 读取当前完整帧长度。 */
        @Accessors(fluent = true, chain = false)
        @Getter
        private int length;
        /**  已校验的固定头长度。 读取已校验的固定头长度。 */
        @Accessors(fluent = true, chain = false)
        @Getter
        private int headerLength;
        /**  已校验的有效载荷长度。 读取有效载荷字节数。 */
        @Accessors(fluent = true, chain = false)
        @Getter
        private int payloadLength;
        /**  载荷在原缓冲中的绝对起始偏移。 读取有效载荷的绝对字节偏移。 */
        @Accessors(fluent = true, chain = false)
        @Getter
        private int payloadOffset;

        /**  帧头协议版本，必须等于当前支持版本。 读取当前协议版本。 */
        @Accessors(fluent = true, chain = false)
        @Getter
        private int schemaVersion;
        /**  帧头消息种类，例如命令或查询。 读取当前消息种类。 */
        @Accessors(fluent = true, chain = false)
        @Getter
        private WireMessageKind kind;
        /**  帧头产品线，经 wire code 解码。 读取已解码产品线。 */
        @Accessors(fluent = true, chain = false)
        @Getter
        private com.surprising.product.api.ProductLine productLine;
        /**  帧头具体业务消息类型。 读取具体业务消息类型。 */
        @Accessors(fluent = true, chain = false)
        @Getter
        private CoreMessageType messageType;
        /**  帧头命令来源种类。 读取命令来源种类。 */
        @Accessors(fluent = true, chain = false)
        @Getter
        private CommandSource source;
        /**  帧头路由分片代码，路由合法性由后续 CoreRoute 构造继续检查。 读取路由分片代码。 */
        @Accessors(fluent = true, chain = false)
        @Getter
        private int shardCode;
        /**  帧头路由版本，后续构造 CoreRoute 使用。 读取路由版本。 */
        @Accessors(fluent = true, chain = false)
        @Getter
        private int routeVersion;
        /**  命令 UUID 的高六十四位。 读取命令 UUID 高位。 */
        @Accessors(fluent = true, chain = false)
        @Getter
        private long commandIdMsb;
        /**  命令 UUID 的低六十四位。 读取命令 UUID 低位。 */
        @Accessors(fluent = true, chain = false)
        @Getter
        private long commandIdLsb;
        /**  命令来源实例 ID。 读取命令来源实例 ID。 */
        @Accessors(fluent = true, chain = false)
        @Getter
        private long sourceId;
        /**  来源内部递增序号，供原幂等和顺序检查使用。 读取来源内部序号。 */
        @Accessors(fluent = true, chain = false)
        @Getter
        private long sourceSequence;
        /**  命令目标用户 ID。 读取目标用户 ID。 */
        @Accessors(fluent = true, chain = false)
        @Getter
        private long userId;
        /**  请求头中的提交毫秒时间，不等于复制日志业务时间。 读取原请求提交毫秒时间。 */
        @Accessors(fluent = true, chain = false)
        @Getter
        private long submittedAtEpochMillis;
        /**  请求与响应关联用的原关联号。 读取请求响应关联号。 */
        @Accessors(fluent = true, chain = false)
        @Getter
        private long correlationId;

        /**
         * 先检查缓冲范围、魔数、版本和头长度，再按小端读取头字段及载荷长度；要求整帧长度精确匹配，成功后保存原缓冲视图。不在此校验业务载荷或执行交易。
         * @param buffer 包含当前完整 Core 帧的原输入缓冲
         * @param offset 帧在缓冲中的起始字节偏移
         * @param length 当前完整帧长度
         */
        public DirectMessageReader wrap(DirectBuffer buffer, int offset, int length) {
            if (buffer == null || offset < 0 || length < CoreProtocol.HEADER_LENGTH
                    || offset > buffer.capacity() - length) {
                throw new ProtocolException("message shorter than fixed header");
            }
            // 按固定头字段顺序向前读取的绝对字节游标
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

    }
}
