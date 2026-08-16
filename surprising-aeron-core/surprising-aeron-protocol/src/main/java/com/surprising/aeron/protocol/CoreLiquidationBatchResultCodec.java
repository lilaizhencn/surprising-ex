package com.surprising.aeron.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class CoreLiquidationBatchResultCodec {
    private static final int VERSION = 1;
    private static final int LENGTH = Integer.BYTES * 7;

    private CoreLiquidationBatchResultCodec() {
    }

    public static byte[] encode(CoreLiquidationBatchResultView result) {
        return ByteBuffer.allocate(LENGTH).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(VERSION)
                .putInt(result.offeredActions())
                .putInt(result.appliedActions())
                .putInt(result.pendingActions())
                .putInt(result.obsoleteActions())
                .putInt(result.processedOrders())
                .putInt(result.riskScanContinuedUsers())
                .array();
    }

    public static CoreLiquidationBatchResultView decode(byte[] encoded) {
        if (encoded == null || encoded.length != LENGTH) {
            throw new ProtocolException("invalid liquidation batch result payload");
        }
        ByteBuffer buffer = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        int version = buffer.getInt();
        if (version != VERSION) {
            throw new ProtocolException("unsupported liquidation batch result version: " + version);
        }
        try {
            return new CoreLiquidationBatchResultView(buffer.getInt(), buffer.getInt(), buffer.getInt(),
                    buffer.getInt(), buffer.getInt(), buffer.getInt());
        } catch (IllegalArgumentException exception) {
            throw new ProtocolException(exception.getMessage());
        }
    }
}
