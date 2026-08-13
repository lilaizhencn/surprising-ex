package com.surprising.aeron.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TradingCommandCodecTest {

    @Test
    void roundTripsAllP2Commands() {
        BalanceAdjustmentCommand adjustment = new BalanceAdjustmentCommand("USDT", 10_000);
        PlaceOrderCommand placeOrder = new PlaceOrderCommand(7, "BTC-USDT", 3, "BTC", "USDT", "USDT",
                CoreOrderSide.BUY, 60_000, 3, false,
                ReservationKind.SPOT_ASSET, "USDT", 2_000);
        CancelOrderCommand cancelOrder = new CancelOrderCommand(7);

        assertThat(TradingCommandCodec.decodeBalanceAdjustment(
                TradingCommandCodec.encodeBalanceAdjustment(adjustment))).isEqualTo(adjustment);
        assertThat(TradingCommandCodec.decodePlaceOrder(
                TradingCommandCodec.encodePlaceOrder(placeOrder))).isEqualTo(placeOrder);
        assertThat(TradingCommandCodec.decodeCancelOrder(
                TradingCommandCodec.encodeCancelOrder(cancelOrder))).isEqualTo(cancelOrder);
    }

    @Test
    void rejectsTruncatedAndTrailingPayloads() {
        byte[] valid = TradingCommandCodec.encodeBalanceAdjustment(
                new BalanceAdjustmentCommand("USDT", 1));
        byte[] trailing = java.util.Arrays.copyOf(valid, valid.length + 1);

        assertThatThrownBy(() -> TradingCommandCodec.decodeBalanceAdjustment(
                java.util.Arrays.copyOf(valid, valid.length - 1)))
                .isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> TradingCommandCodec.decodeBalanceAdjustment(trailing))
                .isInstanceOf(ProtocolException.class);
    }

    @Test
    void responseRoundTripPreservesOriginalCommandStatusAndData() {
        CoreResponse response = new CoreResponse(ResponseStatus.DUPLICATE, ResponseStatus.REJECTED,
                CoreResultCode.INSUFFICIENT_AVAILABLE_BALANCE, 9, 17, new byte[] {1, 2, 3});

        CoreResponse restored = CoreProtocol.decodeResponse(CoreProtocol.responsePayload(response));

        assertThat(restored.status()).isEqualTo(response.status());
        assertThat(restored.commandStatus()).isEqualTo(response.commandStatus());
        assertThat(restored.resultCode()).isEqualTo(response.resultCode());
        assertThat(restored.appliedCommandCount()).isEqualTo(response.appliedCommandCount());
        assertThat(restored.stateHash()).isEqualTo(response.stateHash());
        assertThat(restored.data()).containsExactly(response.data());
    }
}
