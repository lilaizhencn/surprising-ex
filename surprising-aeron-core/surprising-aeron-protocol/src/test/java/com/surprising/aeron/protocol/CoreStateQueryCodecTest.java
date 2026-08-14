package com.surprising.aeron.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CoreStateQueryCodecTest {

    @Test
    void roundTripsUserAndOrderViews() {
        CoreUserStateView user = new CoreUserStateView(ProductLine.LINEAR_PERPETUAL, 7, 3,
                CorePositionMode.HEDGE,
                List.of(new CoreBalanceView("USDT", 900, 100)),
                List.of(new CoreReservationView(71, "BTC-USDT", 3, ReservationKind.DERIVATIVE_MARGIN,
                        "USDT", 100, 0, 0, 2)),
                List.of(new CorePositionView("BTC-USDT", "USDT", CoreMarginMode.ISOLATED,
                        CorePositionSide.LONG, 3, 2, 60_000, 120_000, 0, 100)),
                List.of(new CoreLeverageView("BTC-USDT", CoreMarginMode.ISOLATED, 5_000_000L)));
        CoreOrderStateView order = new CoreOrderStateView(71, ProductLine.SPOT, 7, "BTC-USDT", 3,
                CoreOrderSide.BUY, 60_000, 2, 0, 2, false, CoreMarginMode.ISOLATED,
                CorePositionSide.LONG, CoreOrderType.LIMIT, CoreTimeInForce.GTX, true,
                "client-71", UUID.fromString("00000000-0000-0000-0000-000000000071"),
                -10, 20, 1_000, 1_001, 99, "OPEN", 1);

        assertThat(CoreStateQueryCodec.decodeUserState(CoreStateQueryCodec.encodeUserState(user))).isEqualTo(user);
        assertThat(CoreStateQueryCodec.decodeOrderState(CoreStateQueryCodec.encodeOrderState(order))).isEqualTo(order);
        assertThat(CoreStateQueryCodec.decodeClientOrderStateQuery(
                CoreStateQueryCodec.encodeClientOrderStateQuery("client-71"))).isEqualTo("client-71");
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
