package com.surprising.aeron.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class CoreSettlementProgressCodec {

    private static final int LENGTH = Long.BYTES + Byte.BYTES * 2 + Long.BYTES * 2 + Integer.BYTES * 2;

    private CoreSettlementProgressCodec() {
    }

    public static byte[] encode(CoreSettlementProgressView view) {
        return ByteBuffer.allocate(LENGTH).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(view.settlementId())
                .put((byte) (view.complete() ? 1 : 0))
                .put((byte) (view.ordersComplete() ? 1 : 0))
                .putLong(view.nextCursorOrderId())
                .putLong(view.nextCursorUserId())
                .putInt(view.processedOrders())
                .putInt(view.processedUsers())
                .array();
    }

    public static CoreSettlementProgressView decode(byte[] encoded) {
        if (encoded == null || encoded.length != LENGTH) {
            throw new ProtocolException("invalid settlement progress payload");
        }
        ByteBuffer buffer = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        long settlementId = buffer.getLong();
        byte complete = buffer.get();
        if (complete != 0 && complete != 1) {
            throw new ProtocolException("invalid settlement completion flag");
        }
        byte ordersComplete = buffer.get();
        if (ordersComplete != 0 && ordersComplete != 1) {
            throw new ProtocolException("invalid settlement order completion flag");
        }
        try {
            return new CoreSettlementProgressView(settlementId, complete == 1, ordersComplete == 1,
                    buffer.getLong(), buffer.getLong(), buffer.getInt(), buffer.getInt());
        } catch (IllegalArgumentException exception) {
            throw new ProtocolException(exception.getMessage());
        }
    }
}
