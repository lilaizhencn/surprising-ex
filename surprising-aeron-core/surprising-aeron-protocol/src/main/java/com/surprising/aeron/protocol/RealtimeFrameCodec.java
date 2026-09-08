package com.surprising.aeron.protocol;

import com.surprising.product.api.ProductLine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** A bounded, versioned protocol separate from the replicated Core command protocol. */
public final class RealtimeFrameCodec {
    private static final int MAGIC = 0x52544d31;
    public static final int MAX_FRAME_BYTES = 1_048_576;
    private RealtimeFrameCodec() {}
    public static byte[] encode(RealtimeFrame frame) {
        return encode(frame.productLine(), frame.kind(), frame.userId(), frame.sequence(), frame.ordinal(),
                frame.timestamp(), frame.snapshotId(), frame.symbol(), frame.entityId(), frame.payloadUnsafe());
    }

    /** Copies the payload directly into the returned envelope, retaining no caller-owned data. */
    public static byte[] encode(ProductLine productLine, RealtimeFrame.Kind kind, long userId, long sequence,
                                int ordinal, long timestamp, long snapshotId, String symbolValue,
                                String entityId, byte[] payload) {
        if (productLine == null || kind == null || userId < 0 || sequence < 0 || ordinal < 0
                || timestamp < 0 || snapshotId < 0 || symbolValue == null || entityId == null || payload == null) {
            throw new IllegalArgumentException("invalid realtime frame");
        }
        byte[] symbol = symbolValue.getBytes(StandardCharsets.UTF_8);
        byte[] entity = entityId.getBytes(StandardCharsets.UTF_8);
        int length = Math.addExact(64, Math.addExact(symbol.length, Math.addExact(entity.length, payload.length)));
        if (symbol.length > 128 || entity.length > 256 || length > MAX_FRAME_BYTES)
            throw new IllegalArgumentException("realtime frame too large");
        ByteBuffer b = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(MAGIC).putInt(1).putInt(productLine.ordinal()).putInt(kind.ordinal());
        b.putLong(userId).putLong(sequence).putInt(ordinal);
        b.putLong(timestamp).putLong(snapshotId);
        put(b, symbol); put(b, entity); put(b, payload);
        return b.array();
    }
    public static RealtimeFrame decode(byte[] bytes) {
        if (bytes == null || bytes.length < 64 || bytes.length > MAX_FRAME_BYTES)
            throw new IllegalArgumentException("invalid realtime frame size");
        try {
            ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            if (b.getInt() != MAGIC || b.getInt() != 1) throw new IllegalArgumentException("realtime protocol mismatch");
            int product = b.getInt(), kind = b.getInt();
            if (product < 0 || product >= ProductLine.values().length || kind < 0 || kind >= RealtimeFrame.Kind.values().length)
                throw new IllegalArgumentException("invalid realtime identity");
            long user = b.getLong(), sequence = b.getLong(); int ordinal = b.getInt();
            long time = b.getLong(), snapshot = b.getLong();
            String symbol = new String(get(b,128), StandardCharsets.UTF_8);
            String entity = new String(get(b,256), StandardCharsets.UTF_8);
            byte[] payload = get(b, MAX_FRAME_BYTES);
            if (b.hasRemaining()) throw new IllegalArgumentException("trailing realtime bytes");
            return new RealtimeFrame(ProductLine.values()[product], RealtimeFrame.Kind.values()[kind], user,
                    sequence, ordinal, time, snapshot, symbol, entity, payload);
        } catch (java.nio.BufferUnderflowException e) {
            throw new IllegalArgumentException("truncated realtime frame", e);
        }
    }
    private static void put(ByteBuffer b, byte[] value) { b.putInt(value.length).put(value); }
    private static byte[] get(ByteBuffer b, int max) {
        int n = b.getInt();
        if (n < 0 || n > max || n > b.remaining()) throw new IllegalArgumentException("invalid realtime field size");
        byte[] value = new byte[n]; b.get(value); return value;
    }
}
