package com.surprising.aeron.protocol;

import com.surprising.product.api.ProductLine;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class CoreLiquidationWorkCodec {

    private static final int VERSION = 4;
    private static final int MAX_ACTIONS = 1_000;
    private static final int MAX_TEXT_BYTES = 64;

    private CoreLiquidationWorkCodec() {
    }

    public static byte[] encodeQuery(ProductLine productLine, CoreLiquidationWorkView.Purpose purpose,
                                     long afterLiquidationId, int maxItems, int maxBytes) {
        return encodeQuery(new CoreLiquidationWorkView.Query(productLine, purpose, afterLiquidationId,
                maxItems, maxBytes));
    }

    public static byte[] encodeQuery(CoreLiquidationWorkView.Query query) {
        Writer writer = new Writer();
        writer.intValue(VERSION);
        writer.byteValue(ProductLineWireCode.encode(query.productLine()));
        writer.byteValue(query.purpose().ordinal());
        writer.longValue(query.afterLiquidationId());
        writer.intValue(query.maxItems());
        writer.intValue(query.maxBytes());
        return writer.toByteArray();
    }

    public static CoreLiquidationWorkView.Query decodeQuery(byte[] encoded) {
        Reader reader = new Reader(encoded);
        reader.version();
        ProductLine productLine = ProductLineWireCode.decode(reader.byteValue());
        int purposeCode = reader.byteValue();
        if (purposeCode >= CoreLiquidationWorkView.Purpose.values().length) {
            throw new ProtocolException("invalid liquidation work purpose");
        }
        CoreLiquidationWorkView.Purpose purpose = CoreLiquidationWorkView.Purpose.values()[purposeCode];
        long cursor = reader.nonNegativeLong("afterLiquidationId");
        int maxItems = reader.intValue();
        int maxBytes = reader.intValue();
        reader.requireConsumed();
        try {
            return new CoreLiquidationWorkView.Query(productLine, purpose, cursor, maxItems, maxBytes);
        } catch (IllegalArgumentException exception) {
            throw new ProtocolException(exception.getMessage());
        }
    }

    public static byte[] encodeWork(CoreLiquidationWorkView work) {
        if (work.actions().size() + work.resolutions().size() > MAX_ACTIONS) {
            throw new IllegalArgumentException("too many liquidation work items");
        }
        Writer writer = new Writer();
        writer.intValue(VERSION);
        writer.byteValue(ProductLineWireCode.encode(work.productLine()));
        writer.longValue(work.nextCursorLiquidationId());
        writer.byteValue(work.complete() ? 1 : 0);
        writer.byteValue(work.riskScanPending() ? 1 : 0);
        if (work.riskScanPending()) {
            writer.text(work.riskScanContinuation().symbol());
            writer.longValue(work.riskScanContinuation().priceSequence());
            writer.longValue(work.riskScanContinuation().lastUserId());
        }
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
            writer.text(action.status());
            writer.longValue(action.cursorOrderId());
        }
        writer.intValue(work.resolutions().size());
        for (CoreLiquidationWorkView.Resolution resolution : work.resolutions()) {
            writer.longValue(resolution.liquidationId());
            writer.longValue(resolution.userId());
            writer.text(resolution.symbol());
            writer.text(resolution.asset());
            writer.intValue(resolution.marginMode().wireCode());
            writer.intValue(resolution.positionSide().wireCode());
            writer.longValue(resolution.instrumentVersion());
            writer.longValue(resolution.triggerPriceSequence());
            writer.longValue(resolution.signedQuantitySteps());
            writer.longValue(resolution.deficitUnits());
            writer.longValue(resolution.recommendedCoveredUnits());
            writer.byteValue(resolution.purpose().ordinal());
        }
        return writer.toByteArray();
    }

    public static CoreLiquidationWorkView decodeWork(byte[] encoded) {
        Reader reader = new Reader(encoded);
        reader.version();
        ProductLine productLine = ProductLineWireCode.decode(reader.byteValue());
        long nextCursor = reader.nonNegativeLong("nextCursorLiquidationId");
        boolean complete = reader.booleanValue();
        boolean pending = reader.booleanValue();
        CoreRiskScanContinuation continuation = pending
                ? reader.riskScanContinuation() : null;
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
                    reader.positiveLong("markPriceTicks"), reader.text(),
                    reader.nonNegativeLong("cursorOrderId")));
        }
        int resolutionCount = reader.intValue();
        if (resolutionCount < 0 || resolutionCount > MAX_ACTIONS - count) {
            throw new ProtocolException("invalid liquidation resolution count");
        }
        List<CoreLiquidationWorkView.Resolution> resolutions = new ArrayList<>(resolutionCount);
        for (int index = 0; index < resolutionCount; index++) {
            long liquidationId = reader.positiveLong("liquidationId");
            long userId = reader.positiveLong("userId");
            String symbol = reader.text();
            String asset = reader.text();
            CoreMarginMode marginMode = CoreMarginMode.fromWireCode(reader.intValue());
            CorePositionSide positionSide = CorePositionSide.fromWireCode(reader.intValue());
            long instrumentVersion = reader.positiveLong("instrumentVersion");
            long triggerPriceSequence = reader.positiveLong("triggerPriceSequence");
            long signedQuantitySteps = reader.nonZeroLong("signedQuantitySteps");
            long deficitUnits = reader.positiveLong("deficitUnits");
            long recommendedCoveredUnits = reader.nonNegativeLong("recommendedCoveredUnits");
            int purposeCode = reader.byteValue();
            if (purposeCode <= CoreLiquidationWorkView.Purpose.EXECUTION.ordinal()
                    || purposeCode >= CoreLiquidationWorkView.Purpose.values().length) {
                throw new ProtocolException("invalid liquidation resolution purpose");
            }
            resolutions.add(new CoreLiquidationWorkView.Resolution(liquidationId, userId, symbol, asset,
                    marginMode, positionSide, instrumentVersion, triggerPriceSequence, signedQuantitySteps,
                    deficitUnits, recommendedCoveredUnits, CoreLiquidationWorkView.Purpose.values()[purposeCode]));
        }
        reader.requireConsumed();
        try {
            return new CoreLiquidationWorkView(productLine, nextCursor, complete, continuation, actions, resolutions);
        } catch (IllegalArgumentException exception) {
            throw new ProtocolException(exception.getMessage());
        }
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
        long nonNegativeLong(String field) {
            long value = longValue();
            if (value < 0) throw new ProtocolException(field + " must be nonnegative");
            return value;
        }
        CoreRiskScanContinuation riskScanContinuation() {
            try {
                return new CoreRiskScanContinuation(text(), nonNegativeLong("priceSequence"),
                        nonNegativeLong("lastUserId"));
            } catch (IllegalArgumentException exception) {
                throw new ProtocolException(exception.getMessage());
            }
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
