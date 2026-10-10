package com.surprising.aeron.service.orchestration.ingress;

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

public final class CoreMessageFlyweightDecoder {

    private CoreMessageFlyweightDecoder() {
    }

    public static CoreMessage decode(DirectBuffer buffer, int offset, int length) {
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
        if (headerLength != CoreProtocol.HEADER_LENGTH && (headerLength < CoreProtocol.HEADER_LENGTH + 3
                || headerLength > CoreProtocol.HEADER_LENGTH + Short.BYTES + com.surprising.aeron.protocol.TraceIds.MAX_LENGTH)) {
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
        String traceId = "";
        if (headerLength > CoreProtocol.HEADER_LENGTH) {
            cursor += Integer.BYTES;
            int traceLength = Short.toUnsignedInt(getShort(buffer, cursor, end));
            cursor += Short.BYTES;
            if (traceLength < 1 || traceLength > com.surprising.aeron.protocol.TraceIds.MAX_LENGTH
                    || CoreProtocol.HEADER_LENGTH + Short.BYTES + traceLength != headerLength)
                throw new ProtocolException("invalid trace id header length");
            check(cursor, traceLength, end);
            char[] chars = new char[traceLength];
            for (int i = 0; i < traceLength; i++) {
                byte b = buffer.getByte(cursor + i);
                if (!(b >= 'a' && b <= 'z' || b >= 'A' && b <= 'Z' || b >= '0' && b <= '9'
                        || b == '.' || b == '_' || b == ':' || b == '-')) {
                    throw new ProtocolException("invalid trace id");
                }
                chars[i] = (char) b;
            }
            traceId = new String(chars);
        }
        byte[] payload = payloadLength == 0 ? CoreMessage.EMPTY_PAYLOAD : new byte[payloadLength];
        if (payloadLength > 0) {
            buffer.getBytes(offset + headerLength, payload);
        }
        CoreMessageHeader header = new CoreMessageHeader(schemaVersion, kind, messageType, commandId,
                productLine, route, source, sourceId, sourceSequence, userId,
                submittedAtEpochMillis, correlationId, traceId);
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
