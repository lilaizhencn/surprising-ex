package com.surprising.aeron.protocol;

import com.surprising.product.api.ProductLine;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class CoreStateQueryCodec {

    private static final int VERSION = 3;
    private static final int VERSION_2 = 2;
    private static final int VERSION_1 = 1;
    private static final int MAX_TEXT_BYTES = 64;

    private CoreStateQueryCodec() {
    }

    public static byte[] encodeClientOrderStateQuery(String clientOrderId) {
        Writer writer = new Writer();
        writer.text(clientOrderId);
        return writer.toByteArray();
    }

    public static String decodeClientOrderStateQuery(byte[] encoded) {
        Reader reader = new Reader(encoded);
        String clientOrderId = reader.text();
        reader.requireConsumed();
        return clientOrderId;
    }

    public static byte[] encodeUserState(CoreUserStateView state) {
        Writer writer = new Writer();
        writer.intValue(VERSION);
        writer.intValue(ProductLineWireCode.encode(state.productLine()));
        writer.longValue(state.userId());
        writer.longValue(state.revision());
        writer.intValue(state.positionMode().wireCode());
        writer.intValue(state.balances().size());
        state.balances().forEach(balance -> {
            writer.text(balance.asset());
            writer.longValue(balance.availableUnits());
            writer.longValue(balance.lockedUnits());
        });
        writer.intValue(state.reservations().size());
        state.reservations().forEach(reservation -> {
            writer.longValue(reservation.orderId());
            writer.text(reservation.symbol());
            writer.longValue(reservation.instrumentVersion());
            writer.intValue(reservation.kind().wireCode());
            writer.text(reservation.asset());
            writer.longValue(reservation.reservedUnits());
            writer.longValue(reservation.releasedUnits());
            writer.longValue(reservation.consumedUnits());
            writer.longValue(reservation.orderQuantitySteps());
        });
        writer.intValue(state.positions().size());
        state.positions().forEach(position -> {
            writer.text(position.symbol());
            writer.text(position.marginAsset());
            writer.intValue(position.marginMode().wireCode());
            writer.intValue(position.positionSide().wireCode());
            writer.longValue(position.instrumentVersion());
            writer.longValue(position.signedQuantitySteps());
            writer.longValue(position.entryPriceTicks());
            writer.longValue(position.entryValueTicks());
            writer.longValue(position.realizedPnlUnits());
            writer.longValue(position.positionMarginUnits());
        });
        return writer.toByteArray();
    }

    public static CoreUserStateView decodeUserState(byte[] encoded) {
        Reader reader = new Reader(encoded);
        int version = reader.version(VERSION, VERSION_1);
        ProductLine productLine = ProductLineWireCode.decode(reader.intValue());
        long userId = reader.positiveLong("userId");
        long revision = reader.nonNegativeLong("revision");
        CorePositionMode positionMode = version == VERSION
                ? CorePositionMode.fromWireCode(reader.intValue()) : CorePositionMode.ONE_WAY;
        List<CoreBalanceView> balances = new ArrayList<>();
        for (int index = 0, count = reader.count("balances"); index < count; index++) {
            balances.add(new CoreBalanceView(reader.text(), reader.nonNegativeLong("availableUnits"),
                    reader.nonNegativeLong("lockedUnits")));
        }
        List<CoreReservationView> reservations = new ArrayList<>();
        for (int index = 0, count = reader.count("reservations"); index < count; index++) {
            reservations.add(new CoreReservationView(reader.positiveLong("orderId"), reader.text(),
                    reader.positiveLong("instrumentVersion"),
                    ReservationKind.fromWireCode(reader.intValue()), reader.text(),
                    reader.positiveLong("reservedUnits"), reader.nonNegativeLong("releasedUnits"),
                    reader.nonNegativeLong("consumedUnits"), reader.positiveLong("orderQuantitySteps")));
        }
        List<CorePositionView> positions = new ArrayList<>();
        for (int index = 0, count = reader.count("positions"); index < count; index++) {
            String symbol = reader.text();
            String marginAsset = reader.text();
            CoreMarginMode marginMode = version == VERSION
                    ? CoreMarginMode.fromWireCode(reader.intValue()) : CoreMarginMode.CROSS;
            CorePositionSide positionSide = version == VERSION
                    ? CorePositionSide.fromWireCode(reader.intValue()) : CorePositionSide.NET;
            positions.add(new CorePositionView(symbol, marginAsset, marginMode, positionSide,
                    reader.nonNegativeLong("instrumentVersion"), reader.longValue(),
                    reader.nonNegativeLong("entryPriceTicks"), reader.nonNegativeLong("entryValueTicks"),
                    reader.longValue(), reader.nonNegativeLong("positionMarginUnits")));
        }
        reader.requireConsumed();
        return new CoreUserStateView(productLine, userId, revision, positionMode, balances, reservations, positions);
    }

    public static byte[] encodeOrderState(CoreOrderStateView state) {
        Writer writer = new Writer();
        writer.intValue(VERSION);
        writer.longValue(state.orderId());
        writer.intValue(ProductLineWireCode.encode(state.productLine()));
        writer.longValue(state.userId());
        writer.text(state.symbol());
        writer.longValue(state.instrumentVersion());
        writer.intValue(state.side().wireCode());
        writer.longValue(state.priceTicks());
        writer.longValue(state.quantitySteps());
        writer.longValue(state.executedQuantitySteps());
        writer.longValue(state.remainingQuantitySteps());
        writer.byteValue(state.reduceOnly() ? 1 : 0);
        writer.intValue(state.marginMode().wireCode());
        writer.intValue(state.positionSide().wireCode());
        writer.intValue(state.orderType().wireCode());
        writer.intValue(state.timeInForce().wireCode());
        writer.byteValue(state.postOnly() ? 1 : 0);
        writer.optionalText(state.clientOrderId());
        writer.longValue(state.commandId().getMostSignificantBits());
        writer.longValue(state.commandId().getLeastSignificantBits());
        writer.longValue(state.makerFeeRatePpm());
        writer.longValue(state.takerFeeRatePpm());
        writer.longValue(state.createdAtEpochMillis());
        writer.longValue(state.updatedAtEpochMillis());
        writer.longValue(state.clusterPosition());
        writer.text(state.status());
        writer.longValue(state.revision());
        return writer.toByteArray();
    }

    public static CoreOrderStateView decodeOrderState(byte[] encoded) {
        Reader reader = new Reader(encoded);
        int version = reader.version(VERSION, VERSION_2, VERSION_1);
        long orderId = reader.positiveLong("orderId");
        ProductLine productLine = ProductLineWireCode.decode(reader.intValue());
        long userId = reader.positiveLong("userId");
        String symbol = reader.text();
        long instrumentVersion = reader.positiveLong("instrumentVersion");
        CoreOrderSide side = CoreOrderSide.fromWireCode(reader.intValue());
        long priceTicks = reader.nonNegativeLong("priceTicks");
        long quantitySteps = reader.positiveLong("quantitySteps");
        long executed = reader.nonNegativeLong("executedQuantitySteps");
        long remaining = reader.nonNegativeLong("remainingQuantitySteps");
        boolean reduceOnly = reader.booleanValue();
        CoreMarginMode marginMode = version == VERSION ? CoreMarginMode.fromWireCode(reader.intValue()) : CoreMarginMode.CROSS;
        CorePositionSide positionSide = version == VERSION ? CorePositionSide.fromWireCode(reader.intValue()) : CorePositionSide.NET;
        CoreOrderType orderType = version == VERSION ? CoreOrderType.fromWireCode(reader.intValue()) : CoreOrderType.LIMIT;
        CoreTimeInForce timeInForce = version == VERSION ? CoreTimeInForce.fromWireCode(reader.intValue()) : CoreTimeInForce.GTC;
        boolean postOnly = version == VERSION && reader.booleanValue();
        String clientOrderId = version == VERSION ? reader.optionalText() : "";
        java.util.UUID commandId = version == VERSION
                ? new java.util.UUID(reader.longValue(), reader.longValue()) : new java.util.UUID(0, orderId);
        long makerFee = version == VERSION ? reader.longValue() : 0;
        long takerFee = version == VERSION ? reader.longValue() : 0;
        long createdAt = version == VERSION ? reader.nonNegativeLong("createdAt") : 0;
        long updatedAt = version == VERSION ? reader.nonNegativeLong("updatedAt") : 0;
        long clusterPosition = version == VERSION ? reader.nonNegativeLong("clusterPosition") : 0;
        CoreOrderStateView state = new CoreOrderStateView(orderId, productLine, userId, symbol,
                instrumentVersion, side, priceTicks, quantitySteps, executed, remaining, reduceOnly,
                marginMode, positionSide, orderType, timeInForce, postOnly, clientOrderId, commandId,
                makerFee, takerFee, createdAt, updatedAt, clusterPosition, reader.text(), reader.positiveLong("revision"));
        reader.requireConsumed();
        return state;
    }

    private static final class Writer {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        void byteValue(int value) {
            output.write(value);
        }

        void intValue(int value) {
            for (int shift = 0; shift < Integer.SIZE; shift += Byte.SIZE) {
                output.write(value >>> shift);
            }
        }

        void longValue(long value) {
            for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {
                output.write((int) (value >>> shift));
            }
        }

        void text(String value) {
            if (value == null) {
                throw new IllegalArgumentException("query text is required");
            }
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            if (bytes.length == 0 || bytes.length > MAX_TEXT_BYTES) {
                throw new IllegalArgumentException("invalid query text length");
            }
            intValue(bytes.length);
            output.writeBytes(bytes);
        }

        void optionalText(String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_TEXT_BYTES) {
                throw new IllegalArgumentException("invalid optional query text length");
            }
            intValue(bytes.length);
            output.writeBytes(bytes);
        }

        byte[] toByteArray() {
            return output.toByteArray();
        }
    }

    private static final class Reader {
        private final byte[] input;
        private int offset;

        Reader(byte[] input) {
            if (input == null) {
                throw new ProtocolException("query state is required");
            }
            this.input = input;
        }

        void requireVersion() {
            int version = intValue();
            if (version != VERSION) {
                throw new ProtocolException("unsupported query state version: " + version);
            }
        }

        int version(int... supported) {
            int version = intValue();
            for (int candidate : supported) {
                if (version == candidate) {
                    return version;
                }
            }
            throw new ProtocolException("unsupported query state version: " + version);
        }

        int byteValue() {
            require(1);
            return Byte.toUnsignedInt(input[offset++]);
        }

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
            if (value <= 0) {
                throw new ProtocolException(field + " must be positive");
            }
            return value;
        }

        long nonNegativeLong(String field) {
            long value = longValue();
            if (value < 0) {
                throw new ProtocolException(field + " must not be negative");
            }
            return value;
        }

        int count(String field) {
            int value = intValue();
            if (value < 0 || value > input.length) {
                throw new ProtocolException("invalid " + field + " count: " + value);
            }
            return value;
        }

        String text() {
            int length = count("text");
            if (length == 0 || length > MAX_TEXT_BYTES) {
                throw new ProtocolException("invalid query text length: " + length);
            }
            require(length);
            String value = new String(input, offset, length, StandardCharsets.UTF_8);
            offset += length;
            return value;
        }

        String optionalText() {
            int length = count("optional text");
            if (length > MAX_TEXT_BYTES) {
                throw new ProtocolException("invalid optional query text length: " + length);
            }
            require(length);
            String value = new String(input, offset, length, StandardCharsets.UTF_8);
            offset += length;
            return value;
        }

        boolean booleanValue() {
            int value = byteValue();
            if (value != 0 && value != 1) {
                throw new ProtocolException("invalid query boolean: " + value);
            }
            return value == 1;
        }

        void requireConsumed() {
            if (offset != input.length) {
                throw new ProtocolException("trailing bytes in query state");
            }
        }

        private void require(int length) {
            if (length < 0 || offset > input.length - length) {
                throw new ProtocolException("truncated query state");
            }
        }
    }
}
