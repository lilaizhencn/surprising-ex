package com.surprising.aeron.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class CoreLiquidationProgressCodec {

    private static final int LENGTH = Byte.BYTES + Long.BYTES + Integer.BYTES;

    private CoreLiquidationProgressCodec() {
    }

    public static byte[] encode(CoreLiquidationProgressView view) {
        return ByteBuffer.allocate(LENGTH).order(ByteOrder.LITTLE_ENDIAN)
                .put((byte) (view.complete() ? 1 : 0))
                .putLong(view.nextCursorOrderId())
                .putInt(view.processedOrders())
                .array();
    }

    public static CoreLiquidationProgressView decode(byte[] encoded) {
        if (encoded == null || encoded.length != LENGTH) {
            throw new ProtocolException("invalid liquidation progress payload");
        }
        ByteBuffer buffer = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        byte complete = buffer.get();
        if (complete != 0 && complete != 1) {
            throw new ProtocolException("invalid liquidation completion flag");
        }
        try {
            return new CoreLiquidationProgressView(complete == 1, buffer.getLong(), buffer.getInt());
        } catch (IllegalArgumentException exception) {
            throw new ProtocolException(exception.getMessage());
        }
    }
}
