package com.surprising.aeron.protocol;

import com.surprising.product.api.ProductLine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** A bounded, versioned protocol separate from the replicated Core command protocol. */
public final class RealtimeFrameCodec {
    private static final int MAGIC = 0x52544d31;
    private static final ProductLine[] PRODUCTS = ProductLine.values();
    private static final RealtimeFrame.Kind[] KINDS = RealtimeFrame.Kind.values();
    public static final int MAX_FRAME_BYTES = 1_048_576;
    private RealtimeFrameCodec() {}

    /** Encode an order payload in its final envelope; no intermediate payload or text arrays escape. */
    public static byte[] encodeOrder(CoreOrderStateView order, long sequence, int ordinal,
                                     long timestamp, long snapshotId) {
        return encodeOrder(order, sequence, ordinal, timestamp, snapshotId, "");
    }
    public static byte[] encodeOrder(CoreOrderStateView order, long sequence, int ordinal,
                                     long timestamp, long snapshotId, String traceId) {
        traceId = TraceIds.validate(traceId);
        int payloadLength = CoreStateQueryCodec.encodedOrderStateLength(order);
        if (order.productLine() == null || order.userId() < 0 || sequence < 0 || ordinal < 0
                || timestamp < 0 || snapshotId < 0) {
            throw new IllegalArgumentException("invalid realtime frame");
        }
        String entityId = Long.toString(order.orderId());
        int symbolLength = CoreStateQueryCodec.utf8Length(order.instrumentId());
        int length = Math.addExact(64 + (traceId.isEmpty() ? 0 : 4 + traceId.length()), Math.addExact(payloadLength, symbolLength + entityId.length()));
        if (length > MAX_FRAME_BYTES) throw new IllegalArgumentException("realtime frame too large");
        ByteBuffer output = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        output.putInt(MAGIC).putInt(traceId.isEmpty() ? 1 : 2).putInt(order.productLine().ordinal()).putInt(RealtimeFrame.Kind.ORDER.ordinal());
        output.putLong(order.userId()).putLong(sequence).putInt(ordinal).putLong(timestamp).putLong(snapshotId);
        CoreStateQueryCodec.putText(output, order.instrumentId(), false);
        CoreStateQueryCodec.putText(output, entityId, false);
        output.putInt(payloadLength);
        CoreStateQueryCodec.writeOrderState(output, order);
        if (!traceId.isEmpty()) CoreStateQueryCodec.putText(output, traceId, true, TraceIds.MAX_LENGTH);
        return output.array();
    }
    public static byte[] encode(RealtimeFrame frame) {
        return encode(frame.productLine(), frame.kind(), frame.userId(), frame.sequence(), frame.ordinal(),
                frame.timestamp(), frame.snapshotId(), frame.instrumentId(), frame.entityId(), frame.payloadUnsafe(), frame.traceId());
    }

    /** Copies the payload directly into the returned envelope, retaining no caller-owned data. */
    public static byte[] encode(ProductLine productLine, RealtimeFrame.Kind kind, long userId, long sequence,
                                int ordinal, long timestamp, long snapshotId, String symbolValue,
                                String entityId, byte[] payload) {
        return encode(productLine, kind, userId, sequence, ordinal, timestamp, snapshotId, symbolValue, entityId, payload, "");
    }

    public static byte[] encode(ProductLine productLine, RealtimeFrame.Kind kind, long userId, long sequence,
                                int ordinal, long timestamp, long snapshotId, String symbolValue,
                                String entityId, byte[] payload, String traceId) {
        traceId = TraceIds.validate(traceId);
        if (productLine == null || kind == null || userId < 0 || sequence < 0 || ordinal < 0
                || timestamp < 0 || snapshotId < 0 || symbolValue == null || entityId == null || payload == null) {
            throw new IllegalArgumentException("invalid realtime frame");
        }
        int symbolLength = CoreStateQueryCodec.utf8Length(symbolValue);
        int entityLength = CoreStateQueryCodec.utf8Length(entityId);
        int length = Math.addExact(64 + (traceId.isEmpty() ? 0 : 4 + traceId.length()), Math.addExact(symbolLength, Math.addExact(entityLength, payload.length)));
        if (symbolLength > 128 || entityLength > 256 || length > MAX_FRAME_BYTES)
            throw new IllegalArgumentException("realtime frame too large");
        ByteBuffer b = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(MAGIC).putInt(traceId.isEmpty() ? 1 : 2).putInt(productLine.ordinal()).putInt(kind.ordinal());
        b.putLong(userId).putLong(sequence).putInt(ordinal);
        b.putLong(timestamp).putLong(snapshotId);
        CoreStateQueryCodec.putText(b, symbolValue, true, 128);
        CoreStateQueryCodec.putText(b, entityId, true, 256);
        put(b, payload);
        if (!traceId.isEmpty()) CoreStateQueryCodec.putText(b, traceId, true, TraceIds.MAX_LENGTH);
        return b.array();
    }
    public static RealtimeFrame decode(byte[] bytes) {
        if (bytes == null) throw new IllegalArgumentException("invalid realtime frame size");
        return decode(ByteBuffer.wrap(bytes));
    }

    /** Decodes within the caller's bounds; no reference to the borrowed transport buffer escapes. */
    public static RealtimeFrame decode(ByteBuffer bytes) {
        if (bytes == null || bytes.remaining() < 64 || bytes.remaining() > MAX_FRAME_BYTES)
            throw new IllegalArgumentException("invalid realtime frame size");
        try {
            ByteBuffer b = bytes.slice().order(ByteOrder.LITTLE_ENDIAN);
            if (b.getInt() != MAGIC) throw new IllegalArgumentException("realtime protocol mismatch");
            int version = b.getInt();
            if (version != 1 && version != 2) throw new IllegalArgumentException("realtime protocol mismatch");
            int product = b.getInt(), kind = b.getInt();
            if (product < 0 || product >= PRODUCTS.length || kind < 0 || kind >= KINDS.length)
                throw new IllegalArgumentException("invalid realtime identity");
            long user = b.getLong(), sequence = b.getLong(); int ordinal = b.getInt();
            long time = b.getLong(), snapshot = b.getLong();
            String instrumentId = text(b,128);
            String entity = text(b,256);
            byte[] payload = get(b, MAX_FRAME_BYTES);
            String traceId = version == 2 ? TraceIds.validate(text(b, TraceIds.MAX_LENGTH)) : "";
            if (version == 2 && traceId.isEmpty()) throw new IllegalArgumentException("missing realtime trace id");
            if (b.hasRemaining()) throw new IllegalArgumentException("trailing realtime bytes");
            return new RealtimeFrame(PRODUCTS[product], KINDS[kind], user,
                    sequence, ordinal, time, snapshot, instrumentId, entity, payload, traceId);
        } catch (java.nio.BufferUnderflowException e) {
            throw new IllegalArgumentException("truncated realtime frame", e);
        }
    }
    private static void put(ByteBuffer b, byte[] value) { b.putInt(value.length).put(value); }
    private static String text(ByteBuffer b, int max) {
        int n = fieldLength(b, max);
        if (b.hasArray()) {
            String value = new String(b.array(), b.arrayOffset() + b.position(), n, StandardCharsets.UTF_8);
            b.position(b.position() + n);
            return value;
        }
        // Direct/read-only transport buffers expose no array. Only the small text field is copied.
        byte[] value = new byte[n];
        b.get(value);
        return new String(value, StandardCharsets.UTF_8);
    }
    private static byte[] get(ByteBuffer b, int max) {
        int n = fieldLength(b, max);
        byte[] value = new byte[n]; b.get(value); return value;
    }
    private static int fieldLength(ByteBuffer b, int max) {
        int n = b.getInt();
        if (n < 0 || n > max || n > b.remaining()) throw new IllegalArgumentException("invalid realtime field size");
        return n;
    }
}
