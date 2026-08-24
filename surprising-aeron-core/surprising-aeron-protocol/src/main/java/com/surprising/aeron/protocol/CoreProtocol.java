package com.surprising.aeron.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class CoreProtocol {

    public static final int MAGIC = 0x53584558;
    public static final int SCHEMA_VERSION = 3;
    public static final int HEADER_LENGTH = 76;
    public static final int RESPONSE_FIXED_PAYLOAD_LENGTH = 40;
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
        byte[] data = response.data();
        return ByteBuffer.allocate(Math.addExact(RESPONSE_FIXED_PAYLOAD_LENGTH, data.length))
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(response.status().wireCode())
                .putInt(response.commandStatus().wireCode())
                .putInt(response.resultCode().wireCode())
                .putLong(response.appliedCommandCount())
                .putLong(response.requiredExportSequence())
                .putLong(response.stateHash())
                .putInt(data.length)
                .put(data)
                .array();
    }

    public static CoreResponse decodeResponse(byte[] payload) {
        if (payload.length < RESPONSE_FIXED_PAYLOAD_LENGTH) {
            throw new ProtocolException("response payload is shorter than fixed response");
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        ResponseStatus status = ResponseStatus.fromWireCode(buffer.getInt());
        ResponseStatus commandStatus = ResponseStatus.fromWireCode(buffer.getInt());
        CoreResultCode resultCode = CoreResultCode.fromWireCode(buffer.getInt());
        long appliedCommandCount = buffer.getLong();
        long requiredExportSequence = buffer.getLong();
        long stateHash = buffer.getLong();
        int dataLength = buffer.getInt();
        if (dataLength < 0 || dataLength != buffer.remaining()) {
            throw new ProtocolException("invalid response data length: " + dataLength);
        }
        byte[] data = new byte[dataLength];
        buffer.get(data);
        return new CoreResponse(status, commandStatus, resultCode, appliedCommandCount,
                requiredExportSequence, stateHash, data);
    }
}
