package com.surprising.aeron.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Bounded administrative protocol; quantities and identifiers remain integer units. */
public final class CoreMaintenanceCodec {
    private CoreMaintenanceCodec() { }
    public record Command(String symbol, long expectedTaskId, CoreInstrumentMaintenance state) {
        public Command { symbol = CoreMaintenanceCodec.symbol(symbol); if (expectedTaskId < 0 || state == null) throw new IllegalArgumentException("invalid maintenance command"); }
    }
    public record Query(String symbol, long afterUserId, int limit) {
        public Query { symbol = CoreMaintenanceCodec.symbol(symbol); if (afterUserId < 0 || limit < 1 || limit > 32) throw new IllegalArgumentException("invalid maintenance query"); }
    }
    public record Page(CoreInstrumentMaintenance state, long instrumentChangeId, List<Long> userIds, boolean hasMore) {
        public Page { userIds = List.copyOf(userIds); if (state == null || instrumentChangeId <= 0 || userIds.size() > 32) throw new IllegalArgumentException("invalid maintenance page"); }
    }
    public static byte[] encodeCommand(Command value) {
        var b = buffer(128); text(b, value.symbol()); b.putLong(value.expectedTaskId()); state(b, value.state()); return bytes(b);
    }
    public static Command decodeCommand(byte[] bytes) {
        try { var b = read(bytes); var value = new Command(text(b), b.getLong(), state(b)); end(b); return value; }
        catch (java.nio.BufferUnderflowException e) { throw new IllegalArgumentException("truncated maintenance command",e); }
    }
    public static byte[] encodeQuery(Query value) {
        var b = buffer(96); text(b, value.symbol()); b.putLong(value.afterUserId()).putInt(value.limit()); return bytes(b);
    }
    public static Query decodeQuery(byte[] bytes) {
        try { var b = read(bytes); var value = new Query(text(b), b.getLong(), b.getInt()); end(b); return value; }
        catch (java.nio.BufferUnderflowException e) { throw new IllegalArgumentException("truncated maintenance query",e); }
    }
    public static byte[] encodePage(Page value) {
        var b = buffer(512); state(b, value.state()); b.putLong(value.instrumentChangeId()).putInt(value.hasMore() ? 1 : 0).putInt(value.userIds().size());
        value.userIds().forEach(b::putLong); return bytes(b);
    }
    public static Page decodePage(byte[] bytes) {
        try {
        var b = read(bytes); var state = state(b); long version = b.getLong(); int more = b.getInt(), count = b.getInt();
        if (more < 0 || more > 1 || count < 0 || count > 32) throw new IllegalArgumentException("invalid maintenance page");
        var ids = new java.util.ArrayList<Long>(count); for (int i = 0; i < count; i++) ids.add(b.getLong());
        end(b); return new Page(state, version, ids, more == 1);
        } catch (java.nio.BufferUnderflowException e) { throw new IllegalArgumentException("truncated maintenance page",e); }
    }
    private static String symbol(String value) {
        if (value == null || !value.matches("[A-Z0-9][A-Z0-9_.-]{0,63}")) throw new IllegalArgumentException("invalid symbol"); return value;
    }
    private static ByteBuffer buffer(int size) { return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN); }
    private static ByteBuffer read(byte[] bytes) { if (bytes == null || bytes.length > 512) throw new IllegalArgumentException("invalid maintenance payload"); return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); }
    private static byte[] bytes(ByteBuffer b) { return java.util.Arrays.copyOf(b.array(), b.position()); }
    private static void text(ByteBuffer b, String value) { byte[] bytes = value.getBytes(StandardCharsets.US_ASCII); b.putInt(bytes.length).put(bytes); }
    private static String text(ByteBuffer b) { int n = b.getInt(); if (n < 1 || n > 64 || n > b.remaining()) throw new IllegalArgumentException("invalid symbol length"); byte[] bytes = new byte[n]; b.get(bytes); return symbol(new String(bytes, StandardCharsets.US_ASCII)); }
    private static void state(ByteBuffer b, CoreInstrumentMaintenance s) { b.putLong(s.taskId()).putInt(s.mode().ordinal()).putLong(s.settlementPriceTicks()); }
    private static CoreInstrumentMaintenance state(ByteBuffer b) { long id = b.getLong(); int mode = b.getInt(); if (mode < 0 || mode >= CoreInstrumentMaintenance.Mode.values().length) throw new IllegalArgumentException("invalid maintenance mode"); return new CoreInstrumentMaintenance(id, CoreInstrumentMaintenance.Mode.values()[mode], b.getLong()); }
    private static void end(ByteBuffer b) { if (b.hasRemaining()) throw new IllegalArgumentException("trailing maintenance bytes"); }
}
