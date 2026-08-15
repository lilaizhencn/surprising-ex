package com.surprising.aeron.protocol;

import com.surprising.product.api.ProductLine;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class CoreTriggerOrderCodec {
    private static final int VERSION = 1;
    private static final int MAX_TEXT_BYTES = 128;
    private CoreTriggerOrderCodec() { }

    public static byte[] encodeState(CoreTriggerOrderStateView state) {
        Writer writer = new Writer();
        writer.intValue(VERSION);
        writer.longValue(state.triggerOrderId());
        writer.intValue(ProductLineWireCode.encode(state.productLine()));
        writer.longValue(state.userId());
        writer.text(state.clientTriggerOrderId()); writer.text(state.ocoGroupId()); writer.text(state.symbol());
        writer.intValue(state.side().wireCode()); writer.intValue(state.triggerType().ordinal());
        writer.intValue(state.triggerCondition().ordinal()); writer.longValue(state.triggerPriceTicks());
        writer.longValue(state.activationPriceTicks()); writer.longValue(state.callbackRatePpm());
        writer.longValue(state.highestPriceTicks()); writer.longValue(state.lowestPriceTicks());
        writer.longValue(state.activatedAtEpochMillis()); writer.intValue(state.orderType().wireCode());
        writer.intValue(state.timeInForce().wireCode()); writer.longValue(state.priceTicks());
        writer.longValue(state.quantitySteps()); writer.intValue(state.marginMode().wireCode());
        writer.intValue(state.positionSide().wireCode()); writer.intValue(state.status().ordinal());
        writer.longValue(state.placedOrderId()); writer.longValue(state.triggerSequence());
        writer.longValue(state.triggeredPriceTicks()); writer.text(state.rejectReason()); writer.text(state.traceId());
        writer.longValue(state.expiresAtEpochMillis()); writer.longValue(state.triggeredAtEpochMillis());
        writer.longValue(state.createdAtEpochMillis()); writer.longValue(state.updatedAtEpochMillis());
        writer.longValue(state.revision());
        return writer.bytes();
    }

    public static CoreTriggerOrderStateView decodeState(byte[] encoded) {
        Reader reader = new Reader(encoded); reader.version();
        CoreTriggerOrderStateView result = new CoreTriggerOrderStateView(
                reader.positive(), ProductLineWireCode.decode(reader.intValue()), reader.positive(),
                reader.text(), reader.text(), reader.text(), CoreOrderSide.fromWireCode(reader.intValue()),
                CoreTriggerOrderType.values()[reader.enumIndex(CoreTriggerOrderType.values().length)],
                CoreTriggerCondition.values()[reader.enumIndex(CoreTriggerCondition.values().length)], reader.nonNegative(),
                reader.nonNegative(), reader.nonNegative(), reader.nonNegative(), reader.nonNegative(), reader.nonNegative(),
                CoreOrderType.fromWireCode(reader.intValue()), CoreTimeInForce.fromWireCode(reader.intValue()),
                reader.nonNegative(), reader.positive(), CoreMarginMode.fromWireCode(reader.intValue()),
                CorePositionSide.fromWireCode(reader.intValue()),
                CoreTriggerOrderStatus.values()[reader.enumIndex(CoreTriggerOrderStatus.values().length)], reader.nonNegative(),
                reader.nonNegative(), reader.nonNegative(), reader.text(), reader.text(), reader.nonNegative(),
                reader.nonNegative(), reader.nonNegative(), reader.nonNegative(), reader.nonNegative());
        reader.consumed(); return result;
    }

    public static byte[] encodeList(List<CoreTriggerOrderStateView> values) {
        Writer writer = new Writer(); writer.intValue(VERSION); writer.intValue(values.size());
        values.forEach(value -> writer.bytes(encodeState(value))); return writer.bytes();
    }

    public static List<CoreTriggerOrderStateView> decodeList(byte[] encoded) {
        Reader reader = new Reader(encoded); reader.version(); List<CoreTriggerOrderStateView> result = new ArrayList<>();
        int count = reader.count();
        for (int i = 0; i < count; i++) result.add(decodeState(reader.bytes()));
        reader.consumed(); return List.copyOf(result);
    }

    public static byte[] encodeQuery(CoreTriggerOrderQuery query) {
        Writer writer = new Writer(); writer.intValue(VERSION); writer.longValue(query.triggerOrderId());
        writer.text(query.symbol()); writer.longValue(query.beforeTriggerOrderId()); writer.intValue(query.limit());
        return writer.bytes();
    }

    public static CoreTriggerOrderQuery decodeQuery(byte[] encoded) {
        Reader reader = new Reader(encoded); reader.version();
        CoreTriggerOrderQuery result = new CoreTriggerOrderQuery(reader.nonNegative(), reader.text(), reader.nonNegative(), reader.intValue());
        reader.consumed(); return result;
    }

    public static byte[] encodeId(long triggerOrderId) {
        Writer writer = new Writer(); writer.intValue(VERSION); writer.longValue(triggerOrderId); return writer.bytes();
    }

    public static long decodeId(byte[] encoded) {
        Reader reader = new Reader(encoded); reader.version(); long value = reader.positive(); reader.consumed(); return value;
    }

    public static byte[] encodeClaim(long triggerOrderId, long triggerSequence, long triggeredPriceTicks,
                                     long triggeredAtEpochMillis) {
        Writer writer = new Writer(); writer.intValue(VERSION); writer.longValue(triggerOrderId);
        writer.longValue(triggerSequence); writer.longValue(triggeredPriceTicks); writer.longValue(triggeredAtEpochMillis);
        return writer.bytes();
    }

    public static long[] decodeClaim(byte[] encoded) {
        Reader reader = new Reader(encoded); reader.version(); long[] result = new long[] {
                reader.positive(), reader.positive(), reader.positive(), reader.positive()}; reader.consumed(); return result;
    }

    public static byte[] encodeComplete(long triggerOrderId, boolean success, long placedOrderId, String rejectReason,
                                        long completedAtEpochMillis) {
        Writer writer = new Writer(); writer.intValue(VERSION); writer.longValue(triggerOrderId);
        writer.byteValue(success ? 1 : 0); writer.longValue(placedOrderId);
        writer.text(rejectReason == null ? "" : rejectReason); writer.longValue(completedAtEpochMillis);
        return writer.bytes();
    }

    public static long[] decodeComplete(byte[] encoded) {
        Reader reader = new Reader(encoded); reader.version(); long id = reader.positive();
        long success = reader.byteValue() == 1 ? 1 : 0; long placed = reader.nonNegative(); reader.text();
        long completed = reader.nonNegative(); reader.consumed(); return new long[] {id, success, placed, completed};
    }

    public static byte[] encodeTrailing(long triggerOrderId, long highestPriceTicks, long lowestPriceTicks,
                                        long activatedAtEpochMillis) {
        Writer writer = new Writer(); writer.intValue(VERSION); writer.longValue(triggerOrderId);
        writer.longValue(highestPriceTicks); writer.longValue(lowestPriceTicks); writer.longValue(activatedAtEpochMillis);
        return writer.bytes();
    }

    public static long[] decodeTrailing(byte[] encoded) {
        Reader reader = new Reader(encoded); reader.version(); long[] result = new long[] {
                reader.positive(), reader.nonNegative(), reader.nonNegative(), reader.nonNegative()}; reader.consumed(); return result;
    }

    public static byte[] encodeLifecycle(long triggerOrderId, long timestampEpochMillis) {
        Writer writer = new Writer(); writer.intValue(VERSION); writer.longValue(triggerOrderId);
        writer.longValue(timestampEpochMillis); return writer.bytes();
    }

    public static long[] decodeLifecycle(byte[] encoded) {
        Reader reader = new Reader(encoded); reader.version(); long[] result = new long[] {
                reader.positive(), reader.positive()}; reader.consumed(); return result;
    }

    private static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        void intValue(int value) { for (int i = 0; i < 4; i++) out.write(value >>> (i * 8)); }
        void byteValue(int value) { out.write(value); }
        void longValue(long value) { for (int i = 0; i < 8; i++) out.write((int) (value >>> (i * 8))); }
        void text(String value) { byte[] bytes = value.getBytes(StandardCharsets.UTF_8); if (bytes.length > MAX_TEXT_BYTES) throw new IllegalArgumentException("trigger text too long"); intValue(bytes.length); out.writeBytes(bytes); }
        void bytes(byte[] value) { intValue(value.length); out.writeBytes(value); }
        byte[] bytes() { return out.toByteArray(); }
    }
    private static final class Reader {
        private final byte[] bytes; private int position;
        Reader(byte[] bytes) { this.bytes = bytes == null ? new byte[0] : bytes; }
        int intValue() { require(4); int value = 0; for (int i=0;i<4;i++) value |= (bytes[position++] & 0xff) << (i*8); return value; }
        int byteValue() { require(1); return bytes[position++] & 0xff; }
        long longValue() { require(8); long value = 0; for (int i=0;i<8;i++) value |= (long)(bytes[position++] & 0xff) << (i*8); return value; }
        void version() { if (intValue() != VERSION) throw new ProtocolException("unsupported trigger codec version"); }
        long positive() { long v=longValue(); if(v<=0) throw new ProtocolException("invalid positive trigger value"); return v; }
        long nonNegative() { long v=longValue(); if(v<0) throw new ProtocolException("invalid trigger value"); return v; }
        String text() { int size=intValue(); if(size<0 || size>MAX_TEXT_BYTES) throw new ProtocolException("invalid trigger text"); require(size); String v=new String(bytes, position, size, StandardCharsets.UTF_8); position+=size; return v; }
        int enumIndex(int max) { int v=intValue(); if(v<0 || v>=max) throw new ProtocolException("invalid trigger enum"); return v; }
        int count() { int v=intValue(); if(v<0 || v>1001) throw new ProtocolException("invalid trigger count"); return v; }
        byte[] bytes() { int size=intValue(); if(size<0 || size>1_000_000) throw new ProtocolException("invalid trigger payload"); require(size); byte[] result=java.util.Arrays.copyOfRange(bytes, position, position+size); position+=size; return result; }
        void consumed() { if(position != bytes.length) throw new ProtocolException("trailing trigger bytes"); }
        void require(int size) { if(size<0 || position > bytes.length-size) throw new ProtocolException("truncated trigger payload"); }
    }
}
