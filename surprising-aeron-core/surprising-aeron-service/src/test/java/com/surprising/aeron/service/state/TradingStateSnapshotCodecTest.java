package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ProtocolException;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.product.api.ProductLine;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

class TradingStateSnapshotCodecTest {

    @Test
    void roundTripPreservesBusinessAndEntityHashes() {
        TradingCoreReducer reducer = new TradingCoreReducer();
        TradingCoreState state = reducer.adjustBalance(
                reducer.upsertInstrument(TradingCoreState.empty(ProductLine.OPTION),
                        CoreStateTestFixtures.instrument(ProductLine.OPTION,
                                "BTC-OPTION", "BTC", "USDT", "USDT", 4)), 7,
                new BalanceAdjustmentCommand("USDT", 50_000));
        state = reducer.placeOrder(state, 7, new PlaceOrderCommand(71, "BTC-OPTION", 4, "BTC", "USDT", "USDT",
                CoreOrderSide.BUY, 500, 2, false, ReservationKind.DERIVATIVE_MARGIN, "USDT", 1_500));

        TradingCoreState restored = TradingStateSnapshotCodec.decode(
                TradingStateSnapshotCodec.encode(state), ProductLine.OPTION);

        assertThat(restored).isEqualTo(state);
        assertThat(restored.businessStateHash()).isEqualTo(state.businessStateHash());
        assertThat(restored.userStateHash(7)).isEqualTo(state.userStateHash(7));
        assertThat(restored.orderStateHash(71)).isEqualTo(state.orderStateHash(71));
    }

    @Test
    void rejectsProductLineMismatchAndTruncation() {
        byte[] encoded = TradingStateSnapshotCodec.encode(TradingCoreState.empty(ProductLine.SPOT));

        assertThatThrownBy(() -> TradingStateSnapshotCodec.decode(encoded, ProductLine.OPTION))
                .isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> TradingStateSnapshotCodec.decode(
                java.util.Arrays.copyOf(encoded, encoded.length - 1), ProductLine.SPOT))
                .isInstanceOf(ProtocolException.class);
    }

    @Test
    void migratesVersionOneOpenOrdersIntoDeterministicBookPriority() {
        TradingCoreReducer reducer = new TradingCoreReducer();
        TradingCoreState state = reducer.adjustBalance(
                reducer.upsertInstrument(TradingCoreState.empty(ProductLine.SPOT),
                        CoreStateTestFixtures.instrument(ProductLine.SPOT,
                                "BTC-USDT", "BTC", "USDT", "USDT", 4)), 7,
                new BalanceAdjustmentCommand("USDT", 50_000));
        state = reducer.placeOrder(state, 7, new PlaceOrderCommand(71, "BTC-USDT", 4, "BTC", "USDT", "USDT",
                CoreOrderSide.BUY, 500, 2, false, ReservationKind.SPOT_ASSET, "USDT", 1_500));
        byte[] versionOne = legacyVersionOne(state);

        TradingCoreState restored = TradingStateSnapshotCodec.decode(versionOne, ProductLine.SPOT);

        assertThat(restored.bookState().openOrders()).containsKey(71L);
        assertThat(restored.bookState().openOrders().get(71L).prioritySequence()).isOne();
    }

    private static byte[] legacyVersionOne(TradingCoreState state) {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        writeInt(output, 1);
        writeInt(output, com.surprising.aeron.protocol.ProductLineWireCode.encode(state.productLine()));
        writeLong(output, state.revision());
        writeInt(output, state.users().size());
        for (CoreUserState user : state.users().values()) {
            writeLong(output, user.userId());
            writeLong(output, user.revision());
            writeInt(output, user.balances().size());
            user.balances().values().forEach(balance -> {
                writeText(output, balance.asset());
                writeLong(output, balance.availableUnits());
                writeLong(output, balance.lockedUnits());
            });
            writeInt(output, user.reservations().size());
            user.reservations().values().forEach(reservation -> {
                writeLong(output, reservation.orderId());
                writeText(output, reservation.symbol());
                writeLong(output, reservation.instrumentVersion());
                writeInt(output, reservation.kind().wireCode());
                writeText(output, reservation.asset());
                writeLong(output, reservation.reservedUnits());
                writeLong(output, reservation.releasedUnits());
                writeLong(output, reservation.consumedUnits());
                writeLong(output, reservation.orderQuantitySteps());
            });
            writeInt(output, user.positions().size());
            user.positions().values().forEach(position -> {
                writeText(output, position.symbol());
                writeText(output, position.marginAsset());
                writeLong(output, position.instrumentVersion());
                writeLong(output, position.signedQuantitySteps());
                writeLong(output, position.entryPriceTicks());
                writeLong(output, position.entryValueTicks());
                writeLong(output, position.realizedPnlUnits());
                writeLong(output, position.positionMarginUnits());
            });
        }
        writeInt(output, state.orders().size());
        state.orders().values().forEach(order -> {
            writeLong(output, order.orderId());
            writeLong(output, order.userId());
            writeText(output, order.symbol());
            writeLong(output, order.instrumentVersion());
            writeInt(output, order.side().wireCode());
            writeLong(output, order.priceTicks());
            writeLong(output, order.quantitySteps());
            writeLong(output, order.executedQuantitySteps());
            writeLong(output, order.remainingQuantitySteps());
            output.write(order.reduceOnly() ? 1 : 0);
            writeInt(output, order.status().ordinal());
            writeLong(output, order.revision());
        });
        return output.toByteArray();
    }

    private static void writeText(java.io.ByteArrayOutputStream output, String value) {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writeInt(output, bytes.length);
        output.writeBytes(bytes);
    }

    private static void writeInt(java.io.ByteArrayOutputStream output, int value) {
        for (int shift = 0; shift < Integer.SIZE; shift += Byte.SIZE) output.write(value >>> shift);
    }

    private static void writeLong(java.io.ByteArrayOutputStream output, long value) {
        for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) output.write((int) (value >>> shift));
    }
}
