package com.surprising.aeron.service;

import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMessage;
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

final class CoreMessageFlyweightDecoder {

    private CoreMessageFlyweightDecoder() {
    }

    static CoreMessage decode(DirectBuffer buffer, int offset, int length) {
        if (buffer == null || offset < 0 || length < CoreProtocol.HEADER_LENGTH
                || offset > buffer.capacity() - length) {
            throw new ProtocolException("message shorter than fixed header");
        }
        int cursor = offset;
        int end = offset + length;
        if (getInt(buffer, cursor, end) != CoreProtocol.MAGIC) {
            throw new ProtocolException("invalid protocol magic");
        }
        int schemaVersion = Short.toUnsignedInt(getShort(buffer, cursor += Integer.BYTES, end));
        if (schemaVersion != CoreProtocol.SCHEMA_VERSION) {
            throw new ProtocolException("unsupported schema version: " + schemaVersion);
        }
        WireMessageKind kind = WireMessageKind.fromWireCode(getByte(buffer, cursor += Short.BYTES, end));
        var productLine = ProductLineWireCode.decode(getByte(buffer, cursor += Byte.BYTES, end));
        CoreMessageType messageType = CoreMessageType.fromWireCode(
                Short.toUnsignedInt(getShort(buffer, cursor += Byte.BYTES, end)));
        CommandSource source = CommandSource.fromWireCode(getByte(buffer, cursor += Short.BYTES, end));
        int shardCode = getByte(buffer, cursor += Byte.BYTES, end);
        int headerLength = Short.toUnsignedInt(getShort(buffer, cursor += Byte.BYTES, end));
        int routeVersion = Short.toUnsignedInt(getShort(buffer, cursor += Short.BYTES, end));
        if (headerLength != CoreProtocol.HEADER_LENGTH) {
            throw new ProtocolException("invalid header length: " + headerLength);
        }
        CoreRoute route = CoreRoute.fromWireCodes(shardCode, routeVersion);
        UUID commandId = new UUID(getLong(buffer, cursor += Short.BYTES, end),
                getLong(buffer, cursor += Long.BYTES, end));
        long sourceId = getLong(buffer, cursor += Long.BYTES, end);
        long sourceSequence = getLong(buffer, cursor += Long.BYTES, end);
        long userId = getLong(buffer, cursor += Long.BYTES, end);
        long submittedAtEpochMillis = getLong(buffer, cursor += Long.BYTES, end);
        long correlationId = getLong(buffer, cursor += Long.BYTES, end);
        int payloadLength = getInt(buffer, cursor += Long.BYTES, end);
        if (payloadLength < 0 || payloadLength > com.surprising.aeron.protocol.CoreMessageCodec.MAX_PAYLOAD_LENGTH
                || headerLength + payloadLength != length) {
            throw new ProtocolException("invalid payload length: " + payloadLength);
        }
        byte[] payload = new byte[payloadLength];
        buffer.getBytes(offset + headerLength, payload);
        CoreMessageHeader header = new CoreMessageHeader(schemaVersion, kind, messageType, commandId,
                productLine, route, source, sourceId, sourceSequence, userId,
                submittedAtEpochMillis, correlationId);
        return CoreMessage.owned(header, payload);
    }

    private static byte getByte(DirectBuffer buffer, int index, int end) {
        check(index, Byte.BYTES, end);
        return buffer.getByte(index);
    }

    private static short getShort(DirectBuffer buffer, int index, int end) {
        check(index, Short.BYTES, end);
        return buffer.getShort(index, ByteOrder.LITTLE_ENDIAN);
    }

    private static int getInt(DirectBuffer buffer, int index, int end) {
        check(index, Integer.BYTES, end);
        return buffer.getInt(index, ByteOrder.LITTLE_ENDIAN);
    }

    private static long getLong(DirectBuffer buffer, int index, int end) {
        check(index, Long.BYTES, end);
        return buffer.getLong(index, ByteOrder.LITTLE_ENDIAN);
    }

    private static void check(int index, int size, int end) {
        if (index < 0 || index > end - size) {
            throw new ProtocolException("message header is truncated");
        }
    }
}
