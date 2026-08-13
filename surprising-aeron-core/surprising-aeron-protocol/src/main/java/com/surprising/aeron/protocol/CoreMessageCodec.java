package com.surprising.aeron.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

public final class CoreMessageCodec {

    public static final int MAX_PAYLOAD_LENGTH = 16 * 1024 * 1024;

    private CoreMessageCodec() {
    }

    public static byte[] encode(CoreMessage message) {
        byte[] payload = message.payload();
        if (payload.length > MAX_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException("message payload is too large");
        }
        CoreMessageHeader header = message.header();
        ByteBuffer buffer = ByteBuffer.allocate(CoreProtocol.HEADER_LENGTH + payload.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(CoreProtocol.MAGIC);
        buffer.putShort((short) header.schemaVersion());
        buffer.put((byte) header.kind().wireCode());
        buffer.put((byte) ProductLineWireCode.encode(header.productLine()));
        buffer.putShort((short) header.messageType().wireCode());
        buffer.put((byte) header.source().wireCode());
        buffer.put((byte) 0);
        buffer.putShort((short) CoreProtocol.HEADER_LENGTH);
        buffer.putShort((short) 0);
        buffer.putLong(header.commandId().getMostSignificantBits());
        buffer.putLong(header.commandId().getLeastSignificantBits());
        buffer.putLong(header.sourceId());
        buffer.putLong(header.sourceSequence());
        buffer.putLong(header.userId());
        buffer.putLong(header.submittedAtEpochMillis());
        buffer.putLong(header.correlationId());
        buffer.putInt(payload.length);
        buffer.put(payload);
        return buffer.array();
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
        if (schemaVersion > CoreProtocol.SCHEMA_VERSION) {
            throw new ProtocolException("unsupported future schema version: " + schemaVersion);
        }
        WireMessageKind kind = WireMessageKind.fromWireCode(Byte.toUnsignedInt(buffer.get()));
        var productLine = ProductLineWireCode.decode(Byte.toUnsignedInt(buffer.get()));
        CoreMessageType messageType = CoreMessageType.fromWireCode(Short.toUnsignedInt(buffer.getShort()));
        CommandSource source = CommandSource.fromWireCode(Byte.toUnsignedInt(buffer.get()));
        buffer.get();
        int headerLength = Short.toUnsignedInt(buffer.getShort());
        buffer.getShort();
        if (headerLength < CoreProtocol.HEADER_LENGTH || headerLength > encoded.length) {
            throw new ProtocolException("invalid header length: " + headerLength);
        }
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
                productLine, source, sourceId, sourceSequence, userId, submittedAtEpochMillis, correlationId);
        return new CoreMessage(header, payload);
    }
}
