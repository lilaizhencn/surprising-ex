package com.surprising.aeron.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.UUID;

public final class CoreMessageCodec {

    public static final int MAX_PAYLOAD_LENGTH = 16 * 1024 * 1024;

    private CoreMessageCodec() {
    }

    public static byte[] encode(CoreMessage message) {
        byte[] encoded = new byte[encodedLength(message)];
        encode(message, encoded);
        return encoded;
    }

    public static int encodedLength(CoreMessage message) {
        Objects.requireNonNull(message, "message");
        int payloadLength = message.payloadLength();
        if (payloadLength > MAX_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException("message payload is too large");
        }
        return CoreProtocol.HEADER_LENGTH + payloadLength;
    }

    public static int encode(CoreMessage message, byte[] destination) {
        int encodedLength = encodedLength(message);
        if (destination == null || destination.length < encodedLength) {
            throw new IllegalArgumentException("destination is smaller than encoded message");
        }
        CoreMessageHeader header = message.header();
        encodeHeader(destination, header, message.payloadLength());
        message.copyPayloadTo(destination, CoreProtocol.HEADER_LENGTH);
        return encodedLength;
    }

    public static int encodedResponseLength(CoreResponse response) {
        return Math.addExact(CoreProtocol.HEADER_LENGTH, CoreProtocol.responsePayloadLength(response));
    }

    public static int encodeResponse(CoreMessageHeader header, CoreResponse response,
                                     long committedCoreSequence, byte[] destination) {
        Objects.requireNonNull(header, "header");
        int payloadLength = CoreProtocol.responsePayloadLength(response);
        int encodedLength = Math.addExact(CoreProtocol.HEADER_LENGTH, payloadLength);
        if (payloadLength > MAX_PAYLOAD_LENGTH || destination == null || destination.length < encodedLength) {
            throw new IllegalArgumentException("destination is smaller than encoded response");
        }
        encodeHeader(destination, header, payloadLength);
        CoreProtocol.encodeResponsePayload(
                response, committedCoreSequence, destination, CoreProtocol.HEADER_LENGTH);
        return encodedLength;
    }

    private static void encodeHeader(byte[] destination, CoreMessageHeader header, int payloadLength) {
        int cursor = 0;
        CoreProtocol.putInt(destination, cursor, CoreProtocol.MAGIC); cursor += Integer.BYTES;
        CoreProtocol.putShort(destination, cursor, header.schemaVersion()); cursor += Short.BYTES;
        destination[cursor++] = (byte) header.kind().wireCode();
        destination[cursor++] = (byte) ProductLineWireCode.encode(header.productLine());
        CoreProtocol.putShort(destination, cursor, header.messageType().wireCode()); cursor += Short.BYTES;
        destination[cursor++] = (byte) header.source().wireCode();
        destination[cursor++] = (byte) header.route().shardCode();
        CoreProtocol.putShort(destination, cursor, CoreProtocol.HEADER_LENGTH); cursor += Short.BYTES;
        CoreProtocol.putShort(destination, cursor, header.route().version()); cursor += Short.BYTES;
        CoreProtocol.putLong(destination, cursor, header.commandId().getMostSignificantBits()); cursor += Long.BYTES;
        CoreProtocol.putLong(destination, cursor, header.commandId().getLeastSignificantBits()); cursor += Long.BYTES;
        CoreProtocol.putLong(destination, cursor, header.sourceId()); cursor += Long.BYTES;
        CoreProtocol.putLong(destination, cursor, header.sourceSequence()); cursor += Long.BYTES;
        CoreProtocol.putLong(destination, cursor, header.userId()); cursor += Long.BYTES;
        CoreProtocol.putLong(destination, cursor, header.submittedAtEpochMillis()); cursor += Long.BYTES;
        CoreProtocol.putLong(destination, cursor, header.correlationId()); cursor += Long.BYTES;
        CoreProtocol.putInt(destination, cursor, payloadLength);
    }

    public static CoreMessage decode(byte[] encoded) {
        if (encoded == null || encoded.length < CoreProtocol.HEADER_LENGTH) {
            throw new ProtocolException("message shorter than fixed header");
        }
        ByteBuffer buffer = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        int magic = buffer.getInt();
        if (magic != CoreProtocol.MAGIC) {
            throw new ProtocolException("invalid protocol magic");
        }
        int schemaVersion = Short.toUnsignedInt(buffer.getShort());
        if (schemaVersion != CoreProtocol.SCHEMA_VERSION) {
            throw new ProtocolException("unsupported schema version: " + schemaVersion);
        }
        WireMessageKind kind = WireMessageKind.fromWireCode(Byte.toUnsignedInt(buffer.get()));
        var productLine = ProductLineWireCode.decode(Byte.toUnsignedInt(buffer.get()));
        CoreMessageType messageType = CoreMessageType.fromWireCode(Short.toUnsignedInt(buffer.getShort()));
        CommandSource source = CommandSource.fromWireCode(Byte.toUnsignedInt(buffer.get()));
        int shardCode = Byte.toUnsignedInt(buffer.get());
        int headerLength = Short.toUnsignedInt(buffer.getShort());
        int routeVersion = Short.toUnsignedInt(buffer.getShort());
        if (headerLength != CoreProtocol.HEADER_LENGTH) {
            throw new ProtocolException("invalid header length: " + headerLength);
        }
        CoreRoute route = CoreRoute.fromWireCodes(shardCode, routeVersion);
        UUID commandId = new UUID(buffer.getLong(), buffer.getLong());
        long sourceId = buffer.getLong();
        long sourceSequence = buffer.getLong();
        long userId = buffer.getLong();
        long submittedAtEpochMillis = buffer.getLong();
        long correlationId = buffer.getLong();
        int payloadLength = buffer.getInt();
        if (payloadLength < 0 || payloadLength > MAX_PAYLOAD_LENGTH
                || headerLength + payloadLength != encoded.length) {
            throw new ProtocolException("invalid payload length: " + payloadLength);
        }
        buffer.position(headerLength);
        byte[] payload = new byte[payloadLength];
        buffer.get(payload);
        CoreMessageHeader header = new CoreMessageHeader(schemaVersion, kind, messageType, commandId,
                productLine, route, source, sourceId, sourceSequence, userId,
                submittedAtEpochMillis, correlationId);
        return CoreMessage.owned(header, payload);
    }
}
