package com.surprising.aeron.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class CoreProtocol {

    public static final int MAGIC = 0x53584558;
    public static final int SCHEMA_VERSION = 4;
    public static final int HEADER_LENGTH = 76;
    public static final int RESPONSE_FIXED_PAYLOAD_LENGTH = 52;
    public static final int CLUSTER_MAX_MESSAGE_LENGTH = 2 * 1024 * 1024;
    public static final int PROBE_PAYLOAD_LENGTH = Long.BYTES;

    private CoreProtocol() {
    }

    public static byte[] probePayload(long delta) {
        return ByteBuffer.allocate(PROBE_PAYLOAD_LENGTH).order(ByteOrder.LITTLE_ENDIAN).putLong(delta).array();
    }

    public static long decodeProbeDelta(byte[] payload) {
        if (payload.length != PROBE_PAYLOAD_LENGTH) {
            throw new ProtocolException("probe payload must be " + PROBE_PAYLOAD_LENGTH + " bytes");
        }
        return ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }

    public static byte[] responsePayload(CoreResponse response) {
        byte[] payload = new byte[responsePayloadLength(response)];
        encodeResponsePayload(response, response.committedCoreSequence(), payload, 0);
        return payload;
    }

    static int responsePayloadLength(CoreResponse response) {
        if (response == null) throw new IllegalArgumentException("core response is required");
        return Math.addExact(RESPONSE_FIXED_PAYLOAD_LENGTH, response.dataLength());
    }

    static int encodeResponsePayload(CoreResponse response, long committedCoreSequence,
                                     byte[] destination, int offset) {
        int length = responsePayloadLength(response);
        if (committedCoreSequence < 0 || destination == null || offset < 0
                || offset > destination.length - length) {
            throw new IllegalArgumentException("invalid response payload destination");
        }
        byte[] data = response.dataUnsafe();
        int cursor = offset;
        putInt(destination, cursor, response.status().wireCode()); cursor += Integer.BYTES;
        putInt(destination, cursor, response.commandStatus().wireCode()); cursor += Integer.BYTES;
        putInt(destination, cursor, response.resultCode().wireCode()); cursor += Integer.BYTES;
        putInt(destination, cursor, response.routeVersion()); cursor += Integer.BYTES;
        putLong(destination, cursor, committedCoreSequence); cursor += Long.BYTES;
        putLong(destination, cursor, response.appliedCommandCount()); cursor += Long.BYTES;
        putLong(destination, cursor, response.requiredExportSequence()); cursor += Long.BYTES;
        putLong(destination, cursor, response.stateHash()); cursor += Long.BYTES;
        putInt(destination, cursor, data.length); cursor += Integer.BYTES;
        System.arraycopy(data, 0, destination, cursor, data.length);
        return length;
    }

    static void putShort(byte[] destination, int offset, int value) {
        destination[offset] = (byte) value;
        destination[offset + 1] = (byte) (value >>> 8);
    }

    static void putInt(byte[] destination, int offset, int value) {
        destination[offset] = (byte) value;
        destination[offset + 1] = (byte) (value >>> 8);
        destination[offset + 2] = (byte) (value >>> 16);
        destination[offset + 3] = (byte) (value >>> 24);
    }

    static void putLong(byte[] destination, int offset, long value) {
        putInt(destination, offset, (int) value);
        putInt(destination, offset + Integer.BYTES, (int) (value >>> 32));
    }

    public static CoreResponse decodeResponse(byte[] payload) {
        if (payload.length < RESPONSE_FIXED_PAYLOAD_LENGTH) {
            throw new ProtocolException("response payload is shorter than fixed response");
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        ResponseStatus status = ResponseStatus.fromWireCode(buffer.getInt());
        ResponseStatus commandStatus = ResponseStatus.fromWireCode(buffer.getInt());
        CoreResultCode resultCode = CoreResultCode.fromWireCode(buffer.getInt());
        int routeVersion = buffer.getInt();
        long committedCoreSequence = buffer.getLong();
        long appliedCommandCount = buffer.getLong();
        long requiredExportSequence = buffer.getLong();
        long stateHash = buffer.getLong();
        int dataLength = buffer.getInt();
        if (dataLength < 0 || dataLength != buffer.remaining()) {
            throw new ProtocolException("invalid response data length: " + dataLength);
        }
        byte[] data = new byte[dataLength];
        buffer.get(data);
        return new CoreResponse(status, commandStatus, resultCode, routeVersion, committedCoreSequence,
                appliedCommandCount, requiredExportSequence, stateHash, data);
    }
}
