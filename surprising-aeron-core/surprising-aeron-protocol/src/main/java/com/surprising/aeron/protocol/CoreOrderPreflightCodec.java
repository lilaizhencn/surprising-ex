package com.surprising.aeron.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public final class CoreOrderPreflightCodec {

    private CoreOrderPreflightCodec() {
    }

    public static byte[] encode(CoreOrderPreflightView view) {
        int assetLength = CoreStateQueryCodec.utf8Length(view.reservationAsset());
        if (assetLength == 0 || assetLength > 64) throw new IllegalArgumentException("invalid reservation asset");
        ByteBuffer buffer = ByteBuffer.allocate(Short.BYTES + assetLength + Long.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) assetLength);
        CoreStateQueryCodec.writeUtf8Bytes(buffer, view.reservationAsset());
        return buffer.putLong(view.reservedUnits()).array();
    }

    public static CoreOrderPreflightView decode(byte[] payload) {
        if (payload == null || payload.length < Short.BYTES + 1 + Long.BYTES) {
            throw new ProtocolException("truncated order preflight result");
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        int length = Short.toUnsignedInt(buffer.getShort());
        if (length == 0 || length > 64 || buffer.remaining() != length + Long.BYTES) {
            throw new ProtocolException("invalid order preflight result");
        }
        String asset = new String(payload, buffer.position(), length, StandardCharsets.UTF_8);
        buffer.position(buffer.position() + length);
        return new CoreOrderPreflightView(asset, buffer.getLong());
    }
}
