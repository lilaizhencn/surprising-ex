package com.surprising.aeron.protocol;

import com.surprising.product.api.ProductLine;

/** Internal realtime envelope. Sequence is a committed cluster log position, not an entity revision. */
public record RealtimeFrame(ProductLine productLine, Kind kind, long userId, long sequence,
                            int ordinal, long timestamp, long snapshotId, String symbol,
                            String entityId, byte[] payload) {
    public enum Kind { USER, ORDER, TRIGGER, TRADE, BOOK, INDEX, MARK, FUNDING, CANDLE,
        SNAPSHOT_BEGIN, SNAPSHOT_END, SNAPSHOT_UNAVAILABLE, BALANCE, POSITION, METADATA, RESERVATION, LEVERAGE, SNAPSHOT_REQUEST, COMMIT_BEGIN, COMMIT_END }
    public RealtimeFrame {
        if (productLine == null || kind == null || userId < 0 || sequence < 0 || ordinal < 0
                || timestamp < 0 || snapshotId < 0 || symbol == null || entityId == null || payload == null) {
            throw new IllegalArgumentException("invalid realtime frame");
        }
        payload = payload.clone();
    }
    public int payloadLength() { return payload.length; }
    byte[] payloadUnsafe() { return payload; }
    @Override public byte[] payload() { return payload.clone(); }
}
