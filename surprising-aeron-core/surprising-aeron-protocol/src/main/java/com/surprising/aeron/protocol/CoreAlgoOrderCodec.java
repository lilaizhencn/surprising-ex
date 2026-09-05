package com.surprising.aeron.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class CoreAlgoOrderCodec {
    private static final int VERSION = 1;
    private static final int MAX_TEXT_BYTES = 256;

    private CoreAlgoOrderCodec() { }

    public static byte[] encode(CoreAlgoOrderView value) { return encodeList(List.of(value)); }

    public static CoreAlgoOrderView decode(byte[] payload) {
        List<CoreAlgoOrderView> values = decodeList(payload);
        if (values.size() != 1) throw new ProtocolException("expected one algo order");
        return values.getFirst();
    }

    public static byte[] encodeList(List<CoreAlgoOrderView> values) {
        Writer writer = new Writer();
        writer.intValue(VERSION); writer.intValue(values.size());
        values.forEach(value -> write(writer, value));
        return writer.bytes();
    }

    public static List<CoreAlgoOrderView> decodeList(byte[] payload) {
        Reader reader = new Reader(payload); reader.version();
        int count = reader.count(); List<CoreAlgoOrderView> values = new ArrayList<>(count);
        for (int index = 0; index < count; index++) values.add(read(reader));
        reader.consumed(); return List.copyOf(values);
    }

    public static byte[] encodeQuery(long userId, long algoOrderId, String symbol, long dueAtEpochMillis, int limit) {
        Writer writer = new Writer(); writer.longValue(userId); writer.longValue(algoOrderId);
        writer.text(symbol == null ? "" : symbol);
        writer.longValue(dueAtEpochMillis); writer.intValue(limit); return writer.bytes();
    }

    public static Query decodeQuery(byte[] payload) {
        Reader reader = new Reader(payload); Query query = new Query(reader.longValue(), reader.longValue(), reader.text(),
                reader.longValue(), reader.intValue()); reader.consumed(); return query;
    }

    private static void write(Writer writer, CoreAlgoOrderView value) {
        writer.longValue(value.algoOrderId()); writer.longValue(value.userId()); writer.text(value.clientAlgoOrderId());
        writer.text(value.symbol()); writer.intValue(value.algoTypeCode()); writer.intValue(value.side().wireCode());
        writer.longValue(value.priceTicks()); writer.longValue(value.quantitySteps()); writer.longValue(value.childQuantitySteps());
        writer.longValue(value.intervalSeconds()); writer.longValue(value.durationSeconds());
        writer.intValue(value.marginMode().wireCode()); writer.intValue(value.positionSide().wireCode());
        writer.byteValue(value.reduceOnly()); writer.byteValue(value.postOnly()); writer.intValue(value.timeInForce().wireCode());
        writer.intValue(value.statusCode()); writer.longValue(value.currentOrderId()); writer.text(value.rejectReason());
        writer.text(value.traceId()); writer.longValue(value.startAtEpochMillis()); writer.longValue(value.nextSliceAtEpochMillis());
        writer.longValue(value.completedAtEpochMillis()); writer.longValue(value.createdAtEpochMillis());
        writer.longValue(value.updatedAtEpochMillis()); writer.longValue(value.revision());
        writer.intValue(value.childOrderIds().size()); value.childOrderIds().forEach(writer::longValue);
        writer.longValue(value.executedQuantitySteps()); writer.longValue(value.activeQuantitySteps());
        writer.intValue(value.activeChildOrderCount());
    }

    private static CoreAlgoOrderView read(Reader reader) {
        long id = reader.longValue(), userId = reader.longValue(); String clientId = reader.text(), symbol = reader.text();
        int type = reader.intValue(); CoreOrderSide side = CoreOrderSide.fromWireCode(reader.intValue());
        long price = reader.longValue(), quantity = reader.longValue(), childQuantity = reader.longValue();
        long interval = reader.longValue(), duration = reader.longValue();
        CoreMarginMode margin = CoreMarginMode.fromWireCode(reader.intValue());
        CorePositionSide position = CorePositionSide.fromWireCode(reader.intValue());
        boolean reduce = reader.bool(), post = reader.bool(); CoreTimeInForce tif = CoreTimeInForce.fromWireCode(reader.intValue());
        int status = reader.intValue(); long current = reader.longValue(); String reason = reader.text(), trace = reader.text();
        long start = reader.longValue(), next = reader.longValue(), completed = reader.longValue();
        long created = reader.longValue(), updated = reader.longValue(), revision = reader.longValue();
        int childCount = reader.count(); List<Long> children = new ArrayList<>(childCount);
        for (int index = 0; index < childCount; index++) children.add(reader.longValue());
        return new CoreAlgoOrderView(id, userId, clientId, symbol, type, side, price, quantity, childQuantity,
                interval, duration, margin, position, reduce, post, tif, status, current, reason, trace,
                start, next, completed, created, updated, revision, children, reader.longValue(), reader.longValue(), reader.intValue());
    }

    public record Query(long userId, long algoOrderId, String symbol, long dueAtEpochMillis, int limit) {
        public Query { if (userId < 0 || algoOrderId < 0 || dueAtEpochMillis < 0 || limit < 1 || limit > 1000) throw new IllegalArgumentException("invalid algo query"); }
    }

    private static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        void intValue(int value) { write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()); }
        void longValue(long value) { write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()); }
        void byteValue(boolean value) { out.write(value ? 1 : 0); }
        void text(String value) { byte[] bytes = value.getBytes(StandardCharsets.UTF_8); if (bytes.length > MAX_TEXT_BYTES) throw new IllegalArgumentException("text too long"); intValue(bytes.length); write(bytes); }
        void write(byte[] bytes) { out.writeBytes(bytes); } byte[] bytes() { return out.toByteArray(); }
    }
    private static final class Reader {
        private final ByteBuffer buffer;
        Reader(byte[] payload) { if (payload == null) throw new ProtocolException("payload required"); buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN); }
        void version() { if (intValue() != VERSION) throw new ProtocolException("unsupported algo codec version"); }
        int intValue() { require(4); return buffer.getInt(); } long longValue() { require(8); return buffer.getLong(); }
        boolean bool() { require(1); byte value = buffer.get(); if (value != 0 && value != 1) throw new ProtocolException("invalid boolean"); return value == 1; }
        int count() { int value = intValue(); if (value < 0 || value > 1_000_000) throw new ProtocolException("invalid count"); return value; }
        String text() { int length = count(); if (length > MAX_TEXT_BYTES) throw new ProtocolException("text too long"); require(length); byte[] bytes = new byte[length]; buffer.get(bytes); return new String(bytes, StandardCharsets.UTF_8); }
        void require(int count) { if (buffer.remaining() < count) throw new ProtocolException("truncated algo payload"); }
        void consumed() { if (buffer.hasRemaining()) throw new ProtocolException("trailing algo payload"); }
    }
}
