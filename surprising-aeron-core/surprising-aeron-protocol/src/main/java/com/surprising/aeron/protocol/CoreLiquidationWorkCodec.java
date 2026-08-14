package com.surprising.aeron.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class CoreLiquidationWorkCodec {

    private static final int VERSION = 1;
    private static final int MAX_ACTIONS = 1_000;
    private static final int MAX_TEXT_BYTES = 64;

    private CoreLiquidationWorkCodec() {
    }

    public static byte[] encodeQuery(int limit) {
        if (limit < 1 || limit > MAX_ACTIONS) throw new IllegalArgumentException("invalid liquidation work limit");
        Writer writer = new Writer();
        writer.intValue(VERSION);
        writer.intValue(limit);
        return writer.toByteArray();
    }

    public static int decodeQuery(byte[] encoded) {
        Reader reader = new Reader(encoded);
        reader.version();
        int limit = reader.intValue();
        if (limit < 1 || limit > MAX_ACTIONS) throw new ProtocolException("invalid liquidation work limit");
        reader.requireConsumed();
        return limit;
    }

    public static byte[] encodeWork(CoreLiquidationWorkView work) {
        if (work.actions().size() > MAX_ACTIONS) throw new IllegalArgumentException("too many liquidation actions");
        Writer writer = new Writer();
        writer.intValue(VERSION);
        writer.byteValue(work.riskScanPending() ? 1 : 0);
        writer.intValue(work.actions().size());
        for (CoreLiquidationActionView action : work.actions()) {
            writer.longValue(action.liquidationId());
            writer.longValue(action.userId());
            writer.text(action.symbol());
            writer.intValue(action.marginMode().wireCode());
            writer.intValue(action.positionSide().wireCode());
            writer.longValue(action.instrumentVersion());
            writer.longValue(action.triggerPriceSequence());
            writer.longValue(action.signedQuantitySteps());
            writer.longValue(action.closeQuantitySteps());
            writer.longValue(action.markPriceTicks());
        }
        return writer.toByteArray();
    }

    public static CoreLiquidationWorkView decodeWork(byte[] encoded) {
        Reader reader = new Reader(encoded);
        reader.version();
        boolean pending = reader.booleanValue();
        int count = reader.intValue();
        if (count < 0 || count > MAX_ACTIONS) throw new ProtocolException("invalid liquidation action count");
        List<CoreLiquidationActionView> actions = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            actions.add(new CoreLiquidationActionView(reader.positiveLong("liquidationId"),
                    reader.positiveLong("userId"), reader.text(),
                    CoreMarginMode.fromWireCode(reader.intValue()),
                    CorePositionSide.fromWireCode(reader.intValue()),
                    reader.positiveLong("instrumentVersion"), reader.positiveLong("triggerPriceSequence"),
                    reader.nonZeroLong("signedQuantitySteps"), reader.positiveLong("closeQuantitySteps"),
                    reader.positiveLong("markPriceTicks")));
        }
        reader.requireConsumed();
        return new CoreLiquidationWorkView(pending, actions);
    }

    private static final class Writer {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        void byteValue(int value) { output.write(value); }
        void intValue(int value) {
            for (int shift = 0; shift < Integer.SIZE; shift += Byte.SIZE) output.write(value >>> shift);
        }
        void longValue(long value) {
            for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) output.write((int) (value >>> shift));
        }
        void text(String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            if (bytes.length < 1 || bytes.length > MAX_TEXT_BYTES) throw new IllegalArgumentException("invalid text");
            intValue(bytes.length);
            output.writeBytes(bytes);
        }
        byte[] toByteArray() { return output.toByteArray(); }
    }

    private static final class Reader {
        private final byte[] input;
        private int offset;
        Reader(byte[] input) {
            if (input == null) throw new ProtocolException("liquidation work is required");
            this.input = input;
        }
        void version() {
            int version = intValue();
            if (version != VERSION) throw new ProtocolException("unsupported liquidation work version: " + version);
        }
        int byteValue() { require(1); return Byte.toUnsignedInt(input[offset++]); }
        int intValue() {
            require(Integer.BYTES);
            int value = 0;
            for (int shift = 0; shift < Integer.SIZE; shift += Byte.SIZE) {
                value |= Byte.toUnsignedInt(input[offset++]) << shift;
            }
            return value;
        }
        long longValue() {
            require(Long.BYTES);
            long value = 0;
            for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {
                value |= (long) Byte.toUnsignedInt(input[offset++]) << shift;
            }
            return value;
        }
        long positiveLong(String field) {
            long value = longValue();
            if (value <= 0) throw new ProtocolException(field + " must be positive");
            return value;
        }
        long nonZeroLong(String field) {
            long value = longValue();
            if (value == 0) throw new ProtocolException(field + " must be non-zero");
            return value;
        }
        String text() {
            int length = intValue();
            if (length < 1 || length > MAX_TEXT_BYTES) throw new ProtocolException("invalid text length");
            require(length);
            String value = new String(input, offset, length, StandardCharsets.UTF_8);
            offset += length;
            return value;
        }
        boolean booleanValue() {
            int value = byteValue();
            if (value != 0 && value != 1) throw new ProtocolException("invalid boolean");
            return value == 1;
        }
        void requireConsumed() {
            if (offset != input.length) throw new ProtocolException("trailing liquidation work bytes");
        }
        private void require(int length) {
            if (offset > input.length - length) throw new ProtocolException("truncated liquidation work");
        }
    }
}
