package com.surprising.aeron.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class CoreCancelAllAfterCodec {
    private static final int VERSION = 2;
    private static final int COMMAND_VERSION = 5;
    private static final int MAX_TEXT_BYTES = 64;

    private CoreCancelAllAfterCodec() {
    }

    public static byte[] encodeCommand(CoreCancelAllAfterCommand command) {
        ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES * 2 + Long.BYTES * 2
                + textLength(command.symbolScope())).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(COMMAND_VERSION).putInt(command.action().wireCode());
        CoreStateQueryCodec.putText(buffer, command.symbolScope(), true, MAX_TEXT_BYTES);
        buffer.putLong(command.updatedAtEpochMillis());
        switch (command.action()) {
            case SET -> buffer.putLong(command.countdownMillis());
            case CLAIM, RETRY, COMPLETE -> buffer.putLong(command.expectedRevision());
        }
        return buffer.array();
    }

    public static CoreCancelAllAfterCommand decodeCommand(byte[] payload) {
        Reader reader = new Reader(payload);
        if (reader.intValue() != COMMAND_VERSION) throw new ProtocolException("unsupported cancel-all-after command version");
        CoreCancelAllAfterAction action = CoreCancelAllAfterAction.fromWireCode(reader.intValue());
        String scope = reader.text();
        long now = reader.longValue();
        CoreCancelAllAfterCommand command = switch (action) {
            case SET -> CoreCancelAllAfterCommand.set(scope, reader.longValue(), now);
            case CLAIM -> CoreCancelAllAfterCommand.claim(scope, reader.longValue(), now);
            case RETRY -> CoreCancelAllAfterCommand.retry(scope, reader.longValue(), now);
            case COMPLETE -> CoreCancelAllAfterCommand.complete(scope, reader.longValue(), now);
        };
        reader.consumed();
        return command;
    }

    public static byte[] encodeQuery(long userId, String symbolScope, long dueAtEpochMillis, int limit) {
        String normalizedScope = symbolScope == null ? "" : symbolScope;
        int length = Integer.BYTES * 2 + Long.BYTES * 2 + textLength(normalizedScope);
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(VERSION).putLong(userId);
        CoreStateQueryCodec.putText(buffer, normalizedScope, true, MAX_TEXT_BYTES);
        buffer.putLong(dueAtEpochMillis).putInt(limit);
        return buffer.array();
    }

    public static Query decodeQuery(byte[] payload) {
        Reader reader = new Reader(payload);
        reader.version();
        Query query = new Query(reader.longValue(), reader.text(), reader.longValue(), reader.intValue());
        reader.consumed();
        return query;
    }

    public static byte[] encodeList(List<CoreCancelAllAfterView> values) {
        int length = Integer.BYTES * 2;
        for (CoreCancelAllAfterView value : values) {
            length = Math.addExact(length, Long.BYTES * 5 + Integer.BYTES + textLength(value.symbolScope()));
        }
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(VERSION).putInt(values.size());
        for (CoreCancelAllAfterView value : values) {
            buffer.putLong(value.userId());
            CoreStateQueryCodec.putText(buffer, value.symbolScope(), true, MAX_TEXT_BYTES);
            buffer.putLong(value.countdownMillis())
                    .putInt(value.status().wireCode())
                    .putLong(value.triggerAtEpochMillis())
                    .putLong(value.updatedAtEpochMillis())
                    .putLong(value.revision());
        }
        return buffer.array();
    }

    public static List<CoreCancelAllAfterView> decodeList(byte[] payload) {
        Reader reader = new Reader(payload);
        reader.version();
        int count = reader.count();
        List<CoreCancelAllAfterView> values = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            values.add(new CoreCancelAllAfterView(reader.longValue(), reader.text(), reader.longValue(),
                    CoreCancelAllAfterStatus.fromWireCode(reader.intValue()), reader.longValue(), reader.longValue(),
                    reader.longValue()));
        }
        reader.consumed();
        return List.copyOf(values);
    }

    private static int textLength(String value) {
        if (value == null) throw new IllegalArgumentException("text is required");
        int bytes = CoreStateQueryCodec.utf8Length(value);
        if (bytes > MAX_TEXT_BYTES) throw new IllegalArgumentException("text too long");
        return Integer.BYTES + bytes;
    }

    public record Query(long userId, String symbolScope, long dueAtEpochMillis, int limit) {
        public Query {
            if (userId < 0 || symbolScope == null || dueAtEpochMillis < 0 || limit < 1 || limit > 1000) {
                throw new IllegalArgumentException("invalid cancel-all-after query");
            }
        }
    }

    private static final class Reader {
        private final byte[] payload;
        private final ByteBuffer buffer;

        Reader(byte[] payload) {
            if (payload == null) throw new ProtocolException("payload required");
            this.payload = payload;
            this.buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        }
        void version() { if (intValue() != VERSION) throw new ProtocolException("unsupported cancel-all-after codec version"); }
        int intValue() { require(4); return buffer.getInt(); }
        long longValue() { require(8); return buffer.getLong(); }
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
        void require(int count) { if (buffer.remaining() < count) throw new ProtocolException("truncated cancel-all-after payload"); }
        void consumed() { if (buffer.hasRemaining()) throw new ProtocolException("trailing cancel-all-after payload"); }
    }
}
