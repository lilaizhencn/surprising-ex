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
        byte[] versionTwo = TradingStateSnapshotCodec.encode(state);
        int bookOffset = versionOneBoundary(versionTwo);
        byte[] versionOne = java.util.Arrays.copyOf(versionTwo, bookOffset);
        ByteBuffer.wrap(versionOne).order(ByteOrder.LITTLE_ENDIAN).putInt(1);

        TradingCoreState restored = TradingStateSnapshotCodec.decode(versionOne, ProductLine.SPOT);

        assertThat(restored.bookState().openOrders()).containsKey(71L);
        assertThat(restored.bookState().openOrders().get(71L).prioritySequence()).isOne();
    }

    private static int versionOneBoundary(byte[] snapshot) {
        ByteBuffer input = ByteBuffer.wrap(snapshot).order(ByteOrder.LITTLE_ENDIAN);
        input.getInt();
        input.getInt();
        input.getLong();
        int userCount = input.getInt();
        for (int userIndex = 0; userIndex < userCount; userIndex++) {
            input.getLong();
            input.getLong();
            int balanceCount = input.getInt();
            for (int balanceIndex = 0; balanceIndex < balanceCount; balanceIndex++) {
                skipText(input);
                input.position(input.position() + Long.BYTES * 2);
            }
            int reservationCount = input.getInt();
            for (int reservationIndex = 0; reservationIndex < reservationCount; reservationIndex++) {
                input.getLong();
                skipText(input);
                input.getLong();
                input.getInt();
                skipText(input);
                input.position(input.position() + Long.BYTES * 4);
            }
            int positionCount = input.getInt();
            for (int positionIndex = 0; positionIndex < positionCount; positionIndex++) {
                skipText(input);
                skipText(input);
                input.position(input.position() + Long.BYTES * 6);
            }
        }
        int orderCount = input.getInt();
        for (int orderIndex = 0; orderIndex < orderCount; orderIndex++) {
            input.position(input.position() + Long.BYTES * 2);
            skipText(input);
            input.getLong();
            input.getInt();
            input.position(input.position() + Long.BYTES * 4);
            input.get();
            input.getInt();
            input.getLong();
        }
        return input.position();
    }

    private static void skipText(ByteBuffer input) {
        int length = input.getInt();
        input.position(input.position() + length);
    }
}
