package com.surprising.aeron.protocol;

import com.surprising.product.api.ProductLine;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public final class CoreStateQueryCodec {

    private static final int VERSION = 5;
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

    public static byte[] encodeFundingProgressQuery(String symbol) {
        Writer writer = new Writer();
        writer.text(symbol);
        return writer.toByteArray();
    }

    public static String decodeFundingProgressQuery(byte[] encoded) {
        Reader reader = new Reader(encoded);
        String symbol = reader.text();
        reader.requireConsumed();
        return symbol;
    }

    public static byte[] encodeSettlementProgressQuery(String symbol) {
        return encodeFundingProgressQuery(symbol);
    }

    public static String decodeSettlementProgressQuery(byte[] encoded) {
        return decodeFundingProgressQuery(encoded);
    }

    public static byte[] encodeCommandResultQuery(UUID commandId) {
        if (commandId == null) {
            throw new IllegalArgumentException("commandId is required");
        }
        Writer writer = new Writer();
        writer.longValue(commandId.getMostSignificantBits());
        writer.longValue(commandId.getLeastSignificantBits());
        return writer.toByteArray();
    }

    public static UUID decodeCommandResultQuery(byte[] encoded) {
        Reader reader = new Reader(encoded);
        UUID commandId = new UUID(reader.longValue(), reader.longValue());
        reader.requireConsumed();
        return commandId;
    }

    public static byte[] encodeTreasuryState(List<CoreTreasuryAssetView> assets) {
        Writer writer = new Writer();
        writer.intValue(2);
        writer.intValue(assets.size());
        assets.forEach(asset -> {
            writer.text(asset.asset());
            writer.longValue(asset.feeBalanceUnits());
            writer.longValue(asset.insuranceBalanceUnits());
            writer.longValue(asset.insuranceDeficitUnits());
            writer.longValue(asset.liquidationFeeBalanceUnits());
            writer.longValue(asset.fundingResidualBalanceUnits());
            writer.longValue(asset.roundingResidualBalanceUnits());
            writer.longValue(asset.clearingPnlBalanceUnits());
        });
        return writer.toByteArray();
    }

    public static List<CoreTreasuryAssetView> decodeTreasuryState(byte[] encoded) {
        Reader reader = new Reader(encoded);
        reader.version(2);
        List<CoreTreasuryAssetView> assets = new ArrayList<>();
        for (int index = 0, count = reader.count("treasury assets"); index < count; index++) {
            assets.add(new CoreTreasuryAssetView(reader.text(), reader.longValue(),
                    reader.nonNegativeLong("insurance balance"), reader.nonNegativeLong("insurance deficit"),
                    reader.longValue(), reader.longValue(), reader.longValue(), reader.longValue()));
        }
        reader.requireConsumed();
        return List.copyOf(assets);
    }

    public static byte[] encodeUserState(CoreUserStateView state) {
        Writer writer = new Writer(encodedUserStateLength(state));
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
        writer.intValue(state.leverages().size());
        state.leverages().forEach(leverage -> {
            writer.text(leverage.symbol());
            writer.intValue(leverage.marginMode().wireCode());
            writer.longValue(leverage.leveragePpm());
        });
        return writer.toByteArray();
    }

    public static int encodedUserStateLength(CoreUserStateView state) {
        if (state == null) throw new IllegalArgumentException("user state is required");
        long length = Integer.BYTES * 4L + Long.BYTES * 2L;
        for (CoreBalanceView balance : state.balances()) {
            length = Math.addExact(length, textLength(balance.asset()) + Long.BYTES * 2L);
        }
        length = Math.addExact(length, Integer.BYTES);
        for (CoreReservationView reservation : state.reservations()) {
            length = Math.addExact(length, Long.BYTES + textLength(reservation.symbol())
                    + Long.BYTES + Integer.BYTES + textLength(reservation.asset()) + Long.BYTES * 4L);
        }
        length = Math.addExact(length, Integer.BYTES);
        for (CorePositionView position : state.positions()) {
            length = Math.addExact(length, textLength(position.symbol()) + textLength(position.marginAsset())
                    + Integer.BYTES * 2L + Long.BYTES * 6L);
        }
        length = Math.addExact(length, Integer.BYTES);
        for (CoreLeverageView leverage : state.leverages()) {
            length = Math.addExact(length, textLength(leverage.symbol()) + Integer.BYTES + Long.BYTES);
        }
        return Math.toIntExact(length);
    }

    static void writeUserState(java.nio.ByteBuffer output, CoreUserStateView state) {
        output.putInt(VERSION);
        output.putInt(ProductLineWireCode.encode(state.productLine()));
        output.putLong(state.userId());
        output.putLong(state.revision());
        output.putInt(state.positionMode().wireCode());
        output.putInt(state.balances().size());
        for (CoreBalanceView balance : state.balances()) {
            putText(output, balance.asset(), false);
            output.putLong(balance.availableUnits()).putLong(balance.lockedUnits());
        }
        output.putInt(state.reservations().size());
        for (CoreReservationView reservation : state.reservations()) {
            output.putLong(reservation.orderId());
            putText(output, reservation.symbol(), false);
            output.putLong(reservation.instrumentVersion()).putInt(reservation.kind().wireCode());
            putText(output, reservation.asset(), false);
            output.putLong(reservation.reservedUnits()).putLong(reservation.releasedUnits())
                    .putLong(reservation.consumedUnits()).putLong(reservation.orderQuantitySteps());
        }
        output.putInt(state.positions().size());
        for (CorePositionView position : state.positions()) {
            putText(output, position.symbol(), false);
            putText(output, position.marginAsset(), false);
            output.putInt(position.marginMode().wireCode()).putInt(position.positionSide().wireCode())
                    .putLong(position.instrumentVersion()).putLong(position.signedQuantitySteps())
                    .putLong(position.entryPriceTicks()).putLong(position.entryValueTicks())
                    .putLong(position.realizedPnlUnits()).putLong(position.positionMarginUnits());
        }
        output.putInt(state.leverages().size());
        for (CoreLeverageView leverage : state.leverages()) {
            putText(output, leverage.symbol(), false);
            output.putInt(leverage.marginMode().wireCode()).putLong(leverage.leveragePpm());
        }
    }

    public static CoreUserStateView decodeUserState(byte[] encoded) {
        Reader reader = new Reader(encoded);
        reader.version(VERSION);
        ProductLine productLine = ProductLineWireCode.decode(reader.intValue());
        long userId = reader.positiveLong("userId");
        long revision = reader.nonNegativeLong("revision");
        CorePositionMode positionMode = CorePositionMode.fromWireCode(reader.intValue());
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
            CoreMarginMode marginMode = CoreMarginMode.fromWireCode(reader.intValue());
            CorePositionSide positionSide = CorePositionSide.fromWireCode(reader.intValue());
            positions.add(new CorePositionView(symbol, marginAsset, marginMode, positionSide,
                    reader.nonNegativeLong("instrumentVersion"), reader.longValue(),
                    reader.nonNegativeLong("entryPriceTicks"), reader.nonNegativeLong("entryValueTicks"),
                    reader.longValue(), reader.nonNegativeLong("positionMarginUnits")));
        }
        List<CoreLeverageView> leverages = new ArrayList<>();
        for (int index = 0, count = reader.count("leverages"); index < count; index++) {
            leverages.add(new CoreLeverageView(reader.text(), CoreMarginMode.fromWireCode(reader.intValue()),
                    reader.positiveLong("leveragePpm")));
        }
        reader.requireConsumed();
        return new CoreUserStateView(productLine, userId, revision, positionMode, balances, reservations, positions,
                leverages);
    }

    public static byte[] encodeOrderState(CoreOrderStateView state) {
        Writer writer = new Writer(encodedOrderStateLength(state));
        writeOrderState(writer, state);
        return writer.toByteArray();
    }

    public static int encodedOrderStateLength(CoreOrderStateView state) {
        if (state == null) throw new IllegalArgumentException("order state is required");
        long length = Integer.BYTES + Long.BYTES + Integer.BYTES + Long.BYTES;
        length = Math.addExact(length, textLength(state.symbol()));
        length = Math.addExact(length, Long.BYTES + Integer.BYTES + Long.BYTES * 4L);
        length = Math.addExact(length, Byte.BYTES + Integer.BYTES * 4L + Byte.BYTES);
        length = Math.addExact(length, optionalTextLength(state.clientOrderId()));
        length = Math.addExact(length, Long.BYTES * 2L);
        length = Math.addExact(length, Long.BYTES * 6L);
        length = Math.addExact(length, textLength(state.status()) + Long.BYTES);
        return Math.toIntExact(length);
    }

    static void writeOrderState(java.nio.ByteBuffer output, CoreOrderStateView state) {
        output.putInt(VERSION).putLong(state.orderId())
                .putInt(ProductLineWireCode.encode(state.productLine())).putLong(state.userId());
        putText(output, state.symbol(), false);
        output.putLong(state.instrumentVersion()).putInt(state.side().wireCode())
                .putLong(state.priceTicks()).putLong(state.quantitySteps())
                .putLong(state.executedQuantitySteps()).putLong(state.remainingQuantitySteps())
                .put((byte) (state.reduceOnly() ? 1 : 0)).putInt(state.marginMode().wireCode())
                .putInt(state.positionSide().wireCode()).putInt(state.orderType().wireCode())
                .putInt(state.timeInForce().wireCode()).put((byte) (state.postOnly() ? 1 : 0));
        putText(output, state.clientOrderId(), true);
        output.putLong(state.commandId().getMostSignificantBits()).putLong(state.commandId().getLeastSignificantBits())
                .putLong(state.makerFeeRatePpm()).putLong(state.takerFeeRatePpm())
                .putLong(state.cumulativeFeeUnits()).putLong(state.createdAtEpochMillis())
                .putLong(state.updatedAtEpochMillis()).putLong(state.clusterPosition());
        putText(output, state.status(), false);
        output.putLong(state.revision());
    }

    private static void putText(java.nio.ByteBuffer output, String value, boolean optional) {
        if (value == null) throw new IllegalArgumentException(optional
                ? "optional query text is required" : "query text is required");
        int length = utf8Length(value);
        if (length > MAX_TEXT_BYTES || !optional && length == 0) {
            throw new IllegalArgumentException(optional
                    ? "invalid optional query text length" : "invalid query text length");
        }
        output.putInt(length);
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current < 0x80) {
                output.put((byte) current);
            } else if (current < 0x800) {
                output.put((byte) (0xc0 | current >>> 6));
                output.put((byte) (0x80 | current & 0x3f));
            } else if (Character.isHighSurrogate(current) && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                int codePoint = Character.toCodePoint(current, value.charAt(++index));
                output.put((byte) (0xf0 | codePoint >>> 18));
                output.put((byte) (0x80 | codePoint >>> 12 & 0x3f));
                output.put((byte) (0x80 | codePoint >>> 6 & 0x3f));
                output.put((byte) (0x80 | codePoint & 0x3f));
            } else if (Character.isSurrogate(current)) {
                output.put((byte) '?');
            } else {
                output.put((byte) (0xe0 | current >>> 12));
                output.put((byte) (0x80 | current >>> 6 & 0x3f));
                output.put((byte) (0x80 | current & 0x3f));
            }
        }
    }

    private static int textLength(String value) {
        if (value == null) throw new IllegalArgumentException("query text is required");
        int bytes = utf8Length(value);
        if (bytes == 0 || bytes > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException("invalid query text length");
        }
        return Math.addExact(Integer.BYTES, bytes);
    }

    private static int optionalTextLength(String value) {
        if (value == null) throw new IllegalArgumentException("optional query text is required");
        int bytes = utf8Length(value);
        if (bytes > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException("invalid optional query text length");
        }
        return Math.addExact(Integer.BYTES, bytes);
    }

    static int utf8Length(String value) {
        int length = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current < 0x80) length++;
            else if (current < 0x800) length += 2;
            else if (Character.isHighSurrogate(current) && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                length += 4;
                index++;
            } else if (Character.isSurrogate(current)) length++;
            else length += 3;
        }
        return length;
    }

    private static void writeOrderState(Writer writer, CoreOrderStateView state) {
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
        writer.longValue(state.cumulativeFeeUnits());
        writer.longValue(state.createdAtEpochMillis());
        writer.longValue(state.updatedAtEpochMillis());
        writer.longValue(state.clusterPosition());
        writer.text(state.status());
        writer.longValue(state.revision());
    }

    public static CoreOrderStateView decodeOrderState(byte[] encoded) {
        return decodeOrderState(encoded, 0, encoded == null ? 0 : encoded.length);
    }

    static CoreOrderStateView decodeOrderState(byte[] encoded, int offset, int length) {
        Reader reader = new Reader(encoded, offset, length);
        CoreOrderStateView state = readOrderState(reader);
        reader.requireConsumed();
        return state;
    }

    private static CoreOrderStateView readOrderState(Reader reader) {
        reader.version(VERSION);
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
        CoreMarginMode marginMode = CoreMarginMode.fromWireCode(reader.intValue());
        CorePositionSide positionSide = CorePositionSide.fromWireCode(reader.intValue());
        CoreOrderType orderType = CoreOrderType.fromWireCode(reader.intValue());
        CoreTimeInForce timeInForce = CoreTimeInForce.fromWireCode(reader.intValue());
        boolean postOnly = reader.booleanValue();
        String clientOrderId = reader.optionalText();
        java.util.UUID commandId = new java.util.UUID(reader.longValue(), reader.longValue());
        long makerFee = reader.longValue();
        long takerFee = reader.longValue();
        long cumulativeFee = reader.longValue();
        long createdAt = reader.nonNegativeLong("createdAt");
        long updatedAt = reader.nonNegativeLong("updatedAt");
        long clusterPosition = reader.nonNegativeLong("clusterPosition");
        return new CoreOrderStateView(orderId, productLine, userId, symbol,
                instrumentVersion, side, priceTicks, quantitySteps, executed, remaining, reduceOnly,
                marginMode, positionSide, orderType, timeInForce, postOnly, clientOrderId, commandId,
                makerFee, takerFee, cumulativeFee, createdAt, updatedAt, clusterPosition,
                reader.text(), reader.positiveLong("revision"));
    }

    public static byte[] encodeOpenOrdersQuery(CoreOpenOrdersQuery query) {
        Writer writer = new Writer();
        writer.intValue(1);
        writer.optionalText(query.symbol());
        writer.longValue(query.beforeOrderId());
        writer.intValue(query.limit());
        return writer.toByteArray();
    }

    public static CoreOpenOrdersQuery decodeOpenOrdersQuery(byte[] encoded) {
        Reader reader = new Reader(encoded);
        reader.version(1);
        CoreOpenOrdersQuery query = new CoreOpenOrdersQuery(reader.optionalText(),
                reader.nonNegativeLong("beforeOrderId"), reader.intValue());
        reader.requireConsumed();
        return query;
    }

    public static byte[] encodeOpenOrders(CoreOpenOrdersView view) {
        Writer writer = new Writer(encodedOpenOrdersLength(view));
        writer.intValue(1);
        writer.intValue(view.orders().size());
        view.orders().forEach(order -> writeOrderState(writer, order));
        return writer.toByteArray();
    }

    public static int encodedOpenOrdersLength(CoreOpenOrdersView view) {
        if (view == null) throw new IllegalArgumentException("open orders view is required");
        long length = Integer.BYTES * 2L;
        for (CoreOrderStateView order : view.orders()) {
            length = Math.addExact(length, encodedOrderStateLength(order));
        }
        return Math.toIntExact(length);
    }

    public static CoreOpenOrdersView decodeOpenOrders(byte[] encoded) {
        Reader reader = new Reader(encoded);
        reader.version(1);
        List<CoreOrderStateView> orders = new ArrayList<>();
        for (int index = 0, count = reader.count("open orders"); index < count; index++) {
            orders.add(readOrderState(reader));
        }
        reader.requireConsumed();
        return new CoreOpenOrdersView(orders);
    }

    public static byte[] encodeOrderBookView(CoreOrderBookView state) {
        Writer writer = new Writer();
        writer.intValue(VERSION);
        writer.longValue(state.exportSequence());
        writer.intValue(state.levels().size());
        for (CoreBookLevelView level : state.levels()) {
            writer.text(level.symbol());
            writer.intValue(level.side().wireCode());
            writer.longValue(level.priceTicks());
            writer.longValue(level.quantitySteps());
            writer.longValue(level.orderCount());
        }
        return writer.toByteArray();
    }

    public static byte[] encodeOrderBookQuery(CoreOrderBookQuery query) {
        Writer writer = new Writer();
        writer.intValue(1);
        writer.optionalText(query.symbol());
        writer.intValue(query.depth());
        return writer.toByteArray();
    }

    public static CoreOrderBookQuery decodeOrderBookQuery(byte[] encoded) {
        Reader reader = new Reader(encoded);
        reader.version(1);
        CoreOrderBookQuery query = new CoreOrderBookQuery(reader.optionalText(), reader.intValue());
        reader.requireConsumed();
        return query;
    }

    public static byte[] encodeOrderBookBootstrapQuery(CoreOrderBookBootstrapQuery query) {
        Writer writer = new Writer();
        writer.intValue(1);
        writer.optionalText(query.snapshotId());
        writer.optionalText(query.symbolCursor());
        writer.intValue(query.limit());
        writer.intValue(query.depth());
        return writer.toByteArray();
    }

    public static CoreOrderBookBootstrapQuery decodeOrderBookBootstrapQuery(byte[] encoded) {
        Reader reader = new Reader(encoded);
        reader.version(1);
        CoreOrderBookBootstrapQuery query = new CoreOrderBookBootstrapQuery(
                reader.optionalText(), reader.optionalText(), reader.intValue(), reader.intValue());
        reader.requireConsumed();
        return query;
    }

    public static byte[] encodeOrderBookBootstrapPage(CoreOrderBookBootstrapPage page) {
        Writer writer = new Writer();
        writer.intValue(1);
        writer.text(page.snapshotId());
        writer.longValue(page.exportSequence());
        writer.optionalText(page.nextSymbolCursor());
        writer.intValue(page.complete() ? 1 : 0);
        writer.intValue(page.levels().size());
        for (CoreBookLevelView level : page.levels()) {
            writer.text(level.symbol());
            writer.intValue(level.side().wireCode());
            writer.longValue(level.priceTicks());
            writer.longValue(level.quantitySteps());
            writer.longValue(level.orderCount());
        }
        return writer.toByteArray();
    }

    public static CoreOrderBookBootstrapPage decodeOrderBookBootstrapPage(byte[] encoded) {
        Reader reader = new Reader(encoded);
        reader.version(1);
        String snapshotId = reader.text();
        long exportSequence = reader.nonNegativeLong("exportSequence");
        String nextSymbolCursor = reader.optionalText();
        int completeCode = reader.intValue();
        if (completeCode != 0 && completeCode != 1) {
            throw new ProtocolException("invalid order-book bootstrap completion flag");
        }
        boolean complete = completeCode == 1;
        int count = reader.count("levels");
        List<CoreBookLevelView> levels = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            levels.add(new CoreBookLevelView(reader.text(), CoreOrderSide.fromWireCode(reader.intValue()),
                    reader.positiveLong("priceTicks"), reader.positiveLong("quantitySteps"),
                    reader.positiveLong("orderCount")));
        }
        reader.requireConsumed();
        return new CoreOrderBookBootstrapPage(snapshotId, exportSequence, nextSymbolCursor, complete, levels);
    }

    public static CoreOrderBookView decodeOrderBookView(byte[] encoded) {
        Reader reader = new Reader(encoded);
        reader.version(VERSION);
        long exportSequence = reader.nonNegativeLong("exportSequence");
        int count = reader.count("levels");
        List<CoreBookLevelView> levels = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            levels.add(new CoreBookLevelView(reader.text(), CoreOrderSide.fromWireCode(reader.intValue()),
                    reader.positiveLong("priceTicks"), reader.positiveLong("quantitySteps"),
                    reader.positiveLong("orderCount")));
        }
        reader.requireConsumed();
        return new CoreOrderBookView(exportSequence, levels);
    }

    private static final class Writer {
        private byte[] output;
        private int offset;

        private Writer() {
            this(128);
        }

        private Writer(int capacity) {
            if (capacity < 0) throw new IllegalArgumentException("writer capacity must not be negative");
            output = new byte[capacity];
        }

        void byteValue(int value) {
            ensureCapacity(Byte.BYTES);
            output[offset++] = (byte) value;
        }

        void intValue(int value) {
            ensureCapacity(Integer.BYTES);
            for (int shift = 0; shift < Integer.SIZE; shift += Byte.SIZE) {
                output[offset++] = (byte) (value >>> shift);
            }
        }

        void longValue(long value) {
            ensureCapacity(Long.BYTES);
            for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {
                output[offset++] = (byte) (value >>> shift);
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
            bytes(bytes);
        }

        void optionalText(String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_TEXT_BYTES) {
                throw new IllegalArgumentException("invalid optional query text length");
            }
            intValue(bytes.length);
            bytes(bytes);
        }

        byte[] toByteArray() {
            return offset == output.length ? output : Arrays.copyOf(output, offset);
        }

        private void bytes(byte[] value) {
            ensureCapacity(value.length);
            System.arraycopy(value, 0, output, offset, value.length);
            offset += value.length;
        }

        private void ensureCapacity(int additional) {
            int required = Math.addExact(offset, additional);
            if (required <= output.length) return;
            int doubled = output.length <= Integer.MAX_VALUE / 2 ? output.length * 2 : Integer.MAX_VALUE;
            output = Arrays.copyOf(output, Math.max(required, doubled));
        }
    }

    private static final class Reader {
        private final byte[] input;
        private final int limit;
        private int offset;

        Reader(byte[] input) {
            this(input, 0, input == null ? 0 : input.length);
        }

        Reader(byte[] input, int offset, int length) {
            if (input == null) {
                throw new ProtocolException("query state is required");
            }
            if (offset < 0 || length < 0 || offset > input.length - length) {
                throw new ProtocolException("invalid query state range");
            }
            this.input = input;
            this.offset = offset;
            this.limit = offset + length;
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
            if (value < 0 || value > limit) {
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
            if (offset != limit) {
                throw new ProtocolException("trailing bytes in query state");
            }
        }

        private void require(int length) {
            if (length < 0 || offset > limit - length) {
                throw new ProtocolException("truncated query state");
            }
        }
    }
}
