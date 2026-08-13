package com.surprising.aeron.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class CoreExportCodec {

    private static final int EVENT_FIXED_LENGTH = 64;
    public static final int MAX_COMMAND_PAYLOAD =
            CoreMessageCodec.MAX_PAYLOAD_LENGTH - EVENT_FIXED_LENGTH;
    private static final int MAX_BATCH_EVENTS = 4096;
    public static final int MAX_BATCH_ENCODED_LENGTH =
            CoreMessageCodec.MAX_PAYLOAD_LENGTH - CoreProtocol.RESPONSE_FIXED_PAYLOAD_LENGTH;

    private CoreExportCodec() {
    }

    public static byte[] encodeAck(AckExportCommand command) {
        return littleEndian(Long.BYTES).putLong(command.throughSequence()).array();
    }

    public static AckExportCommand decodeAck(byte[] encoded) {
        return new AckExportCommand(exact(encoded, Long.BYTES).getLong());
    }

    public static byte[] encodeBatchQuery(int maxEvents) {
        if (maxEvents <= 0 || maxEvents > MAX_BATCH_EVENTS) {
            throw new IllegalArgumentException("invalid export batch size");
        }
        return littleEndian(Integer.BYTES).putInt(maxEvents).array();
    }

    public static int decodeBatchQuery(byte[] encoded) {
        int maxEvents = exact(encoded, Integer.BYTES).getInt();
        if (maxEvents <= 0 || maxEvents > MAX_BATCH_EVENTS) {
            throw new ProtocolException("invalid export batch size");
        }
        return maxEvents;
    }

    public static byte[] encodeEvent(CoreExportEvent event) {
        byte[] payload = event.commandPayload();
        if (payload.length > MAX_COMMAND_PAYLOAD) {
            throw new IllegalArgumentException("export event payload is too large");
        }
        ByteBuffer output = littleEndian(Math.addExact(EVENT_FIXED_LENGTH, payload.length));
        output.putLong(event.exportSequence());
        output.putLong(event.appliedCommandCount());
        output.putLong(event.businessStateHash());
        output.putLong(event.commandId().getMostSignificantBits());
        output.putLong(event.commandId().getLeastSignificantBits());
        output.putInt(event.commandType().wireCode());
        output.putInt(event.commandStatus().wireCode());
        output.putInt(event.resultCode().wireCode());
        output.putLong(event.userId());
        output.putInt(payload.length);
        output.put(payload);
        return output.array();
    }

    public static CoreExportEvent decodeEvent(byte[] encoded) {
        if (encoded == null || encoded.length < EVENT_FIXED_LENGTH) {
            throw new ProtocolException("export event is truncated");
        }
        ByteBuffer input = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        long sequence = input.getLong();
        long appliedCount = input.getLong();
        long businessHash = input.getLong();
        UUID commandId = new UUID(input.getLong(), input.getLong());
        CoreMessageType commandType = CoreMessageType.fromWireCode(input.getInt());
        ResponseStatus status = ResponseStatus.fromWireCode(input.getInt());
        CoreResultCode resultCode = CoreResultCode.fromWireCode(input.getInt());
        long userId = input.getLong();
        int payloadLength = input.getInt();
        if (payloadLength < 0 || payloadLength > MAX_COMMAND_PAYLOAD || input.remaining() != payloadLength) {
            throw new ProtocolException("invalid export event payload length");
        }
        byte[] payload = new byte[payloadLength];
        input.get(payload);
        return new CoreExportEvent(sequence, appliedCount, businessHash, commandId,
                commandType, status, resultCode, userId, payload);
    }

    public static byte[] encodeBatch(List<CoreMessage> events) {
        if (events == null || events.size() > MAX_BATCH_EVENTS) {
            throw new IllegalArgumentException("invalid export event batch");
        }
        List<byte[]> encoded = events.stream().map(CoreMessageCodec::encode).toList();
        long total = Integer.BYTES;
        for (byte[] event : encoded) {
            total = Math.addExact(total, Math.addExact(Integer.BYTES, event.length));
        }
        if (total > MAX_BATCH_ENCODED_LENGTH) {
            throw new IllegalArgumentException("export batch exceeds response payload limit");
        }
        ByteBuffer output = littleEndian(Math.toIntExact(total));
        output.putInt(encoded.size());
        encoded.forEach(event -> output.putInt(event.length).put(event));
        return output.array();
    }

    public static List<CoreMessage> decodeBatch(byte[] encoded) {
        if (encoded == null || encoded.length > MAX_BATCH_ENCODED_LENGTH) {
            throw new ProtocolException("invalid export batch length");
        }
        ByteBuffer input = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        if (input.remaining() < Integer.BYTES) {
            throw new ProtocolException("export batch is truncated");
        }
        int count = input.getInt();
        if (count < 0 || count > MAX_BATCH_EVENTS) {
            throw new ProtocolException("invalid export batch count");
        }
        List<CoreMessage> events = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            if (input.remaining() < Integer.BYTES) {
                throw new ProtocolException("export batch entry is truncated");
            }
            int length = input.getInt();
            if (length < 0 || length > input.remaining()) {
                throw new ProtocolException("invalid export batch entry length");
            }
            byte[] event = new byte[length];
            input.get(event);
            events.add(CoreMessageCodec.decode(event));
        }
        if (input.hasRemaining()) {
            throw new ProtocolException("export batch has trailing bytes");
        }
        return List.copyOf(events);
    }

    public static byte[] encodeStatus(CoreExportStatus status) {
        return littleEndian(Long.BYTES * 4 + Integer.BYTES * 2)
                .putLong(status.acknowledgedSequence()).putLong(status.nextSequence())
                .putInt(status.pendingCount()).putLong(status.pendingBytes())
                .putInt(status.maxPendingCount()).putLong(status.maxPendingBytes()).array();
    }

    public static CoreExportStatus decodeStatus(byte[] encoded) {
        ByteBuffer input = exact(encoded, Long.BYTES * 4 + Integer.BYTES * 2);
        return new CoreExportStatus(input.getLong(), input.getLong(), input.getInt(), input.getLong(),
                input.getInt(), input.getLong());
    }

    private static ByteBuffer exact(byte[] encoded, int length) {
        if (encoded == null || encoded.length != length) {
            throw new ProtocolException("invalid export payload length");
        }
        return ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static ByteBuffer littleEndian(int length) {
        return ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
    }
}
