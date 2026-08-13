package com.surprising.aeron.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class CoreProtocol {

    public static final int MAGIC = 0x53584558;
    public static final int SCHEMA_VERSION = 1;
    public static final int HEADER_LENGTH = 76;
    public static final int RESPONSE_PAYLOAD_LENGTH = 20;
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
        return ByteBuffer.allocate(RESPONSE_PAYLOAD_LENGTH)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(response.status().wireCode())
                .putLong(response.appliedCommandCount())
                .putLong(response.stateHash())
                .array();
    }

    public static CoreResponse decodeResponse(byte[] payload) {
        if (payload.length != RESPONSE_PAYLOAD_LENGTH) {
            throw new ProtocolException("response payload must be " + RESPONSE_PAYLOAD_LENGTH + " bytes");
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        return new CoreResponse(ResponseStatus.fromWireCode(buffer.getInt()), buffer.getLong(), buffer.getLong());
    }
}
