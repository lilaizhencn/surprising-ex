package com.surprising.aeron.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class CoreInputEventCodec {

    private static final int MAGIC = 0x5358494e;
    private static final int FIXED_LENGTH = 20;

    private CoreInputEventCodec() {
    }

    public static byte[] encode(CoreInputEvent event) {
        byte[] payload = event.commandPayload();
        if (payload.length > CoreExportCodec.MAX_COMMAND_PAYLOAD) {
            throw new IllegalArgumentException("core input payload is too large");
        }
        return ByteBuffer.allocate(FIXED_LENGTH + payload.length).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(MAGIC)
                .putShort((short) event.schemaVersion())
                .put((byte) ProductLineWireCode.encode(event.productLine()))
                .put((byte) 0)
                .putInt(event.commandType().wireCode())
                .putLong(event.userId())
                .put(payload)
                .array();
    }

    public static CoreInputEvent decode(byte[] encoded) {
        if (encoded == null || encoded.length < FIXED_LENGTH) {
            throw new ProtocolException("core input event is truncated");
        }
        ByteBuffer input = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        if (input.getInt() != MAGIC) {
            throw new ProtocolException("invalid core input event magic");
        }
        int schemaVersion = Short.toUnsignedInt(input.getShort());
        if (schemaVersion != CoreProtocol.SCHEMA_VERSION) {
            throw new ProtocolException("unsupported core input schema: " + schemaVersion);
        }
        var productLine = ProductLineWireCode.decode(Byte.toUnsignedInt(input.get()));
        input.get();
        CoreMessageType commandType = CoreMessageType.fromWireCode(input.getInt());
        long userId = input.getLong();
        byte[] payload = new byte[input.remaining()];
        input.get(payload);
        return new CoreInputEvent(schemaVersion, productLine, commandType, userId, payload);
    }
}
