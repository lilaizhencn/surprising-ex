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
                "客户-71", UUID.fromString("00000000-0000-0000-0000-000000000071"),
                -10, 20, 1_000, 1_001, 99, "OPEN", 1);

        assertThat(CoreStateQueryCodec.decodeUserState(CoreStateQueryCodec.encodeUserState(user))).isEqualTo(user);
        assertThat(CoreStateQueryCodec.decodeOrderState(CoreStateQueryCodec.encodeOrderState(order))).isEqualTo(order);
        assertThat(CoreStateQueryCodec.encodedUserStateLength(user))
                .isEqualTo(CoreStateQueryCodec.encodeUserState(user).length);
        assertThat(CoreStateQueryCodec.encodedOrderStateLength(order))
                .isEqualTo(CoreStateQueryCodec.encodeOrderState(order).length);
        assertThat(CoreStateQueryCodec.decodeClientOrderStateQuery(
                CoreStateQueryCodec.encodeClientOrderStateQuery("client-71"))).isEqualTo("client-71");
    }

    @Test
    void roundTripsFundingProgressQueryAndResponse() {
        assertThat(CoreStateQueryCodec.decodeFundingProgressQuery(
                CoreStateQueryCodec.encodeFundingProgressQuery("btc-usdt"))).isEqualTo("btc-usdt");
        CoreFundingProgressView progress = new CoreFundingProgressView(91, false, 42, 128);
        assertThat(CoreFundingProgressCodec.decode(CoreFundingProgressCodec.encode(progress)))
                .isEqualTo(progress);
    }

    @Test
    void roundTripsCommandResultQuery() {
        UUID commandId = UUID.randomUUID();
        assertThat(CoreStateQueryCodec.decodeCommandResultQuery(
                CoreStateQueryCodec.encodeCommandResultQuery(commandId))).isEqualTo(commandId);
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

    @Test
    void roundTripsBookStateWithExportWatermark() {
        CoreOrderBookView state = new CoreOrderBookView(19,
                List.of(new CoreBookLevelView("BTC-USDT", CoreOrderSide.SELL, 10, 4, 2)));

        assertThat(CoreStateQueryCodec.decodeOrderBookView(CoreStateQueryCodec.encodeOrderBookView(state)))
                .isEqualTo(state);
    }

    @Test
    void roundTripsBoundedBookQuery() {
        CoreOrderBookQuery query = new CoreOrderBookQuery(" btc-usdt ", 25);
        assertThat(CoreStateQueryCodec.decodeOrderBookQuery(
                CoreStateQueryCodec.encodeOrderBookQuery(query))).isEqualTo(query);
        assertThat(new CoreOrderBookQuery("BTC-USDT", 0).depth()).isEqualTo(30);
        assertThatThrownBy(() -> new CoreOrderBookQuery("", 30))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CoreOrderBookQuery("BTC-USDT", 101))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void roundTripsOrderBookBootstrapPage() {
        CoreOrderBookBootstrapQuery first = new CoreOrderBookBootstrapQuery("", "", 10, 0);
        assertThat(CoreStateQueryCodec.decodeOrderBookBootstrapQuery(
                CoreStateQueryCodec.encodeOrderBookBootstrapQuery(first))).isEqualTo(first);

        String snapshotId = "00000000-0000-0000-0000-000000000001";
        CoreOrderBookBootstrapPage page = new CoreOrderBookBootstrapPage(snapshotId, 19,
                "BTC-USDT", false,
                List.of(new CoreBookLevelView("BTC-USDT", CoreOrderSide.SELL, 10, 4, 2)));
        assertThat(CoreStateQueryCodec.decodeOrderBookBootstrapPage(
                CoreStateQueryCodec.encodeOrderBookBootstrapPage(page))).isEqualTo(page);

        assertThatThrownBy(() -> new CoreOrderBookBootstrapQuery("", "BTC-USDT", 10, 30))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void roundTripsOpenOrdersQueryAndView() {
        CoreOpenOrdersQuery query = new CoreOpenOrdersQuery(" btc-usdt ", 71, 25);
        assertThat(CoreStateQueryCodec.decodeOpenOrdersQuery(
                CoreStateQueryCodec.encodeOpenOrdersQuery(query))).isEqualTo(query);

        CoreOrderStateView first = new CoreOrderStateView(71, ProductLine.SPOT, 7, "BTC-USDT", 3,
                CoreOrderSide.BUY, 60_000, 2, 0, 2, false, CoreMarginMode.ISOLATED,
                CorePositionSide.LONG, CoreOrderType.LIMIT, CoreTimeInForce.GTX, true,
                "client-71", UUID.randomUUID(), -10, 20, 1_000, 1_001, 99, "OPEN", 1);
        CoreOrderStateView second = new CoreOrderStateView(70, ProductLine.SPOT, 7, "BTC-USDT", 3,
                CoreOrderSide.SELL, 61_000, 1, 0, 1, false, CoreMarginMode.ISOLATED,
                CorePositionSide.LONG, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false,
                "client-70", UUID.randomUUID(), -10, 20, 1_000, 1_001, 98, "OPEN", 1);
        CoreOpenOrdersView view = new CoreOpenOrdersView(List.of(first, second));
        assertThat(CoreStateQueryCodec.decodeOpenOrders(CoreStateQueryCodec.encodeOpenOrders(view)))
                .isEqualTo(view);
        assertThat(CoreStateQueryCodec.encodedOpenOrdersLength(view))
                .isEqualTo(CoreStateQueryCodec.encodeOpenOrders(view).length);
    }

    @Test
    void rejectsTruncatedOpenOrdersView() {
        CoreOrderStateView order = new CoreOrderStateView(71, ProductLine.SPOT, 7, "BTC-USDT", 3,
                CoreOrderSide.BUY, 60_000, 2, 0, 2, false, "OPEN", 1);
        byte[] encoded = CoreStateQueryCodec.encodeOpenOrders(new CoreOpenOrdersView(List.of(order)));
        assertThatThrownBy(() -> CoreStateQueryCodec.decodeOpenOrders(
                java.util.Arrays.copyOf(encoded, encoded.length - 1)))
                .isInstanceOf(ProtocolException.class);
    }

    @Test
    void roundTripsCommandResultOrdersAndExecutions() {
        CoreOrderStateView order = new CoreOrderStateView(71, ProductLine.SPOT, 7, "BTC-USDT", 3,
                CoreOrderSide.BUY, 60_000, 2, 1, 1, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "client-71", UUID.randomUUID(),
                -10, 20, 1_000, 1_001, 99, "OPEN", 1);
        CoreExecutionView execution = new CoreExecutionView(71, 70, 7, 8, 60_000, 1);
        CoreCommandResultView result = new CoreCommandResultView(99, UUID.randomUUID(), 71, 3, 100,
                17, 19, List.of(order), List.of(execution));

        assertThat(CoreCommandResultCodec.decode(CoreCommandResultCodec.encode(result))).isEqualTo(result);
    }

    @Test
    void rejectsTruncatedCommandResult() {
        CoreCommandResultView result = new CoreCommandResultView(99, UUID.randomUUID(), 71, 3, 100,
                17, 19, List.of(), List.of());
        byte[] encoded = CoreCommandResultCodec.encode(result);

        assertThatThrownBy(() -> CoreCommandResultCodec.decode(
                java.util.Arrays.copyOf(encoded, encoded.length - 1)))
                .isInstanceOf(ProtocolException.class);
    }
}
