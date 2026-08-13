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
        TradingCoreState state = reducer.adjustBalance(TradingCoreState.empty(ProductLine.OPTION), 7,
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
        TradingCoreState state = reducer.adjustBalance(TradingCoreState.empty(ProductLine.SPOT), 7,
                new BalanceAdjustmentCommand("USDT", 50_000));
        state = reducer.placeOrder(state, 7, new PlaceOrderCommand(71, "BTC-USDT", 4, "BTC", "USDT", "USDT",
                CoreOrderSide.BUY, 500, 2, false, ReservationKind.SPOT_ASSET, "USDT", 1_500));
        byte[] versionTwo = TradingStateSnapshotCodec.encode(state);
        int bookOffset = versionTwo.length - (Long.BYTES + Integer.BYTES);
        byte[] versionOne = java.util.Arrays.copyOf(versionTwo, bookOffset);
        ByteBuffer.wrap(versionOne).order(ByteOrder.LITTLE_ENDIAN).putInt(1);

        TradingCoreState restored = TradingStateSnapshotCodec.decode(versionOne, ProductLine.SPOT);

        assertThat(restored.bookState().openOrders()).containsKey(71L);
        assertThat(restored.bookState().openOrders().get(71L).prioritySequence()).isOne();
    }
}
