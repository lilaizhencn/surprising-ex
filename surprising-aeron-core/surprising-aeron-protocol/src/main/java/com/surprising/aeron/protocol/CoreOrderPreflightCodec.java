package com.surprising.aeron.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public final class CoreOrderPreflightCodec {

    private CoreOrderPreflightCodec() {
    }

    public static byte[] encode(CoreOrderPreflightView view) {
        byte[] asset = view.reservationAsset().getBytes(StandardCharsets.UTF_8);
        if (asset.length == 0 || asset.length > 64) throw new IllegalArgumentException("invalid reservation asset");
        return ByteBuffer.allocate(Short.BYTES + asset.length + Long.BYTES).order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) asset.length).put(asset).putLong(view.reservedUnits()).array();
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
        byte[] asset = new byte[length];
        buffer.get(asset);
        return new CoreOrderPreflightView(new String(asset, StandardCharsets.UTF_8), buffer.getLong());
    }
}
