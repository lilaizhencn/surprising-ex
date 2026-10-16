package com.surprising.aeron.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class CoreAlgoOrderCodec {
    private static final int VERSION = 1;
    private static final int MAX_TEXT_BYTES = 256;
    private static final int FIXED_ITEM_BYTES =
            Long.BYTES * 16 + Integer.BYTES * 8 + Byte.BYTES * 2;

    private CoreAlgoOrderCodec() { }

    public static byte[] encode(CoreAlgoOrderView value) {
        if (value == null) throw new IllegalArgumentException("algo order is required");
        int length = Integer.BYTES * 2 + encodedItemLength(value);
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(VERSION).putInt(1);
        write(buffer, value);
        return buffer.array();
    }

    public static CoreAlgoOrderView decode(byte[] payload) {
        List<CoreAlgoOrderView> values = decodeList(payload);
        if (values.size() != 1) throw new ProtocolException("expected one algo order");
        return values.getFirst();
    }

    public static byte[] encodeList(List<CoreAlgoOrderView> values) {
        if (values == null) throw new IllegalArgumentException("algo orders are required");
        int length = Integer.BYTES * 2;
        for (CoreAlgoOrderView value : values) {
            length = Math.addExact(length, encodedItemLength(value));
        }
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(VERSION).putInt(values.size());
        for (CoreAlgoOrderView value : values) {
            write(buffer, value);
        }
        return buffer.array();
    }

    public static List<CoreAlgoOrderView> decodeList(byte[] payload) {
        Reader reader = new Reader(payload); reader.version();
        int count = reader.count(); List<CoreAlgoOrderView> values = new ArrayList<>(count);
        for (int index = 0; index < count; index++) values.add(read(reader));
        reader.consumed(); return List.copyOf(values);
    }

    public static byte[] encodeQuery(long userId, long algoOrderId, String instrumentId, long dueAtEpochMillis, int limit) {
        String normalizedInstrument = instrumentId == null ? "" : instrumentId;
        int length = Long.BYTES * 3 + Integer.BYTES + textLength(normalizedInstrument);
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putLong(userId).putLong(algoOrderId);
        CoreStateQueryCodec.putText(buffer, normalizedInstrument, true, MAX_TEXT_BYTES);
        buffer.putLong(dueAtEpochMillis).putInt(limit);
        return buffer.array();
    }

    public static Query decodeQuery(byte[] payload) {
        Reader reader = new Reader(payload); Query query = new Query(reader.longValue(), reader.longValue(), reader.text(),
                reader.longValue(), reader.intValue()); reader.consumed(); return query;
    }

    private static int encodedItemLength(CoreAlgoOrderView value) {
        if (value == null) throw new IllegalArgumentException("algo order is required");
        int length = FIXED_ITEM_BYTES;
        length = Math.addExact(length, textLength(value.clientAlgoOrderId()));
        length = Math.addExact(length, textLength(value.instrumentId()));
        length = Math.addExact(length, textLength(value.rejectReason()));
        return Math.addExact(length, Math.multiplyExact(value.childOrderIds().size(), Long.BYTES));
    }

    private static int textLength(String value) {
        if (value == null) throw new IllegalArgumentException("text is required");
        int bytes = CoreStateQueryCodec.utf8Length(value);
        if (bytes > MAX_TEXT_BYTES) throw new IllegalArgumentException("text too long");
        return Integer.BYTES + bytes;
    }

    private static void write(ByteBuffer buffer, CoreAlgoOrderView value) {
        buffer.putLong(value.algoOrderId()).putLong(value.userId());
        CoreStateQueryCodec.putText(buffer, value.clientAlgoOrderId(), true, MAX_TEXT_BYTES);
        CoreStateQueryCodec.putText(buffer, value.instrumentId(), true, MAX_TEXT_BYTES);
        buffer.putInt(value.algoTypeCode()).putInt(value.side().wireCode())
                .putLong(value.priceTicks()).putLong(value.quantitySteps()).putLong(value.childQuantitySteps())
                .putLong(value.intervalSeconds()).putLong(value.durationSeconds())
                .putInt(value.marginMode().wireCode()).putInt(value.positionSide().wireCode())
                .put((byte) (value.reduceOnly() ? 1 : 0)).put((byte) (value.postOnly() ? 1 : 0))
                .putInt(value.timeInForce().wireCode())
                .putInt(value.statusCode()).putLong(value.currentOrderId());
        CoreStateQueryCodec.putText(buffer, value.rejectReason(), true, MAX_TEXT_BYTES);
        buffer.putLong(value.startAtEpochMillis()).putLong(value.nextSliceAtEpochMillis())
                .putLong(value.completedAtEpochMillis()).putLong(value.createdAtEpochMillis())
                .putLong(value.updatedAtEpochMillis()).putLong(value.revision())
                .putInt(value.childOrderIds().size());
        for (Long childOrderId : value.childOrderIds()) {
            buffer.putLong(childOrderId);
        }
        buffer.putLong(value.executedQuantitySteps()).putLong(value.activeQuantitySteps())
                .putInt(value.activeChildOrderCount());
    }

    private static CoreAlgoOrderView read(Reader reader) {
        long id = reader.longValue(), userId = reader.longValue(); String clientId = reader.text(), instrumentId = reader.text();
        int type = reader.intValue(); CoreOrderSide side = CoreOrderSide.fromWireCode(reader.intValue());
        long price = reader.longValue(), quantity = reader.longValue(), childQuantity = reader.longValue();
        long interval = reader.longValue(), duration = reader.longValue();
        CoreMarginMode margin = CoreMarginMode.fromWireCode(reader.intValue());
        CorePositionSide position = CorePositionSide.fromWireCode(reader.intValue());
        boolean reduce = reader.bool(), post = reader.bool(); CoreTimeInForce tif = CoreTimeInForce.fromWireCode(reader.intValue());
        int status = reader.intValue(); long current = reader.longValue(); String reason = reader.text();
        long start = reader.longValue(), next = reader.longValue(), completed = reader.longValue();
        long created = reader.longValue(), updated = reader.longValue(), revision = reader.longValue();
        int childCount = reader.count(); List<Long> children = new ArrayList<>(childCount);
        for (int index = 0; index < childCount; index++) children.add(reader.longValue());
        return new CoreAlgoOrderView(id, userId, clientId, instrumentId, type, side, price, quantity, childQuantity,
                interval, duration, margin, position, reduce, post, tif, status, current, reason,
                start, next, completed, created, updated, revision, children, reader.longValue(), reader.longValue(), reader.intValue());
    }

    public record Query(long userId, long algoOrderId, String instrumentId, long dueAtEpochMillis, int limit) {
        public Query { if (userId < 0 || algoOrderId < 0 || dueAtEpochMillis < 0 || limit < 1 || limit > 1000) throw new IllegalArgumentException("invalid algo query"); }
    }

    private static final class Reader {
        private final byte[] payload;
        private final ByteBuffer buffer;
        Reader(byte[] payload) {
            if (payload == null) throw new ProtocolException("payload required");
            this.payload = payload;
            this.buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        }
        void version() { if (intValue() != VERSION) throw new ProtocolException("unsupported algo codec version"); }
        int intValue() { require(4); return buffer.getInt(); } long longValue() { require(8); return buffer.getLong(); }
        boolean bool() { require(1); byte value = buffer.get(); if (value != 0 && value != 1) throw new ProtocolException("invalid boolean"); return value == 1; }
        int count() { int value = intValue(); if (value < 0 || value > 1_000_000) throw new ProtocolException("invalid count"); return value; }
        String text() {
            int length = count();
            if (length > MAX_TEXT_BYTES) throw new ProtocolException("text too long");
            require(length);
            if (length == 0) return "";
            String value = new String(payload, buffer.position(), length, StandardCharsets.UTF_8);
            buffer.position(buffer.position() + length);
            return value;
        }
        void require(int count) { if (buffer.remaining() < count) throw new ProtocolException("truncated algo payload"); }
        void consumed() { if (buffer.hasRemaining()) throw new ProtocolException("trailing algo payload"); }
    }
}
