package com.surprising.aeron.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class CoreFundingProgressCodec {

    private static final int LENGTH = Long.BYTES + Byte.BYTES + Long.BYTES + Integer.BYTES;

    private CoreFundingProgressCodec() {
    }

    public static byte[] encode(CoreFundingProgressView view) {
        return ByteBuffer.allocate(LENGTH).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(view.settlementId())
                .put((byte) (view.complete() ? 1 : 0))
                .putLong(view.nextCursorUserId())
                .putInt(view.processedUsers())
                .array();
    }

    public static CoreFundingProgressView decode(byte[] encoded) {
        if (encoded == null || encoded.length != LENGTH) {
            throw new ProtocolException("invalid funding progress payload");
        }
        ByteBuffer buffer = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        long settlementId = buffer.getLong();
        byte complete = buffer.get();
        if (complete != 0 && complete != 1) {
            throw new ProtocolException("invalid funding progress completion flag");
        }
        return new CoreFundingProgressView(settlementId, complete == 1, buffer.getLong(), buffer.getInt());
    }
}
