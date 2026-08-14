package com.surprising.trading.matching.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreBookStateView;
import com.surprising.aeron.protocol.CoreBookLevelView;
import com.surprising.aeron.protocol.CoreExecutionView;
import com.surprising.aeron.protocol.CoreExportCodec;
import com.surprising.aeron.protocol.CoreExportEvent;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderStateView;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.OrderBookDepthEvent;
import com.surprising.trading.api.model.PublicTradeEvent;
import com.surprising.trading.matching.config.MatchingProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CoreMarketDataProjectionTest {

    @Test
    void bootstrapsFromAeronAndAppliesContiguousCoreEvents() {
        MatchingProperties properties = new MatchingProperties();
        properties.getKafka().setProductLine(ProductLine.SPOT);
        properties.getKafka().setProductTopicsEnabled(true);
        MatchingAeronGateway gateway = mock(MatchingAeronGateway.class);
        when(gateway.bookState()).thenReturn(new CoreBookStateView(1,
                List.of(new CoreBookLevelView("BTC-USDT", CoreOrderSide.SELL, 10, 10, 1))));
        List<OrderBookDepthEvent> depths = new ArrayList<>();
        List<PublicTradeEvent> trades = new ArrayList<>();
        LatestPublicTradeCache cache = new LatestPublicTradeCache();
        CoreMarketDataProjection projection = new CoreMarketDataProjection(
                properties, gateway, depths::add, trades::add, cache);
        projection.initialize();

        CoreOrderStateView maker = order(2, 202, CoreOrderSide.SELL, 6, 4, "OPEN", -50, 100);
        CoreOrderStateView taker = order(1, 101, CoreOrderSide.BUY, 6, 0, "FILLED", 0, 200);
        UUID commandId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        CoreExportEvent event = new CoreExportEvent(2, 2, 99, commandId, CoreMessageType.PLACE_ORDER,
                ResponseStatus.APPLIED, CoreResultCode.NONE, 101, new byte[0], List.of(),
                List.of(taker, maker), List.of(new CoreExecutionView(1, 2, 101, 202, 10, 6)));
        CoreMessage message = new CoreMessage(CoreMessageHeader.command(CoreMessageType.PLACE_ORDER, commandId,
                ProductLine.SPOT, CommandSource.GATEWAY, 7, 2, 101, 1_000, 2).exportEvent(2),
                CoreExportCodec.encodeEvent(event));

        projection.apply(message);

        assertThat(projection.snapshot("BTC-USDT", 10).asks()).singleElement()
                .satisfies(level -> {
                    assertThat(level.priceTicks()).isEqualTo(10);
                    assertThat(level.quantitySteps()).isEqualTo(4);
                    assertThat(level.orderCount()).isEqualTo(1);
                });
        assertThat(depths).singleElement().satisfies(depth -> {
            assertThat(depth.sequence()).isEqualTo(2);
            assertThat(depth.asks()).singleElement()
                    .satisfies(level -> assertThat(level.quantitySteps()).isEqualTo(4));
        });
        assertThat(trades).singleElement().satisfies(trade -> {
            assertThat(trade.symbol()).isEqualTo("BTC-USDT");
            assertThat(trade.quantitySteps()).isEqualTo(6);
            assertThat(trade.sequence()).isEqualTo(2_000_000);
        });
        assertThat(cache.latest("BTC-USDT")).contains(trades.getFirst());
    }

    @Test
    void appliesCancellationAndNewRestingOrderWithoutRetainingStaleLevels() {
        ProjectionFixture fixture = fixture(new CoreBookStateView(5,
                List.of(new CoreBookLevelView("BTC-USDT", CoreOrderSide.BUY, 10, 10, 1))));
        UUID cancelCommandId = new UUID(0, 600);
        CoreOrderStateView canceled = order(1, 101, CoreOrderSide.BUY, 0, 10, "CANCELED", 0, 0,
                new UUID(0, 100));

        fixture.projection().apply(message(6, cancelCommandId, List.of(canceled), List.of()));

        assertThat(fixture.projection().snapshot("BTC-USDT", 10).bids()).isEmpty();
        UUID placeCommandId = new UUID(0, 700);
        CoreOrderStateView resting = order(2, 202, CoreOrderSide.SELL, 0, 7, "OPEN", 0, 0,
                placeCommandId);
        fixture.projection().apply(message(7, placeCommandId, List.of(resting), List.of()));

        assertThat(fixture.projection().snapshot("BTC-USDT", 10).asks()).singleElement()
                .satisfies(level -> {
                    assertThat(level.quantitySteps()).isEqualTo(7);
                    assertThat(level.orderCount()).isEqualTo(1);
                });
    }

    @Test
    void removesFullyFilledMakerLevel() {
        ProjectionFixture fixture = fixture(new CoreBookStateView(1,
                List.of(new CoreBookLevelView("BTC-USDT", CoreOrderSide.SELL, 10, 6, 1))));
        CoreOrderStateView maker = order(2, 202, CoreOrderSide.SELL, 6, 0, "FILLED", 0, 0);
        CoreOrderStateView taker = order(1, 101, CoreOrderSide.BUY, 6, 0, "FILLED", 0, 0);

        fixture.projection().apply(message(2, new UUID(0, 900), List.of(taker, maker),
                List.of(new CoreExecutionView(1, 2, 101, 202, 10, 6))));

        assertThat(fixture.projection().snapshot("BTC-USDT", 10).asks()).isEmpty();
        assertThat(fixture.trades()).hasSize(1);
    }

    @Test
    void rejectsSequenceGapBeforeMutatingBook() {
        ProjectionFixture fixture = fixture(new CoreBookStateView(1, List.of()));
        UUID commandId = new UUID(0, 300);
        CoreOrderStateView resting = order(3, 303, CoreOrderSide.BUY, 0, 4, "OPEN", 0, 0, commandId);

        assertThatThrownBy(() -> fixture.projection().apply(
                message(3, commandId, List.of(resting), List.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-contiguous Core events");
        assertThat(fixture.projection().appliedExportSequence()).isEqualTo(1);
        assertThat(fixture.projection().snapshot("BTC-USDT", 10).bids()).isEmpty();
    }

    private static ProjectionFixture fixture(CoreBookStateView bootstrap) {
        MatchingProperties properties = new MatchingProperties();
        properties.getKafka().setProductLine(ProductLine.SPOT);
        properties.getKafka().setProductTopicsEnabled(true);
        MatchingAeronGateway gateway = mock(MatchingAeronGateway.class);
        when(gateway.bookState()).thenReturn(bootstrap);
        List<OrderBookDepthEvent> depths = new ArrayList<>();
        List<PublicTradeEvent> trades = new ArrayList<>();
        CoreMarketDataProjection projection = new CoreMarketDataProjection(properties, gateway, depths::add,
                trades::add, new LatestPublicTradeCache());
        projection.initialize();
        return new ProjectionFixture(projection, depths, trades);
    }

    private static CoreMessage message(long exportSequence, UUID commandId,
                                       List<CoreOrderStateView> orders, List<CoreExecutionView> executions) {
        long userId = orders.isEmpty() ? 1 : orders.getFirst().userId();
        CoreExportEvent event = new CoreExportEvent(exportSequence, exportSequence, 99, commandId,
                CoreMessageType.PLACE_ORDER, ResponseStatus.APPLIED, CoreResultCode.NONE, userId,
                new byte[0], List.of(), orders, executions);
        return new CoreMessage(CoreMessageHeader.command(CoreMessageType.PLACE_ORDER, commandId,
                ProductLine.SPOT, CommandSource.GATEWAY, 7, exportSequence, userId, 1_000, exportSequence)
                .exportEvent(exportSequence), CoreExportCodec.encodeEvent(event));
    }

    private static CoreOrderStateView order(long orderId, long userId, CoreOrderSide side,
                                             long executed, long remaining, String status,
                                             long makerFee, long takerFee) {
        return order(orderId, userId, side, executed, remaining, status, makerFee, takerFee,
                new UUID(0, orderId));
    }

    private static CoreOrderStateView order(long orderId, long userId, CoreOrderSide side,
                                             long executed, long remaining, String status,
                                             long makerFee, long takerFee, UUID commandId) {
        return new CoreOrderStateView(orderId, ProductLine.SPOT, userId, "BTC-USDT", 1, side,
                10, Math.addExact(executed, remaining), executed, remaining, false,
                CoreMarginMode.CROSS, CorePositionSide.NET,
                CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "client-" + orderId,
                commandId, makerFee, takerFee, 900, 1_000, 2, status, 2);
    }

    private record ProjectionFixture(CoreMarketDataProjection projection,
                                     List<OrderBookDepthEvent> depths,
                                     List<PublicTradeEvent> trades) {
    }
}
