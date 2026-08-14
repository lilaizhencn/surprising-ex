package com.surprising.aeron.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class CoreCancelAllAfterCodec {
    private static final int VERSION = 1;
    private static final int MAX_TEXT_BYTES = 64;

    private CoreCancelAllAfterCodec() {
    }

    public static byte[] encodeCommand(CoreCancelAllAfterCommand command) {
        Writer writer = new Writer();
        writer.intValue(VERSION);
        writer.intValue(command.action().wireCode());
        writer.longValue(command.userId());
        writer.text(command.symbolScope());
        writer.longValue(command.countdownMillis());
        writer.longValue(command.triggerAtEpochMillis());
        writer.longValue(command.expectedRevision());
        writer.intValue(command.canceledOrders());
        writer.intValue(command.canceledTriggerOrders());
        writer.longValue(command.updatedAtEpochMillis());
        return writer.bytes();
    }

    public static CoreCancelAllAfterCommand decodeCommand(byte[] payload) {
        Reader reader = new Reader(payload);
        reader.version();
        CoreCancelAllAfterCommand command = new CoreCancelAllAfterCommand(
                CoreCancelAllAfterAction.fromWireCode(reader.intValue()), reader.longValue(), reader.text(),
                reader.longValue(), reader.longValue(), reader.longValue(), reader.intValue(), reader.intValue(),
                reader.longValue());
        reader.consumed();
        return command;
    }

    public static byte[] encodeQuery(long userId, String symbolScope, long dueAtEpochMillis, int limit) {
        Writer writer = new Writer();
        writer.intValue(VERSION);
        writer.longValue(userId);
        writer.text(symbolScope == null ? "" : symbolScope);
        writer.longValue(dueAtEpochMillis);
        writer.intValue(limit);
        return writer.bytes();
    }

    public static Query decodeQuery(byte[] payload) {
        Reader reader = new Reader(payload);
        reader.version();
        Query query = new Query(reader.longValue(), reader.text(), reader.longValue(), reader.intValue());
        reader.consumed();
        return query;
    }

    public static byte[] encodeList(List<CoreCancelAllAfterView> values) {
        Writer writer = new Writer();
        writer.intValue(VERSION);
        writer.intValue(values.size());
        for (CoreCancelAllAfterView value : values) {
            writer.longValue(value.userId());
            writer.text(value.symbolScope());
            writer.longValue(value.countdownMillis());
            writer.intValue(value.status().wireCode());
            writer.longValue(value.triggerAtEpochMillis());
            writer.longValue(value.updatedAtEpochMillis());
            writer.intValue(value.canceledOrders());
            writer.intValue(value.canceledTriggerOrders());
            writer.longValue(value.revision());
        }
        return writer.bytes();
    }

    public static List<CoreCancelAllAfterView> decodeList(byte[] payload) {
        Reader reader = new Reader(payload);
        reader.version();
        int count = reader.count();
        List<CoreCancelAllAfterView> values = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            values.add(new CoreCancelAllAfterView(reader.longValue(), reader.text(), reader.longValue(),
                    CoreCancelAllAfterStatus.fromWireCode(reader.intValue()), reader.longValue(), reader.longValue(),
                    reader.nonNegativeInt(), reader.nonNegativeInt(), reader.longValue()));
        }
        reader.consumed();
        return List.copyOf(values);
    }

    public record Query(long userId, String symbolScope, long dueAtEpochMillis, int limit) {
        public Query {
            if (userId < 0 || symbolScope == null || dueAtEpochMillis < 0 || limit < 1 || limit > 1000) {
                throw new IllegalArgumentException("invalid cancel-all-after query");
            }
        }
    }

    private static final class Writer {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        void intValue(int value) { output.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()); }
        void longValue(long value) { output.writeBytes(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()); }
        void text(String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_TEXT_BYTES) throw new IllegalArgumentException("text too long");
            intValue(bytes.length);
            output.writeBytes(bytes);
        }
        byte[] bytes() { return output.toByteArray(); }
    }

    private static final class Reader {
        private final ByteBuffer buffer;

        Reader(byte[] payload) {
            if (payload == null) throw new ProtocolException("payload required");
            buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        }
        void version() { if (intValue() != VERSION) throw new ProtocolException("unsupported cancel-all-after codec version"); }
        int intValue() { require(4); return buffer.getInt(); }
        int nonNegativeInt() { int value = intValue(); if (value < 0) throw new ProtocolException("negative value"); return value; }
        long longValue() { require(8); return buffer.getLong(); }
        int count() { int value = intValue(); if (value < 0 || value > 1_000_000) throw new ProtocolException("invalid count"); return value; }
        String text() {
            int length = count();
            if (length > MAX_TEXT_BYTES) throw new ProtocolException("text too long");
            require(length);
            byte[] bytes = new byte[length];
            buffer.get(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }
        void require(int count) { if (buffer.remaining() < count) throw new ProtocolException("truncated cancel-all-after payload"); }
        void consumed() { if (buffer.hasRemaining()) throw new ProtocolException("trailing cancel-all-after payload"); }
    }
}
