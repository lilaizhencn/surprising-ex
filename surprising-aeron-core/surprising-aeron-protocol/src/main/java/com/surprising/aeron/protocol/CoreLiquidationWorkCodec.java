package com.surprising.aeron.protocol;

import com.surprising.product.api.ProductLine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class CoreLiquidationWorkCodec {

    private static final int VERSION = 4;
    private static final int MAX_ACTIONS = 1_000;
    private static final int MAX_TEXT_BYTES = 64;
    private static final CoreLiquidationWorkView.Purpose[] PURPOSES =
            CoreLiquidationWorkView.Purpose.values();

    private CoreLiquidationWorkCodec() {
    }

    public static byte[] encodeQuery(ProductLine productLine, CoreLiquidationWorkView.Purpose purpose,
                                     long afterLiquidationId, int maxItems, int maxBytes) {
        return encodeQuery(new CoreLiquidationWorkView.Query(productLine, purpose, afterLiquidationId,
                maxItems, maxBytes));
    }

    public static byte[] encodeQuery(CoreLiquidationWorkView.Query query) {
        return ByteBuffer.allocate(Integer.BYTES * 3 + Byte.BYTES * 2 + Long.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(VERSION)
                .put((byte) ProductLineWireCode.encode(query.productLine()))
                .put((byte) query.purpose().ordinal())
                .putLong(query.afterLiquidationId())
                .putInt(query.maxItems())
                .putInt(query.maxBytes())
                .array();
    }

    public static CoreLiquidationWorkView.Query decodeQuery(byte[] encoded) {
        Reader reader = new Reader(encoded);
        reader.version();
        ProductLine productLine = ProductLineWireCode.decode(reader.byteValue());
        int purposeCode = reader.byteValue();
        if (purposeCode >= PURPOSES.length) {
            throw new ProtocolException("invalid liquidation work purpose");
        }
        CoreLiquidationWorkView.Purpose purpose = PURPOSES[purposeCode];
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
        int length = Integer.BYTES * 3 + Byte.BYTES * 3 + Long.BYTES;
        if (work.riskScanPending()) {
            length = Math.addExact(length,
                    textLength(work.riskScanContinuation().instrumentId()) + Long.BYTES * 2);
        }
        for (CoreLiquidationActionView action : work.actions()) {
            length = Math.addExact(length, Long.BYTES * 7 + Integer.BYTES * 2
                    + textLength(action.instrumentId()) + textLength(action.status()));
        }
        for (CoreLiquidationWorkView.Resolution resolution : work.resolutions()) {
            length = Math.addExact(length, Long.BYTES * 6 + Integer.BYTES * 2 + Byte.BYTES
                    + textLength(resolution.instrumentId()) + textLength(resolution.asset()));
        }
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(VERSION)
                .put((byte) ProductLineWireCode.encode(work.productLine()))
                .putLong(work.nextCursorLiquidationId())
                .put((byte) (work.complete() ? 1 : 0))
                .put((byte) (work.riskScanPending() ? 1 : 0));
        if (work.riskScanPending()) {
            CoreStateQueryCodec.putText(buffer, work.riskScanContinuation().instrumentId(), false, MAX_TEXT_BYTES);
            buffer.putLong(work.riskScanContinuation().priceSequence())
                    .putLong(work.riskScanContinuation().lastUserId());
        }
        buffer.putInt(work.actions().size());
        for (CoreLiquidationActionView action : work.actions()) {
            buffer.putLong(action.liquidationId())
                    .putLong(action.userId());
            CoreStateQueryCodec.putText(buffer, action.instrumentId(), false, MAX_TEXT_BYTES);
            buffer.putInt(action.marginMode().wireCode())
                    .putInt(action.positionSide().wireCode())
                    .putLong(action.triggerPriceSequence())
                    .putLong(action.signedQuantitySteps())
                    .putLong(action.closeQuantitySteps())
                    .putLong(action.markPriceTicks());
            CoreStateQueryCodec.putText(buffer, action.status(), false, MAX_TEXT_BYTES);
            buffer.putLong(action.cursorOrderId());
        }
        buffer.putInt(work.resolutions().size());
        for (CoreLiquidationWorkView.Resolution resolution : work.resolutions()) {
            buffer.putLong(resolution.liquidationId())
                    .putLong(resolution.userId());
            CoreStateQueryCodec.putText(buffer, resolution.instrumentId(), false, MAX_TEXT_BYTES);
            CoreStateQueryCodec.putText(buffer, resolution.asset(), false, MAX_TEXT_BYTES);
            buffer.putInt(resolution.marginMode().wireCode())
                    .putInt(resolution.positionSide().wireCode())
                    .putLong(resolution.triggerPriceSequence())
                    .putLong(resolution.signedQuantitySteps())
                    .putLong(resolution.deficitUnits())
                    .putLong(resolution.recommendedCoveredUnits())
                    .put((byte) resolution.purpose().ordinal());
        }
        return buffer.array();
    }

    private static int textLength(String value) {
        if (value == null) throw new IllegalArgumentException("invalid text");
        int bytes = CoreStateQueryCodec.utf8Length(value);
        if (bytes < 1 || bytes > MAX_TEXT_BYTES) throw new IllegalArgumentException("invalid text");
        return Integer.BYTES + bytes;
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
                    reader.positiveLong("triggerPriceSequence"),
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
            String instrumentId = reader.text();
            String asset = reader.text();
            CoreMarginMode marginMode = CoreMarginMode.fromWireCode(reader.intValue());
            CorePositionSide positionSide = CorePositionSide.fromWireCode(reader.intValue());
            long triggerPriceSequence = reader.positiveLong("triggerPriceSequence");
            long signedQuantitySteps = reader.nonZeroLong("signedQuantitySteps");
            long deficitUnits = reader.positiveLong("deficitUnits");
            long recommendedCoveredUnits = reader.nonNegativeLong("recommendedCoveredUnits");
            int purposeCode = reader.byteValue();
            if (purposeCode <= CoreLiquidationWorkView.Purpose.EXECUTION.ordinal()
                    || purposeCode >= PURPOSES.length) {
                throw new ProtocolException("invalid liquidation resolution purpose");
            }
            resolutions.add(new CoreLiquidationWorkView.Resolution(liquidationId, userId, instrumentId, asset,
                    marginMode, positionSide, triggerPriceSequence, signedQuantitySteps,
                    deficitUnits, recommendedCoveredUnits, PURPOSES[purposeCode]));
        }
        reader.requireConsumed();
        try {
            return new CoreLiquidationWorkView(productLine, nextCursor, complete, continuation, actions, resolutions);
        } catch (IllegalArgumentException exception) {
            throw new ProtocolException(exception.getMessage());
        }
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
