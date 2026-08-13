package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.ProductLineWireCode;
import com.surprising.aeron.protocol.ProtocolException;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.product.api.ProductLine;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.OptionType;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

public final class TradingStateSnapshotCodec {

    private static final int VERSION = 3;
    private static final int VERSION_2 = 2;
    private static final int VERSION_1 = 1;
    private static final int MAX_TEXT_BYTES = 64;

    private TradingStateSnapshotCodec() {
    }

    public static byte[] encode(TradingCoreState state) {
        Writer writer = new Writer();
        writer.intValue(VERSION);
        writer.intValue(ProductLineWireCode.encode(state.productLine()));
        writer.longValue(state.revision());
        writer.intValue(state.users().size());
        for (CoreUserState user : state.users().values()) {
            writer.longValue(user.userId());
            writer.longValue(user.revision());
            writer.intValue(user.balances().size());
            user.balances().values().forEach(balance -> {
                writer.text(balance.asset());
                writer.longValue(balance.availableUnits());
                writer.longValue(balance.lockedUnits());
            });
            writer.intValue(user.reservations().size());
            user.reservations().values().forEach(reservation -> {
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
            writer.intValue(user.positions().size());
            user.positions().values().forEach(position -> {
                writer.text(position.symbol());
                writer.text(position.marginAsset());
                writer.longValue(position.instrumentVersion());
                writer.longValue(position.signedQuantitySteps());
                writer.longValue(position.entryPriceTicks());
                writer.longValue(position.entryValueTicks());
                writer.longValue(position.realizedPnlUnits());
                writer.longValue(position.positionMarginUnits());
            });
        }
        writer.intValue(state.orders().size());
        state.orders().values().forEach(order -> {
            writer.longValue(order.orderId());
            writer.longValue(order.userId());
            writer.text(order.symbol());
            writer.longValue(order.instrumentVersion());
            writer.intValue(order.side().wireCode());
            writer.longValue(order.priceTicks());
            writer.longValue(order.quantitySteps());
            writer.longValue(order.executedQuantitySteps());
            writer.longValue(order.remainingQuantitySteps());
            writer.byteValue(order.reduceOnly() ? 1 : 0);
            writer.intValue(order.status().ordinal());
            writer.longValue(order.revision());
        });
        writer.longValue(state.bookState().nextPrioritySequence());
        writer.intValue(state.bookState().openOrders().size());
        state.bookState().recoveryOrder().forEach(order -> {
            writer.longValue(order.orderId());
            writer.longValue(order.userId());
            writer.text(order.symbol());
            writer.intValue(order.side().wireCode());
            writer.longValue(order.priceTicks());
            writer.longValue(order.remainingQuantitySteps());
            writer.longValue(order.prioritySequence());
        });
        writer.intValue(state.instruments().size());
        state.instruments().values().forEach(instrument -> {
            writer.text(instrument.symbol());
            writer.longValue(instrument.version());
            writer.intValue(instrument.contractType().ordinal());
            writer.text(instrument.baseAsset());
            writer.text(instrument.quoteAsset());
            writer.text(instrument.settleAsset());
            writer.longValue(instrument.notionalMultiplierUnits());
            writer.longValue(instrument.priceTickUnits());
            writer.longValue(instrument.settleScaleUnits());
            writer.longValue(instrument.initialMarginRatePpm());
            writer.longValue(instrument.maintenanceMarginRatePpm());
            writer.longValue(instrument.makerFeeRatePpm());
            writer.longValue(instrument.takerFeeRatePpm());
            writer.longValue(instrument.expiryEpochMillis());
            writer.intValue(instrument.optionType() == null ? -1 : instrument.optionType().ordinal());
            writer.longValue(instrument.strikePriceTicks());
        });
        writer.intValue(state.riskState().markPrices().size());
        state.riskState().markPrices().values().forEach(mark -> {
            writer.text(mark.symbol());
            writer.longValue(mark.instrumentVersion());
            writer.longValue(mark.markPriceTicks());
            writer.longValue(mark.priceSequence());
        });
        writer.intValue(state.riskState().snapshots().size());
        state.riskState().snapshots().values().forEach(risk -> {
            writer.longValue(risk.userId());
            writer.text(risk.symbol());
            writer.longValue(risk.priceSequence());
            writer.longValue(risk.equityUnits());
            writer.longValue(risk.unrealizedPnlUnits());
            writer.longValue(risk.maintenanceMarginUnits());
            writer.longValue(risk.marginRatioPpm());
            writer.intValue(risk.status().ordinal());
        });
        writer.intValue(state.riskState().liquidations().size());
        state.riskState().liquidations().values().forEach(liquidation -> {
            writer.longValue(liquidation.liquidationId());
            writer.longValue(liquidation.userId());
            writer.text(liquidation.symbol());
            writer.longValue(liquidation.instrumentVersion());
            writer.longValue(liquidation.triggerPriceSequence());
            writer.longValue(liquidation.closeQuantitySteps());
            writer.longValue(liquidation.deficitUnits());
            writer.intValue(liquidation.status().ordinal());
        });
        writer.text(state.riskState().scan().symbol());
        writer.longValue(state.riskState().scan().priceSequence());
        writer.longValue(state.riskState().scan().lastUserId());
        writer.byteValue(state.riskState().scan().complete() ? 1 : 0);
        writer.longValue(state.riskState().nextLiquidationId());
        writeUnits(writer, state.treasuryState().feeBalances());
        writeUnits(writer, state.treasuryState().insuranceBalances());
        writeUnits(writer, state.treasuryState().insuranceDeficits());
        writeUnits(writer, state.treasuryState().fundingSettlements());
        writeUnits(writer, state.treasuryState().lifecycleSettlements());
        return writer.toByteArray();
    }

    public static TradingCoreState decode(byte[] encoded, ProductLine expectedProductLine) {
        Reader reader = new Reader(encoded);
        int version = reader.intValue();
        if (version != VERSION && version != VERSION_2 && version != VERSION_1) {
            throw new ProtocolException("unsupported trading snapshot version: " + version);
        }
        ProductLine productLine = ProductLineWireCode.decode(reader.intValue());
        if (productLine != expectedProductLine) {
            throw new ProtocolException("trading snapshot product line mismatch");
        }
        long revision = reader.nonNegativeLong("core revision");
        int userCount = reader.count("users");
        Map<Long, CoreUserState> users = new TreeMap<>();
        for (int index = 0; index < userCount; index++) {
            long userId = reader.positiveLong("userId");
            long userRevision = reader.nonNegativeLong("user revision");
            Map<String, AssetBalance> balances = new TreeMap<>();
            int balanceCount = reader.count("balances");
            for (int balanceIndex = 0; balanceIndex < balanceCount; balanceIndex++) {
                String asset = reader.text();
                putUnique(balances, asset, new AssetBalance(asset,
                        reader.nonNegativeLong("available units"), reader.nonNegativeLong("locked units")));
            }
            Map<Long, OrderReservation> reservations = new TreeMap<>();
            int reservationCount = reader.count("reservations");
            for (int reservationIndex = 0; reservationIndex < reservationCount; reservationIndex++) {
                long orderId = reader.positiveLong("reservation orderId");
                OrderReservation reservation = new OrderReservation(orderId, reader.text(),
                        reader.positiveLong("instrument version"),
                        ReservationKind.fromWireCode(reader.intValue()), reader.text(),
                        reader.positiveLong("reserved units"), reader.nonNegativeLong("released units"),
                        reader.nonNegativeLong("consumed units"), reader.positiveLong("order quantity"));
                putUnique(reservations, orderId, reservation);
            }
            Map<String, CorePositionState> positions = new TreeMap<>();
            int positionCount = reader.count("positions");
            for (int positionIndex = 0; positionIndex < positionCount; positionIndex++) {
                String symbol = reader.text();
                CorePositionState position = new CorePositionState(symbol, reader.text(),
                        reader.nonNegativeLong("instrument version"), reader.longValue(),
                        reader.nonNegativeLong("entry price"), reader.nonNegativeLong("entry value"),
                        reader.longValue(), reader.nonNegativeLong("position margin"));
                putUnique(positions, symbol, position);
            }
            putUnique(users, userId, new CoreUserState(productLine, userId, userRevision,
                    balances, reservations, positions));
        }
        Map<Long, CoreOrderState> orders = new TreeMap<>();
        int orderCount = reader.count("orders");
        for (int index = 0; index < orderCount; index++) {
            long orderId = reader.positiveLong("orderId");
            long userId = reader.positiveLong("order userId");
            String symbol = reader.text();
            long instrumentVersion = reader.positiveLong("instrument version");
            CoreOrderSide side = CoreOrderSide.fromWireCode(reader.intValue());
            long priceTicks = reader.nonNegativeLong("price ticks");
            long quantitySteps = reader.positiveLong("quantity steps");
            long executedSteps = reader.nonNegativeLong("executed steps");
            long remainingSteps = reader.nonNegativeLong("remaining steps");
            boolean reduceOnly = reader.booleanValue();
            int statusCode = reader.intValue();
            if (statusCode < 0 || statusCode >= CoreOrderStatus.values().length) {
                throw new ProtocolException("invalid order status: " + statusCode);
            }
            CoreOrderState order = new CoreOrderState(orderId, productLine, userId, symbol,
                    instrumentVersion, side,
                    priceTicks, quantitySteps, executedSteps, remainingSteps, reduceOnly,
                    CoreOrderStatus.values()[statusCode], reader.positiveLong("order revision"));
            putUnique(orders, orderId, order);
        }
        CoreBookState bookState;
        if (version == VERSION_1) {
            Map<Long, CoreBookOrder> migratedOrders = new TreeMap<>();
            long prioritySequence = 1;
            for (CoreOrderState order : orders.values()) {
                if (order.status() == CoreOrderStatus.OPEN) {
                    migratedOrders.put(order.orderId(), new CoreBookOrder(order.orderId(), order.userId(),
                            order.symbol(), order.side(), order.priceTicks(), order.remainingQuantitySteps(),
                            prioritySequence));
                    prioritySequence = Math.incrementExact(prioritySequence);
                }
            }
            bookState = new CoreBookState(prioritySequence, migratedOrders);
        } else {
            long nextPrioritySequence = reader.positiveLong("next book priority sequence");
            int bookOrderCount = reader.count("book orders");
            Map<Long, CoreBookOrder> bookOrders = new TreeMap<>();
            for (int index = 0; index < bookOrderCount; index++) {
                long orderId = reader.positiveLong("book orderId");
                CoreBookOrder bookOrder = new CoreBookOrder(orderId,
                        reader.positiveLong("book userId"), reader.text(),
                        CoreOrderSide.fromWireCode(reader.intValue()),
                        reader.positiveLong("book price"), reader.positiveLong("book remaining quantity"),
                        reader.positiveLong("book priority sequence"));
                putUnique(bookOrders, orderId, bookOrder);
            }
            bookState = new CoreBookState(nextPrioritySequence, bookOrders);
        }
        Map<String, CoreInstrumentState> instruments = new TreeMap<>();
        CoreRiskState riskState = CoreRiskState.empty();
        CoreTreasuryState treasuryState = CoreTreasuryState.empty();
        if (version == VERSION) {
            int instrumentCount = reader.count("instruments");
            for (int index = 0; index < instrumentCount; index++) {
                String symbol = reader.text();
                long instrumentVersion = reader.positiveLong("instrument version");
                int contractType = reader.intValue();
                if (contractType < 0 || contractType >= ContractType.values().length) {
                    throw new ProtocolException("invalid contract type: " + contractType);
                }
                ContractType decodedType = ContractType.values()[contractType];
                String baseAsset = reader.text();
                String quoteAsset = reader.text();
                String settleAsset = reader.text();
                long multiplier = reader.positiveLong("notional multiplier");
                long priceTick = reader.positiveLong("price tick units");
                long settleScale = reader.positiveLong("settle scale");
                long initialMargin = reader.positiveLong("initial margin rate");
                long maintenanceMargin = reader.positiveLong("maintenance margin rate");
                long makerFee = reader.longValue();
                long takerFee = reader.longValue();
                long expiry = reader.nonNegativeLong("expiry time");
                int optionTypeCode = reader.intValue();
                if (optionTypeCode < -1 || optionTypeCode >= OptionType.values().length) {
                    throw new ProtocolException("invalid option type: " + optionTypeCode);
                }
                CoreInstrumentState instrument = new CoreInstrumentState(symbol, instrumentVersion,
                        decodedType, baseAsset, quoteAsset, settleAsset, multiplier, priceTick, settleScale,
                        initialMargin, maintenanceMargin, makerFee, takerFee, expiry,
                        optionTypeCode < 0 ? null : OptionType.values()[optionTypeCode],
                        reader.nonNegativeLong("strike price"));
                putUnique(instruments, symbol, instrument);
            }
            Map<String, CoreMarkPriceState> marks = new TreeMap<>();
            int markCount = reader.count("mark prices");
            for (int index = 0; index < markCount; index++) {
                String symbol = reader.text();
                CoreMarkPriceState mark = new CoreMarkPriceState(symbol,
                        reader.positiveLong("mark instrument version"), reader.positiveLong("mark price"),
                        reader.positiveLong("price sequence"));
                putUnique(marks, symbol, mark);
            }
            Map<String, CoreRiskSnapshot> risks = new TreeMap<>();
            int riskCount = reader.count("risk snapshots");
            for (int index = 0; index < riskCount; index++) {
                long userId = reader.positiveLong("risk userId");
                String symbol = reader.text();
                long priceSequence = reader.positiveLong("risk price sequence");
                long equity = reader.longValue();
                long unrealized = reader.longValue();
                long maintenance = reader.nonNegativeLong("maintenance margin");
                long ratio = reader.nonNegativeLong("margin ratio");
                int status = reader.intValue();
                if (status < 0 || status >= CoreRiskStatus.values().length) {
                    throw new ProtocolException("invalid risk status: " + status);
                }
                CoreRiskSnapshot risk = new CoreRiskSnapshot(userId, symbol, priceSequence, equity, unrealized,
                        maintenance, ratio, CoreRiskStatus.values()[status]);
                putUnique(risks, risk.key(), risk);
            }
            Map<Long, CoreLiquidationState> liquidations = new TreeMap<>();
            int liquidationCount = reader.count("liquidations");
            for (int index = 0; index < liquidationCount; index++) {
                long liquidationId = reader.positiveLong("liquidationId");
                long userId = reader.positiveLong("liquidation userId");
                String symbol = reader.text();
                long instrumentVersion = reader.positiveLong("liquidation instrument version");
                long priceSequence = reader.positiveLong("liquidation price sequence");
                long closeQuantity = reader.positiveLong("liquidation close quantity");
                long deficitUnits = reader.nonNegativeLong("liquidation deficit");
                int status = reader.intValue();
                if (status < 0 || status >= CoreLiquidationState.Status.values().length) {
                    throw new ProtocolException("invalid liquidation status: " + status);
                }
                CoreLiquidationState liquidation = new CoreLiquidationState(liquidationId, userId, symbol,
                        instrumentVersion, priceSequence, closeQuantity,
                        deficitUnits, CoreLiquidationState.Status.values()[status]);
                putUnique(liquidations, liquidationId, liquidation);
            }
            String scanSymbol = reader.text();
            CoreRiskState.RiskScan scan = new CoreRiskState.RiskScan(scanSymbol,
                    reader.nonNegativeLong("scan price sequence"), reader.nonNegativeLong("scan userId"),
                    reader.booleanValue());
            riskState = new CoreRiskState(marks, risks, liquidations, scan,
                    reader.positiveLong("next liquidation id"));
            treasuryState = new CoreTreasuryState(readUnits(reader, "fee balances"),
                    readUnits(reader, "insurance balances"), readUnits(reader, "insurance deficits"),
                    readUnits(reader, "funding settlements"), readUnits(reader, "lifecycle settlements"));
        }
        reader.requireConsumed();
        return new TradingCoreState(productLine, revision, users, orders, bookState, instruments, riskState,
                treasuryState);
    }

    private static void writeUnits(Writer writer, Map<String, Long> values) {
        writer.intValue(values.size());
        values.forEach((asset, units) -> {
            writer.text(asset);
            writer.longValue(units);
        });
    }

    private static Map<String, Long> readUnits(Reader reader, String name) {
        Map<String, Long> values = new TreeMap<>();
        int count = reader.count(name);
        for (int index = 0; index < count; index++) {
            putUnique(values, reader.text(), reader.longValue());
        }
        return values;
    }

    private static <K, V> void putUnique(Map<K, V> values, K key, V value) {
        if (values.put(key, value) != null) {
            throw new ProtocolException("duplicate trading snapshot key: " + key);
        }
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
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            if (bytes.length == 0 || bytes.length > MAX_TEXT_BYTES) {
                throw new IllegalArgumentException("invalid snapshot text length");
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
                throw new ProtocolException("trading snapshot is required");
            }
            this.input = input;
        }

        int byteValue() {
            require(Byte.BYTES);
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

        long nonNegativeLong(String field) {
            long value = longValue();
            if (value < 0) {
                throw new ProtocolException(field + " must not be negative");
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
                throw new ProtocolException("invalid snapshot text length: " + length);
            }
            require(length);
            String value = new String(input, offset, length, StandardCharsets.UTF_8);
            offset += length;
            return value;
        }

        boolean booleanValue() {
            int value = byteValue();
            if (value != 0 && value != 1) {
                throw new ProtocolException("invalid boolean value: " + value);
            }
            return value == 1;
        }

        void requireConsumed() {
            if (offset != input.length) {
                throw new ProtocolException("trailing bytes in trading snapshot");
            }
        }

        private void require(int length) {
            if (length < 0 || offset > input.length - length) {
                throw new ProtocolException("truncated trading snapshot");
            }
        }
    }
}
