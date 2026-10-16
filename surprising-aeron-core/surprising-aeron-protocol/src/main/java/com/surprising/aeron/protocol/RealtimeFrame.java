package com.surprising.aeron.protocol;

import com.surprising.product.api.ProductLine;

/** Internal realtime envelope. Sequence is a committed cluster log position, not an entity revision. */
public record RealtimeFrame(ProductLine productLine, Kind kind, long userId, long sequence,
                            int ordinal, long timestamp, long snapshotId, String instrumentId,
                            String entityId, byte[] payload) {
    public enum Kind { USER, ORDER, TRIGGER, TRADE, BOOK, INDEX, MARK, FUNDING, CANDLE,
        SNAPSHOT_BEGIN, SNAPSHOT_END, SNAPSHOT_UNAVAILABLE, BALANCE, POSITION, METADATA, RESERVATION, LEVERAGE, SNAPSHOT_REQUEST, COMMIT_BEGIN, COMMIT_END, BOOK_REQUEST, EXECUTION, RISK }
    public RealtimeFrame {
        if (productLine == null || kind == null || userId < 0 || sequence < 0 || ordinal < 0
                || timestamp < 0 || snapshotId < 0 || instrumentId == null || entityId == null || payload == null) {
            throw new IllegalArgumentException("invalid realtime frame");
        }
        payload = payload.clone();
    }
    public int payloadLength() { return payload.length; }
    byte[] payloadUnsafe() { return payload; }
    @Override public byte[] payload() { return payload.clone(); }

    @Override
    public boolean equals(Object other) {
        return other instanceof RealtimeFrame that
                && productLine == that.productLine && kind == that.kind
                && userId == that.userId && sequence == that.sequence
                && ordinal == that.ordinal && timestamp == that.timestamp
                && snapshotId == that.snapshotId
                && java.util.Objects.equals(instrumentId, that.instrumentId)
                && java.util.Objects.equals(entityId, that.entityId)
                && java.util.Arrays.equals(payload, that.payload);
    }

    @Override
    public int hashCode() {
        int result = java.util.Objects.hash(productLine, kind, userId, sequence, ordinal, timestamp, snapshotId,
                instrumentId, entityId);
        return 31 * result + java.util.Arrays.hashCode(payload);
    }
}
