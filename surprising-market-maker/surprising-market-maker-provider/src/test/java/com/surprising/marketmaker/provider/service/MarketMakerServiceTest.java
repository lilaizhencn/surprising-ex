package com.surprising.marketmaker.provider.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.account.api.client.AccountRpcApi;
import com.surprising.account.api.model.BalanceQueryResponse;
import com.surprising.account.api.model.BalanceResponse;
import com.surprising.account.api.model.PositionMarginAdjustmentRequest;
import com.surprising.account.api.model.PositionMarginAdjustmentResponse;
import com.surprising.account.api.model.PositionMarginResponse;
import com.surprising.account.api.model.PositionQueryResponse;
import com.surprising.account.api.model.PositionResponse;
import com.surprising.instrument.api.client.InstrumentRpcApi;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.InstrumentQueryResponse;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.marketmaker.api.model.MarketMakerRunRequest;
import com.surprising.marketmaker.api.model.MarketMakerStrategyStatus;
import com.surprising.marketmaker.provider.config.MarketMakerProperties;
import com.surprising.price.api.client.MarkPriceRpcApi;
import com.surprising.price.api.model.MarkPriceQueryResponse;
import com.surprising.price.api.model.MarkPriceResponse;
import com.surprising.price.api.model.PriceStatus;
import com.surprising.trading.api.model.CancelOrderRequest;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderBookLevel;
import com.surprising.trading.api.model.OrderBookSnapshotResponse;
import com.surprising.trading.api.model.OrderQueryResponse;
import com.surprising.trading.api.model.OrderResponse;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PlaceOrderRequest;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.api.client.MarketDataRpcApi;
import com.surprising.trading.api.client.OrderRpcApi;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;

class MarketMakerServiceTest {

    @Test
    void runOncePlacesPostOnlyQuotesThroughOrderRpc() {
        Fixtures fixtures = new Fixtures(List.of());
        MarketMakerService service = fixtures.service();

        var response = service.runOnce(new MarketMakerRunRequest("btc-usdt-mm-a", "BTC-USDT"));

        assertThat(response.strategies()).singleElement()
                .satisfies(strategy -> {
                    assertThat(strategy.status()).isEqualTo(MarketMakerStrategyStatus.RUNNING);
                    assertThat(strategy.lastTraceId()).isNotBlank();
                });
        assertThat(fixtures.orderRpc.placeRequests).hasSize(6);
        assertThat(fixtures.orderRpc.placeRequests)
                .allSatisfy(request -> {
                    assertThat(request.timeInForce()).isEqualTo(TimeInForce.GTX);
                    assertThat(request.postOnly()).isTrue();
                    assertThat(request.orderType()).isEqualTo(OrderType.LIMIT);
                    assertThat(request.userId()).isEqualTo(900001L);
                });
    }

    @Test
    void runOnceCancelsOffTargetOwnedQuotesBeforeReposting() {
        String prefix = accountPrefix("btc-usdt-mm-a", "BTC-USDT", 900001L);
        OrderResponse staleBid = order(7L, 900001L, prefix + "b0-1", OrderSide.BUY,
                49_000L, 10L, OrderStatus.ACCEPTED);
        Fixtures fixtures = new Fixtures(List.of(staleBid));
        MarketMakerService service = fixtures.service();

        service.runOnce(new MarketMakerRunRequest("btc-usdt-mm-a", "BTC-USDT"));

        assertThat(fixtures.orderRpc.cancelRequests).singleElement()
                .extracting(CancelOrderRequest::orderId)
                .isEqualTo(7L);
        assertThat(fixtures.orderRpc.placeRequests).hasSize(6);
    }

    @Test
    void restartedProviderDoesNotReuseClientOrderIdsFromPreviousInstance() {
        Fixtures first = new Fixtures(List.of());
        first.service().runOnce(new MarketMakerRunRequest("btc-usdt-mm-a", "BTC-USDT"));
        List<String> firstIds = first.orderRpc.placeRequests.stream()
                .map(PlaceOrderRequest::clientOrderId)
                .toList();

        Fixtures second = new Fixtures(List.of());
        second.service().runOnce(new MarketMakerRunRequest("btc-usdt-mm-a", "BTC-USDT"));
        List<String> secondIds = second.orderRpc.placeRequests.stream()
                .map(PlaceOrderRequest::clientOrderId)
                .toList();

        assertThat(secondIds).doesNotContainAnyElementsOf(firstIds);
        assertThat(secondIds).allSatisfy(id -> assertThat(id).startsWith("mm-"));
    }

    private static String accountPrefix(String strategyId, String symbol, long accountId) {
        CRC32 crc32 = new CRC32();
        crc32.update((strategyId + ":" + symbol).getBytes(StandardCharsets.UTF_8));
        return "mm-" + Long.toUnsignedString(crc32.getValue(), 36) + "-" + accountId + "-";
    }

    private static OrderResponse order(long orderId,
                                       long userId,
                                       String clientOrderId,
                                       OrderSide side,
                                       long priceTicks,
                                       long remainingQuantity,
                                       OrderStatus status) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new OrderResponse(orderId, userId, clientOrderId, "BTC-USDT", 1L, side, OrderType.LIMIT,
                TimeInForce.GTX, priceTicks, remainingQuantity, 0L, remainingQuantity, MarginMode.CROSS,
                PositionSide.NET, -100L, 500L, false, true, status, null, now, now);
    }

    private static final class Fixtures {
        private final FakeOrderRpc orderRpc;

        private Fixtures(List<OrderResponse> openOrders) {
            this.orderRpc = new FakeOrderRpc(openOrders);
        }

        private MarketMakerService service() {
            MarketMakerProperties properties = properties();
            return new MarketMakerService(properties, new FakeInstrumentRpc(), new FakeMarkPriceRpc(),
                    new FakeMarketDataRpc(), orderRpc, new FakeAccountRpc(), new QuotePlanner(),
                    (strategyId, symbol, ownerId, leaseDuration) -> true);
        }

        private MarketMakerProperties properties() {
            MarketMakerProperties properties = new MarketMakerProperties();
            properties.getEngine().setNodeId("mm-test");
            properties.getCoordination().setEnabled(false);
            properties.getQuoting().setOrderLevels(3);
            properties.getQuoting().setMinSpreadTicks(10L);
            properties.getQuoting().setLevelSpacingTicks(10L);
            properties.getQuoting().setRefreshThresholdTicks(2L);
            properties.getRisk().setMaxInventorySteps(1000L);
            MarketMakerProperties.Strategy strategy = new MarketMakerProperties.Strategy();
            strategy.setStrategyId("btc-usdt-mm-a");
            strategy.setEnabled(true);
            strategy.setAccountIds(List.of(900001L));
            strategy.setSymbols(List.of("BTC-USDT"));
            strategy.setBaseQuantitySteps(10L);
            strategy.setMarginMode(MarginMode.CROSS);
            properties.setStrategies(List.of(strategy));
            return properties;
        }
    }

    private static final class FakeOrderRpc implements OrderRpcApi {
        private final List<OrderResponse> openOrders;
        private final List<PlaceOrderRequest> placeRequests = new ArrayList<>();
        private final List<CancelOrderRequest> cancelRequests = new ArrayList<>();

        private FakeOrderRpc(List<OrderResponse> openOrders) {
            this.openOrders = new ArrayList<>(openOrders);
        }

        @Override
        public OrderResponse place(PlaceOrderRequest request) {
            placeRequests.add(request);
            return order(1000L + placeRequests.size(), request.userId(), request.clientOrderId(), request.side(),
                    request.priceTicks(), request.quantitySteps(), OrderStatus.ACCEPTED);
        }

        @Override
        public OrderResponse cancel(CancelOrderRequest request) {
            cancelRequests.add(request);
            return order(request.orderId(), request.userId(), "canceled", OrderSide.BUY,
                    1L, 0L, OrderStatus.CANCELED);
        }

        @Override
        public OrderResponse get(long orderId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public OrderResponse getByClientOrderId(long userId, String clientOrderId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public OrderQueryResponse openOrders(long userId, String symbol, int limit) {
            return new OrderQueryResponse(openOrders.size(), openOrders);
        }
    }

    private static final class FakeMarketDataRpc implements MarketDataRpcApi {
        @Override
        public OrderBookSnapshotResponse orderBook(String symbol, int depth) {
            Instant now = Instant.parse("2026-01-01T00:00:00Z");
            return new OrderBookSnapshotResponse(symbol, 1L, depth,
                    List.of(new OrderBookLevel(49_990L, 100L, 1L)),
                    List.of(new OrderBookLevel(50_010L, 100L, 1L)), now);
        }
    }

    private static final class FakeMarkPriceRpc implements MarkPriceRpcApi {
        @Override
        public MarkPriceResponse latestMarkPrice(String symbol) {
            Instant now = Instant.parse("2026-01-01T00:00:00Z");
            return new MarkPriceResponse(symbol, BigDecimal.valueOf(50_000L), 5_000_000L,
                    BigDecimal.valueOf(50_000L), BigDecimal.valueOf(50_000L), BigDecimal.valueOf(50_000L),
                    BigDecimal.valueOf(50_000L), BigDecimal.valueOf(49_990L), BigDecimal.valueOf(50_010L),
                    BigDecimal.ZERO, now.plusSeconds(3600), 3600L, BigDecimal.ZERO, 60L,
                    BigDecimal.valueOf(49_000L), BigDecimal.valueOf(51_000L), 1L, PriceStatus.HEALTHY, now);
        }

        @Override
        public MarkPriceQueryResponse history(String symbol, Instant startTime, Instant endTime, int limit) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakeInstrumentRpc implements InstrumentRpcApi {
        @Override
        public InstrumentResponse latest(String symbol) {
            Instant now = Instant.parse("2026-01-01T00:00:00Z");
            return new InstrumentResponse(symbol, 1L, InstrumentType.PERPETUAL, ContractType.LINEAR_PERPETUAL,
                    "BTC", "USDT", "USDT", 1_000_000L, "BTC", 100L, 1L, 1L, 1_000_000L,
                    1L, 1_000_000_000_000L, 1L, 2, 0, List.of("LIMIT"), List.of("GTX"), true,
                    true, true, 100_000_000L, 10_000L, 5_000L, -100L, 500L,
                    1_000_000_000L, 300_000L, 250_000_000L, 8, 100L, 3_000L, -3_000L,
                    10_000_000L, 3, InstrumentStatus.TRADING, now, now, now, List.of(), List.of());
        }

        @Override
        public InstrumentResponse version(String symbol, long version) {
            return latest(symbol);
        }

        @Override
        public InstrumentQueryResponse list(InstrumentType type, InstrumentStatus status) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakeAccountRpc implements AccountRpcApi {
        @Override
        public BalanceResponse balance(long userId, String asset) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BalanceQueryResponse balances(long userId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PositionResponse position(long userId, String symbol, String marginMode, String positionSide) {
            return new PositionResponse(userId, symbol, 1L, MarginMode.CROSS, PositionSide.NET,
                    0L, 0L, 0L, Instant.parse("2026-01-01T00:00:00Z"));
        }

        @Override
        public PositionMarginResponse positionMargin(long userId, String symbol, String marginMode) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PositionMarginAdjustmentResponse adjustPositionMargin(PositionMarginAdjustmentRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PositionQueryResponse positions(long userId, String positionSide) {
            throw new UnsupportedOperationException();
        }
    }
}
