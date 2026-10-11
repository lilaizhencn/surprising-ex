package com.surprising.aeron.service.orchestration.ingress;

import com.surprising.aeron.protocol.*;
import com.surprising.product.api.ProductLine;
import org.agrona.DirectBuffer;

import java.nio.ByteOrder;

/**
 * 真正的零拷贝、零堆分配 DirectBuffer 消息读取器 (Flyweight 视图)。
 * 原位基于 DirectBuffer 的偏移量解析 primitive 字段，不创建任何堆对象。
 */
public final class DirectMessageReader {

    private DirectBuffer buffer;
    private int offset;
    private int length;
    private int headerLength;
    private int payloadLength;
    private int payloadOffset;

    private int schemaVersion;
    private WireMessageKind kind;
    private ProductLine productLine;
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
    private int traceOffset;
    private int traceLength;

    public DirectMessageReader() {
    }

    public DirectMessageReader wrap(DirectBuffer buffer, int offset, int length) {
        if (buffer == null || offset < 0 || length < CoreProtocol.HEADER_LENGTH
                || offset > buffer.capacity() - length) {
            throw new ProtocolException("message shorter than fixed header");
        }
        int cursor = offset;
        int end = offset + length;
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

        if (headerLength != CoreProtocol.HEADER_LENGTH && (headerLength < CoreProtocol.HEADER_LENGTH + 3
                || headerLength > CoreProtocol.HEADER_LENGTH + Short.BYTES + TraceIds.MAX_LENGTH)) {
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
        cursor += Integer.BYTES;

        if (payloadLength < 0 || payloadLength > CoreMessageCodec.MAX_PAYLOAD_LENGTH
                || headerLength + payloadLength != length) {
            throw new ProtocolException("invalid payload length: " + payloadLength);
        }

        this.buffer = buffer;
        this.offset = offset;
        this.length = length;
        this.payloadOffset = offset + headerLength;

        if (headerLength > CoreProtocol.HEADER_LENGTH) {
            int traceLen = Short.toUnsignedInt(buffer.getShort(cursor, ByteOrder.LITTLE_ENDIAN));
            cursor += Short.BYTES;
            if (traceLen < 1 || traceLen > TraceIds.MAX_LENGTH
                    || CoreProtocol.HEADER_LENGTH + Short.BYTES + traceLen != headerLength) {
                throw new ProtocolException("invalid trace id header length");
            }
            for (int i = 0; i < traceLen; i++) {
                byte b = buffer.getByte(cursor + i);
                if (!(b >= 'a' && b <= 'z' || b >= 'A' && b <= 'Z' || b >= '0' && b <= '9'
                        || b == '.' || b == '_' || b == ':' || b == '-')) {
                    throw new ProtocolException("invalid trace id");
                }
            }
            this.traceOffset = cursor;
            this.traceLength = traceLen;
        } else {
            this.traceOffset = 0;
            this.traceLength = 0;
        }
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
    public ProductLine productLine() { return productLine; }
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
    public int traceLength() { return traceLength; }

    public String traceId() {
        if (traceLength == 0) return "";
        char[] chars = new char[traceLength];
        for (int i = 0; i < traceLength; i++) {
            chars[i] = (char) buffer.getByte(traceOffset + i);
        }
        return new String(chars);
    }
}
