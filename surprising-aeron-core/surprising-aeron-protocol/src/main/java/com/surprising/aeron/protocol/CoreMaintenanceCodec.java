package com.surprising.aeron.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Bounded administrative protocol; quantities and identifiers remain integer units. */
public final class CoreMaintenanceCodec {
    private static final CoreInstrumentMaintenance.Mode[] MODES = CoreInstrumentMaintenance.Mode.values();
    private static final int STATE_BYTES = Long.BYTES * 2 + Integer.BYTES;

    private CoreMaintenanceCodec() { }
    public record Command(String instrumentId, long expectedTaskId, CoreInstrumentMaintenance state) {
        public Command { instrumentId = CoreMaintenanceCodec.instrumentId(instrumentId); if (expectedTaskId < 0 || state == null) throw new IllegalArgumentException("invalid maintenance command"); }
    }
    public record Query(String instrumentId, long afterUserId, int limit) {
        public Query { instrumentId = CoreMaintenanceCodec.instrumentId(instrumentId); if (afterUserId < 0 || limit < 1 || limit > 32) throw new IllegalArgumentException("invalid maintenance query"); }
    }
    public record Page(CoreInstrumentMaintenance state, List<Long> userIds, boolean hasMore) {
        public Page { userIds = List.copyOf(userIds); if (state == null || userIds.size() > 32) throw new IllegalArgumentException("invalid maintenance page"); }
    }
    public static byte[] encodeCommand(Command value) {
        var b = buffer(Integer.BYTES + value.instrumentId().length() + Long.BYTES + STATE_BYTES);
        text(b, value.instrumentId()); b.putLong(value.expectedTaskId()); state(b, value.state()); return b.array();
    }
    public static Command decodeCommand(byte[] bytes) {
        try { var b = read(bytes); var value = new Command(text(bytes, b), b.getLong(), state(b)); end(b); return value; }
        catch (java.nio.BufferUnderflowException e) { throw new IllegalArgumentException("truncated maintenance command",e); }
    }
    public static byte[] encodeQuery(Query value) {
        var b = buffer(Integer.BYTES + value.instrumentId().length() + Long.BYTES + Integer.BYTES);
        text(b, value.instrumentId()); b.putLong(value.afterUserId()).putInt(value.limit()); return b.array();
    }
    public static Query decodeQuery(byte[] bytes) {
        try { var b = read(bytes); var value = new Query(text(bytes, b), b.getLong(), b.getInt()); end(b); return value; }
        catch (java.nio.BufferUnderflowException e) { throw new IllegalArgumentException("truncated maintenance query",e); }
    }
    public static byte[] encodePage(Page value) {
        var b = buffer(STATE_BYTES + Integer.BYTES * 2 + value.userIds().size() * Long.BYTES);
        state(b, value.state()); b.putInt(value.hasMore() ? 1 : 0).putInt(value.userIds().size());
        value.userIds().forEach(b::putLong); return b.array();
    }
    public static Page decodePage(byte[] bytes) {
        try {
        var b = read(bytes); var state = state(b); int more = b.getInt(), count = b.getInt();
        if (more < 0 || more > 1 || count < 0 || count > 32) throw new IllegalArgumentException("invalid maintenance page");
        var ids = new java.util.ArrayList<Long>(count); for (int i = 0; i < count; i++) ids.add(b.getLong());
        end(b); return new Page(state, ids, more == 1);
        } catch (java.nio.BufferUnderflowException e) { throw new IllegalArgumentException("truncated maintenance page",e); }
    }
    private static String instrumentId(String value) {
        com.surprising.product.api.InstrumentIds.parse(value); return value;
    }
    private static ByteBuffer buffer(int size) { return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN); }
    private static ByteBuffer read(byte[] bytes) { if (bytes == null || bytes.length > 512) throw new IllegalArgumentException("invalid maintenance payload"); return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); }
    private static void text(ByteBuffer b, String value) {
        b.putInt(value.length());
        for (int i = 0; i < value.length(); i++) b.put((byte) value.charAt(i));
    }
    private static String text(byte[] raw, ByteBuffer b) {
        int n = b.getInt();
        if (n < 1 || n > 64 || n > b.remaining()) throw new IllegalArgumentException("invalid instrumentId length");
        String value = new String(raw, b.position(), n, StandardCharsets.US_ASCII);
        b.position(b.position() + n);
        return instrumentId(value);
    }
    private static void state(ByteBuffer b, CoreInstrumentMaintenance s) { b.putLong(s.taskId()).putInt(s.mode().ordinal()).putLong(s.settlementPriceTicks()); }
    private static CoreInstrumentMaintenance state(ByteBuffer b) { long id = b.getLong(); int mode = b.getInt(); if (mode < 0 || mode >= MODES.length) throw new IllegalArgumentException("invalid maintenance mode"); return new CoreInstrumentMaintenance(id, MODES[mode], b.getLong()); }
    private static void end(ByteBuffer b) { if (b.hasRemaining()) throw new IllegalArgumentException("trailing maintenance bytes"); }
}
