package com.surprising.aeron.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.product.api.ProductLine;
import java.util.List;
import org.junit.jupiter.api.Test;

class CoreStateQueryCodecTest {

    @Test
    void roundTripsUserAndOrderViews() {
        CoreUserStateView user = new CoreUserStateView(ProductLine.SPOT, 7, 3,
                List.of(new CoreBalanceView("USDT", 900, 100)),
                List.of(new CoreReservationView(71, "BTC-USDT", 3, ReservationKind.SPOT_ASSET,
                        "USDT", 100, 0, 0, 2)), List.of());
        CoreOrderStateView order = new CoreOrderStateView(71, ProductLine.SPOT, 7, "BTC-USDT", 3,
                CoreOrderSide.BUY, 60_000, 2, 0, 2, false, "OPEN", 1);

        assertThat(CoreStateQueryCodec.decodeUserState(CoreStateQueryCodec.encodeUserState(user))).isEqualTo(user);
        assertThat(CoreStateQueryCodec.decodeOrderState(CoreStateQueryCodec.encodeOrderState(order))).isEqualTo(order);
    }

    @Test
    void rejectsTruncatedQueryView() {
        byte[] encoded = CoreStateQueryCodec.encodeOrderState(new CoreOrderStateView(
                1, ProductLine.SPOT, 7, "BTC-USDT", 3, CoreOrderSide.BUY,
                1, 1, 0, 1, false, "OPEN", 1));

        assertThatThrownBy(() -> CoreStateQueryCodec.decodeOrderState(
                java.util.Arrays.copyOf(encoded, encoded.length - 1)))
                .isInstanceOf(ProtocolException.class);
    }
}
