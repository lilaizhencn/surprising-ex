package com.surprising.aeron.protocol;

import com.surprising.product.api.ProductLine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class CoreTriggerOrderCodec {
    private static final int VERSION = 1;
    private static final int QUERY_VERSION = 3;
    private static final int STATE_VERSION = 3;
    private static final int MAX_TEXT_BYTES = 128;
    private static final CoreTriggerOrderType[] TYPES = CoreTriggerOrderType.values();
    private static final CoreTriggerCondition[] CONDITIONS = CoreTriggerCondition.values();
    private static final CoreTriggerOrderStatus[] STATUSES = CoreTriggerOrderStatus.values();
    private static final CoreTriggerPriceSource[] PRICE_SOURCES = CoreTriggerPriceSource.values();

    private CoreTriggerOrderCodec() { }

    public static byte[] encodeState(CoreTriggerOrderStateView state) {
        int length = encodedStateLength(state);
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        writeState(buffer, state);
        return buffer.array();
    }

    /** Encode a one-element response without allocating the temporary singleton List. */
    public static byte[] encodeSingle(CoreTriggerOrderStateView state) {
        int stateLength = encodedStateLength(state);
        ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES * 3 + stateLength).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(VERSION).putInt(1).putInt(stateLength);
        writeState(buffer, state);
        return buffer.array();
    }

    private static void writeState(ByteBuffer buffer, CoreTriggerOrderStateView state) {
        buffer.putInt(STATE_VERSION)
                .putLong(state.triggerOrderId())
                .putInt(ProductLineWireCode.encode(state.productLine()))
                .putLong(state.userId());
        CoreStateQueryCodec.putText(buffer, state.clientTriggerOrderId(), true, MAX_TEXT_BYTES);
        CoreStateQueryCodec.putText(buffer, state.ocoGroupId(), true, MAX_TEXT_BYTES);
        CoreStateQueryCodec.putText(buffer, state.instrumentId(), true, MAX_TEXT_BYTES);
        buffer.putInt(state.side().wireCode()).putInt(state.triggerType().ordinal())
                .putInt(state.triggerCondition().ordinal()).putLong(state.triggerPriceTicks())
                .putLong(state.activationPriceTicks()).putLong(state.callbackRatePpm())
                .putLong(state.highestPriceTicks()).putLong(state.lowestPriceTicks())
                .putLong(state.activatedAtEpochMillis()).putInt(state.orderType().wireCode())
                .putInt(state.timeInForce().wireCode()).putLong(state.priceTicks())
                .putLong(state.quantitySteps()).putInt(state.marginMode().wireCode())
                .putInt(state.positionSide().wireCode()).putInt(state.status().ordinal())
                .putLong(state.placedOrderId()).putLong(state.triggerSequence())
                .putLong(state.triggeredPriceTicks());
        CoreStateQueryCodec.putText(buffer, state.rejectReason(), true, MAX_TEXT_BYTES);
        buffer.putLong(state.expiresAtEpochMillis()).putLong(state.triggeredAtEpochMillis())
                .putLong(state.createdAtEpochMillis()).putLong(state.updatedAtEpochMillis())
                .putLong(state.revision())
                .putLong(state.makerFeeRatePpm())
                .putLong(state.takerFeeRatePpm())
                .putInt(state.priceSource().ordinal());
    }

    public static int encodedStateLength(CoreTriggerOrderStateView state) {
        if (state == null) throw new IllegalArgumentException("trigger state is required");
        long length = Integer.BYTES + Long.BYTES + Integer.BYTES + Long.BYTES;
        length = Math.addExact(length, textLength(state.clientTriggerOrderId())
                + textLength(state.ocoGroupId()) + textLength(state.instrumentId()));
        length = Math.addExact(length, Integer.BYTES * 3L + Long.BYTES * 6L);
        length = Math.addExact(length, Integer.BYTES * 2L + Long.BYTES * 2L);
        length = Math.addExact(length, Integer.BYTES * 3L + Long.BYTES * 3L);
        length = Math.addExact(length, textLength(state.rejectReason()));
        length = Math.addExact(length, Long.BYTES * 7L + Integer.BYTES);
        return Math.toIntExact(length);
    }

    private static int textLength(String value) {
        if (value == null) throw new IllegalArgumentException("trigger text is required");
        int bytes = CoreStateQueryCodec.utf8Length(value);
        if (bytes > MAX_TEXT_BYTES) throw new IllegalArgumentException("trigger text too long");
        return Math.addExact(Integer.BYTES, bytes);
    }

    public static CoreTriggerOrderStateView decodeState(byte[] encoded) {
        Reader reader = new Reader(encoded, 0, encoded == null ? 0 : encoded.length);
        return decodeState(reader);
    }

    private static CoreTriggerOrderStateView decodeState(Reader reader) {
        int version = reader.stateVersion();
        CoreTriggerOrderStateView result = new CoreTriggerOrderStateView(
                reader.positive(), ProductLineWireCode.decode(reader.intValue()), reader.positive(),
                reader.text(), reader.text(), reader.text(), CoreOrderSide.fromWireCode(reader.intValue()),
                TYPES[reader.enumIndex(TYPES.length)],
                CONDITIONS[reader.enumIndex(CONDITIONS.length)], reader.nonNegative(),
                reader.nonNegative(), reader.nonNegative(), reader.nonNegative(), reader.nonNegative(), reader.nonNegative(),
                CoreOrderType.fromWireCode(reader.intValue()), CoreTimeInForce.fromWireCode(reader.intValue()),
                reader.nonNegative(), reader.positive(), CoreMarginMode.fromWireCode(reader.intValue()),
                CorePositionSide.fromWireCode(reader.intValue()),
                STATUSES[reader.enumIndex(STATUSES.length)], reader.nonNegative(),
                reader.nonNegative(), reader.nonNegative(), reader.text(), reader.nonNegative(),
                reader.nonNegative(), reader.nonNegative(), reader.nonNegative(), reader.nonNegative(),
                reader.longValue(), reader.longValue(),
                version < 3 ? CoreTriggerPriceSource.MARK
                        : PRICE_SOURCES[reader.enumIndex(PRICE_SOURCES.length)]);
        reader.consumed(); return result;
    }

    public static byte[] encodeList(List<CoreTriggerOrderStateView> values) {
        int length = Integer.BYTES * 2;
        for (CoreTriggerOrderStateView value : values) {
            length = Math.addExact(length, Math.addExact(Integer.BYTES, encodedStateLength(value)));
        }
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(VERSION).putInt(values.size());
        for (CoreTriggerOrderStateView value : values) {
            buffer.putInt(encodedStateLength(value));
            writeState(buffer, value);
        }
        return buffer.array();
    }

    public static List<CoreTriggerOrderStateView> decodeList(byte[] encoded) {
        Reader reader = new Reader(encoded, 0, encoded == null ? 0 : encoded.length);
        reader.version();
        int count = reader.count();
        List<CoreTriggerOrderStateView> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            result.add(decodeState(reader.slice()));
        }
        reader.consumed(); return List.copyOf(result);
    }

    public static byte[] encodeQuery(CoreTriggerOrderQuery query) {
        int length = Integer.BYTES * 3 + Long.BYTES * 3 + textLength(query.instrumentId());
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(QUERY_VERSION).putLong(query.triggerOrderId());
        CoreStateQueryCodec.putText(buffer, query.instrumentId(), true, MAX_TEXT_BYTES);
        buffer.putLong(query.beforeTriggerOrderId()).putInt(query.limit())
                .putInt(query.status() == null ? 0 : query.status().ordinal() + 1)
                .putLong(query.expiresBeforeEpochMillis());
        return buffer.array();
    }

    public static CoreTriggerOrderQuery decodeQuery(byte[] encoded) {
        Reader reader = new Reader(encoded, 0, encoded == null ? 0 : encoded.length); int version = reader.queryVersion();
        CoreTriggerOrderQuery result = new CoreTriggerOrderQuery(reader.nonNegative(), reader.text(), reader.nonNegative(), reader.intValue(),
                version == 1 ? null : reader.status(), version < QUERY_VERSION ? 0 : reader.nonNegative());
        reader.consumed(); return result;
    }

    public static byte[] encodeId(long triggerOrderId) {
        return ByteBuffer.allocate(Integer.BYTES + Long.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(VERSION)
                .putLong(triggerOrderId)
                .array();
    }

    public static long decodeId(byte[] encoded) {
        Reader reader = new Reader(encoded, 0, encoded == null ? 0 : encoded.length); reader.version(); long value = reader.positive(); reader.consumed(); return value;
    }

    public static byte[] encodeClaim(long triggerOrderId, long triggerSequence, long triggeredPriceTicks,
                                     long triggeredAtEpochMillis) {
        return ByteBuffer.allocate(Integer.BYTES + Long.BYTES * 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(VERSION)
                .putLong(triggerOrderId)
                .putLong(triggerSequence)
                .putLong(triggeredPriceTicks)
                .putLong(triggeredAtEpochMillis)
                .array();
    }

    public static long[] decodeClaim(byte[] encoded) {
        Reader reader = new Reader(encoded, 0, encoded == null ? 0 : encoded.length); reader.version(); long[] result = new long[] {
                reader.positive(), reader.positive(), reader.positive(), reader.positive()}; reader.consumed(); return result;
    }

    public static byte[] encodeExecute(long triggerOrderId, long triggerSequence, long triggeredPriceTicks,
                                       long triggeredAtEpochMillis) {
        return encodeClaim(triggerOrderId, triggerSequence, triggeredPriceTicks, triggeredAtEpochMillis);
    }

    public static long[] decodeExecute(byte[] encoded) {
        return decodeClaim(encoded);
    }

    public static byte[] encodeComplete(long triggerOrderId, boolean success, long placedOrderId, String rejectReason,
                                        long completedAtEpochMillis) {
        String normalizedReason = rejectReason == null ? "" : rejectReason;
        int length = Integer.BYTES + Long.BYTES * 3 + Byte.BYTES + textLength(normalizedReason);
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(VERSION).putLong(triggerOrderId)
                .put((byte) (success ? 1 : 0)).putLong(placedOrderId);
        CoreStateQueryCodec.putText(buffer, normalizedReason, true, MAX_TEXT_BYTES);
        buffer.putLong(completedAtEpochMillis);
        return buffer.array();
    }

    public static long[] decodeComplete(byte[] encoded) {
        Reader reader = new Reader(encoded, 0, encoded == null ? 0 : encoded.length); reader.version(); long id = reader.positive();
        long success = reader.byteValue() == 1 ? 1 : 0; long placed = reader.nonNegative(); reader.text();
        long completed = reader.nonNegative(); reader.consumed(); return new long[] {id, success, placed, completed};
    }

    public static byte[] encodeTrailing(long triggerOrderId, long highestPriceTicks, long lowestPriceTicks,
                                        long activatedAtEpochMillis) {
        return ByteBuffer.allocate(Integer.BYTES + Long.BYTES * 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(VERSION)
                .putLong(triggerOrderId)
                .putLong(highestPriceTicks)
                .putLong(lowestPriceTicks)
                .putLong(activatedAtEpochMillis)
                .array();
    }

    public static long[] decodeTrailing(byte[] encoded) {
        Reader reader = new Reader(encoded, 0, encoded == null ? 0 : encoded.length); reader.version(); long[] result = new long[] {
                reader.positive(), reader.nonNegative(), reader.nonNegative(), reader.nonNegative()}; reader.consumed(); return result;
    }

    public static byte[] encodeLifecycle(long triggerOrderId, long timestampEpochMillis) {
        return ByteBuffer.allocate(Integer.BYTES + Long.BYTES * 2)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(VERSION)
                .putLong(triggerOrderId)
                .putLong(timestampEpochMillis)
                .array();
    }

    public static long[] decodeLifecycle(byte[] encoded) {
        Reader reader = new Reader(encoded, 0, encoded == null ? 0 : encoded.length); reader.version(); long[] result = new long[] {
                reader.positive(), reader.positive()}; reader.consumed(); return result;
    }

    private static final class Reader {
        private final byte[] bytes;
        private final int limit;
        private int position;
        Reader(byte[] bytes, int offset, int length) {
            this.bytes = bytes == null ? new byte[0] : bytes;
            this.position = offset;
            this.limit = offset + length;
        }
        int intValue() { require(4); int value = 0; for (int i=0;i<4;i++) value |= (bytes[position++] & 0xff) << (i*8); return value; }
        int byteValue() { require(1); return bytes[position++] & 0xff; }
        long longValue() { require(8); long value = 0; for (int i=0;i<8;i++) value |= (long)(bytes[position++] & 0xff) << (i*8); return value; }
        int version() {
            int value = intValue();
            if (value != VERSION) throw new ProtocolException("unsupported trigger codec version");
            return value;
        }
        int queryVersion() {
            int value = intValue();
            if (value < VERSION || value > QUERY_VERSION) throw new ProtocolException("unsupported trigger query codec version");
            return value;
        }
        int stateVersion() {
            int value = intValue();
            if (value < 2 || value > STATE_VERSION) {
                throw new ProtocolException("unsupported trigger state codec version");
            }
            return value;
        }
        long positive() { long v=longValue(); if(v<=0) throw new ProtocolException("invalid positive trigger value"); return v; }
        long nonNegative() { long v=longValue(); if(v<0) throw new ProtocolException("invalid trigger value"); return v; }
        String text() { int size=intValue(); if(size<0 || size>MAX_TEXT_BYTES) throw new ProtocolException("invalid trigger text"); require(size); String v = size == 0 ? "" : new String(bytes, position, size, StandardCharsets.UTF_8); position+=size; return v; }
        int enumIndex(int max) { int v=intValue(); if(v<0 || v>=max) throw new ProtocolException("invalid trigger enum"); return v; }
        CoreTriggerOrderStatus status() { int v = intValue(); if (v == 0) return null; if (v > STATUSES.length) throw new ProtocolException("invalid trigger status"); return STATUSES[v - 1]; }
        int count() { int v=intValue(); if(v<0 || v>1001) throw new ProtocolException("invalid trigger count"); return v; }
        Reader slice() {
            int size = intValue();
            if (size < 0 || size > 1_000_000) throw new ProtocolException("invalid trigger payload");
            require(size);
            Reader child = new Reader(bytes, position, size);
            position += size;
            return child;
        }
        void consumed() { if(position != limit) throw new ProtocolException("trailing trigger bytes"); }
        void require(int size) { if(size<0 || position > limit-size) throw new ProtocolException("truncated trigger payload"); }
    }
}
