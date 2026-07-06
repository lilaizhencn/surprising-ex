package com.surprising.marketmaker.provider.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.account.api.client.AccountRpcApi;
import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.BalanceQueryResponse;
import com.surprising.account.api.model.BalanceResponse;
import com.surprising.account.api.model.PositionMarginAdjustmentRequest;
import com.surprising.account.api.model.PositionMarginAdjustmentResponse;
import com.surprising.account.api.model.PositionMarginResponse;
import com.surprising.account.api.model.PositionModeResponse;
import com.surprising.account.api.model.PositionModeUpdateRequest;
import com.surprising.account.api.model.PositionQueryResponse;
import com.surprising.account.api.model.PositionResponse;
import com.surprising.account.api.model.ProductBalanceQueryResponse;
import com.surprising.account.api.model.ProductBalanceResponse;
import com.surprising.account.api.model.ProductTransferRequest;
import com.surprising.account.api.model.ProductTransferResponse;
import com.surprising.instrument.api.client.InstrumentRpcApi;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.InstrumentQueryResponse;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.marketmaker.api.model.MarketMakerRunRequest;
import com.surprising.marketmaker.api.model.MarketMakerStrategyStatus;
import com.surprising.marketmaker.provider.config.MarketMakerProperties;
import com.surprising.marketmaker.provider.model.ReferenceOrderBookLevel;
import com.surprising.marketmaker.provider.model.ReferenceOrderBookSnapshot;
import com.surprising.marketmaker.provider.model.StrategyConfigOverride;
import com.surprising.marketmaker.provider.repository.MarketMakerAdminRepository;
import com.surprising.marketmaker.provider.repository.MarketMakerAdminRepository.CursorPage;
import com.surprising.marketmaker.provider.repository.MarketMakerAdminRepository.MarketMakerPnlAttributionRecord;
import com.surprising.marketmaker.provider.repository.MarketMakerAdminRepository.MarketMakerPnlScope;
import com.surprising.marketmaker.provider.repository.MarketMakerAdminRepository.MarketMakerReferenceSampleWrite;
import com.surprising.marketmaker.provider.repository.MarketMakerAdminRepository.MarketMakerRunEventRecord;
import com.surprising.marketmaker.provider.repository.MarketMakerAdminRepository.MarketMakerRunEventWrite;
import com.surprising.marketmaker.provider.repository.MarketMakerStrategyOverrideStore;
import com.surprising.price.api.client.MarkPriceRpcApi;
import com.surprising.price.api.model.MarkPriceQueryResponse;
import com.surprising.price.api.model.MarkPriceResponse;
import com.surprising.price.api.model.PriceStatus;
import com.surprising.trading.api.model.AmendOrderBatchResponse;
import com.surprising.trading.api.model.AmendOrderRequest;
import com.surprising.trading.api.model.AmendOrderResponse;
import com.surprising.trading.api.model.AlgoOrderBatchResponse;
import com.surprising.trading.api.model.AlgoOrderQueryResponse;
import com.surprising.trading.api.model.AlgoOrderResponse;
import com.surprising.trading.api.model.BatchAmendOrdersRequest;
import com.surprising.trading.api.model.BatchCancelOrdersRequest;
import com.surprising.trading.api.model.BatchPlaceOrderRequest;
import com.surprising.trading.api.model.CancelAllAfterRequest;
import com.surprising.trading.api.model.CancelAllAfterResponse;
import com.surprising.trading.api.model.CancelAlgoOrderRequest;
import com.surprising.trading.api.model.CancelOrderRequest;
import com.surprising.trading.api.model.CancelOpenAlgoOrdersRequest;
import com.surprising.trading.api.model.CancelOpenOrdersRequest;
import com.surprising.trading.api.model.ClosePositionRequest;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderBookLevel;
import com.surprising.trading.api.model.OrderBookSnapshotResponse;
import com.surprising.trading.api.model.OrderBatchResponse;
import com.surprising.trading.api.model.OrderQueryResponse;
import com.surprising.trading.api.model.OrderResponse;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PlaceAlgoOrderRequest;
import com.surprising.trading.api.model.PlaceOrderRequest;
import com.surprising.trading.api.model.PositionMode;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TestOrderResponse;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.api.client.MarketDataRpcApi;
import com.surprising.trading.api.client.OrderRpcApi;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    void runOnceRecordsReferenceMarketSampleWhenSnapshotIsAvailable() {
        Fixtures fixtures = new Fixtures(List.of());
        FakeAdminRepository adminRepository = new FakeAdminRepository();
        ReferenceOrderBookSnapshot snapshot = new ReferenceOrderBookSnapshot("BINANCE_USDM", "WEBSOCKET", "BTC-USDT",
                List.of(new ReferenceOrderBookLevel(49_990L, 3L)),
                List.of(new ReferenceOrderBookLevel(50_020L, 7L)),
                Instant.parse("2026-01-01T00:00:01Z"));
        MarketMakerService service = fixtures.service(adminRepository, (symbol, instrument) -> snapshot);

        service.runOnce(new MarketMakerRunRequest("btc-usdt-mm-a", "BTC-USDT"));

        assertThat(adminRepository.referenceSamples).singleElement()
                .satisfies(sample -> {
                    assertThat(sample.strategyId()).isEqualTo("btc-usdt-mm-a");
                    assertThat(sample.symbol()).isEqualTo("BTC-USDT");
                    assertThat(sample.sourceName()).isEqualTo("BINANCE_USDM");
                    assertThat(sample.transport()).isEqualTo("WEBSOCKET");
                    assertThat(sample.bidLevels()).isEqualTo(1);
                    assertThat(sample.askLevels()).isEqualTo(1);
                    assertThat(sample.spreadTicks()).isEqualTo(30L);
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

    @Test
    void adminMetricsReportsQuoteQualityAndAnomalies() {
        Fixtures fixtures = new Fixtures(List.of());
        MarketMakerService service = fixtures.service();

        var metrics = service.adminMetrics(100);

        assertThat(metrics.nodeId()).isEqualTo("mm-test");
        assertThat(metrics.totals().strategyCount()).isEqualTo(1L);
        assertThat(metrics.totals().metricRows()).isEqualTo(1L);
        assertThat(metrics.totals().criticalAnomalies()).isEqualTo(1L);
        assertThat(metrics.totals().warnAnomalies()).isEqualTo(1L);
        assertThat(metrics.rows()).singleElement()
                .satisfies(row -> {
                    assertThat(row.strategyId()).isEqualTo("btc-usdt-mm-a");
                    assertThat(row.symbol()).isEqualTo("BTC-USDT");
                    assertThat(row.accountId()).isEqualTo(900001L);
                    assertThat(row.qualityStatus()).isEqualTo("CRITICAL");
                    assertThat(row.desiredQuoteCount()).isEqualTo(6L);
                    assertThat(row.missingDesiredQuotes()).isEqualTo(6L);
                    assertThat(row.ownedOpenOrders()).isZero();
                    assertThat(row.quoteCoveragePpm()).isZero();
                    assertThat(row.bestBidTicks()).isEqualTo(49_990L);
                    assertThat(row.bestAskTicks()).isEqualTo(50_010L);
                    assertThat(row.markPriceTicks()).isEqualTo(50_000L);
                });
        assertThat(metrics.anomalies())
                .extracting(MarketMakerService.MarketMakerAnomaly::type)
                .containsExactlyInAnyOrder("MISSING_DESIRED_QUOTES", "NO_LIVE_QUOTES");
    }

    @Test
    void updateStrategyConfigAppliesPersistentOverridesToQuotePlanning() {
        Fixtures fixtures = new Fixtures(List.of());
        MarketMakerService service = fixtures.service();

        var config = service.updateStrategyConfig("btc-usdt-mm-a",
                new MarketMakerService.MarketMakerStrategyConfigUpdateRequest(
                        true, 25L, "CROSS", 40L, 12L, 500L, 500_000L, 2, "quote tuning"),
                "1001");
        service.runOnce(new MarketMakerRunRequest("btc-usdt-mm-a", "BTC-USDT"));

        assertThat(config.effective().baseQuantitySteps()).isEqualTo(25L);
        assertThat(config.effective().orderLevels()).isEqualTo(2);
        assertThat(config.override().updatedByAdminUserId()).isEqualTo("1001");
        assertThat(fixtures.orderRpc.placeRequests).hasSize(4);
        assertThat(fixtures.orderRpc.placeRequests)
                .allSatisfy(request -> assertThat(request.quantitySteps()).isEqualTo(25L));
    }

    @Test
    void runOnceRecordsStrategyRunEvents() {
        Fixtures fixtures = new Fixtures(List.of());
        FakeAdminRepository adminRepository = new FakeAdminRepository();
        MarketMakerService service = fixtures.service(adminRepository);

        service.runOnce(new MarketMakerRunRequest("btc-usdt-mm-a", "BTC-USDT"));

        assertThat(adminRepository.events)
                .extracting(MarketMakerRunEventWrite::eventType)
                .contains("QUOTE_RECONCILED", "CYCLE_SUCCESS");
        assertThat(adminRepository.events)
                .filteredOn(event -> "QUOTE_RECONCILED".equals(event.eventType()))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.strategyId()).isEqualTo("btc-usdt-mm-a");
                    assertThat(event.symbol()).isEqualTo("BTC-USDT");
                    assertThat(event.accountId()).isEqualTo(900001L);
                    assertThat(event.submittedOrders()).isEqualTo(6L);
                    assertThat(event.traceId()).isNotBlank();
                });
    }

    @Test
    void runLogsWithCursorDelegatesNormalizedFiltersToRepository() {
        Fixtures fixtures = new Fixtures(List.of());
        FakeAdminRepository adminRepository = new FakeAdminRepository();
        MarketMakerService service = fixtures.service(adminRepository);
        Instant createdAt = Instant.parse("2026-07-03T00:00:00Z");
        adminRepository.runEventPage = new CursorPage<>(List.of(new MarketMakerRunEventRecord(
                77L, "btc-usdt-mm-a", "BTC-USDT", 900001L, "mm-test", 9L,
                "QUOTE_RECONCILED", 2L, 1L, 0L, null, null, "trace-1", createdAt)),
                "next", true, "createdAt.desc", 25);

        var response = service.runLogs("btc-usdt-mm-a", "btc-usdt", 900001L,
                "QUOTE_RECONCILED", 25, "cursor", "createdAt.desc");

        assertThat(response.events()).singleElement()
                .satisfies(event -> assertThat(event.eventId()).isEqualTo(77L));
        assertThat(response.nextCursor()).isEqualTo("next");
        assertThat(response.hasMore()).isTrue();
        assertThat(response.sort()).isEqualTo("createdAt.desc");
        assertThat(response.limit()).isEqualTo(25);
        assertThat(adminRepository.lastRunEventsPageStrategyId).isEqualTo("BTC-USDT-MM-A");
        assertThat(adminRepository.lastRunEventsPageSymbol).isEqualTo("BTC-USDT");
        assertThat(adminRepository.lastRunEventsPageCursor).isEqualTo("cursor");
        assertThat(adminRepository.lastRunEventsPageSort).isEqualTo("createdAt.desc");
    }

    @Test
    void pnlAttributionUsesConfiguredStrategyScopesOnly() {
        Fixtures fixtures = new Fixtures(List.of());
        FakeAdminRepository adminRepository = new FakeAdminRepository();
        MarketMakerService service = fixtures.service(adminRepository);

        var response = service.pnlAttribution("btc-usdt-mm-a", "BTC-USDT", 900001L, 24, 10);

        assertThat(response.totals().rowCount()).isEqualTo(1L);
        assertThat(adminRepository.scopes).singleElement()
                .satisfies(scope -> {
                    assertThat(scope.strategyId()).isEqualTo("btc-usdt-mm-a");
                    assertThat(scope.symbol()).isEqualTo("BTC-USDT");
                    assertThat(scope.accountId()).isEqualTo(900001L);
                    assertThat(scope.clientOrderPrefix()).startsWith("mm-").endsWith("-900001-");
                });
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
            return service(new FakeAdminRepository());
        }

        private MarketMakerService service(MarketMakerAdminRepository adminRepository) {
            return service(adminRepository, ReferenceMarketProvider.disabled());
        }

        private MarketMakerService service(MarketMakerAdminRepository adminRepository,
                                           ReferenceMarketProvider referenceMarketProvider) {
            MarketMakerProperties properties = properties();
            return new MarketMakerService(properties, new FakeInstrumentRpc(), new FakeMarkPriceRpc(),
                    new FakeMarketDataRpc(), orderRpc, new FakeAccountRpc(), new QuotePlanner(),
                    referenceMarketProvider, (strategyId, symbol, ownerId, leaseDuration) -> true,
                    new FakeOverrideStore(), adminRepository);
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

    private static final class FakeOverrideStore implements MarketMakerStrategyOverrideStore {
        private final Map<String, StrategyConfigOverride> overrides = new HashMap<>();

        @Override
        public List<StrategyConfigOverride> findAll() {
            return List.copyOf(overrides.values());
        }

        @Override
        public Optional<StrategyConfigOverride> find(String strategyId) {
            return Optional.ofNullable(overrides.get(strategyId));
        }

        @Override
        public StrategyConfigOverride save(StrategyConfigOverride override) {
            long version = overrides.containsKey(override.strategyId())
                    ? overrides.get(override.strategyId()).version() + 1L
                    : 1L;
            StrategyConfigOverride saved = new StrategyConfigOverride(
                    override.strategyId(), override.enabled(), override.baseQuantitySteps(), override.marginMode(),
                    override.spreadTicks(), override.levelSpacingTicks(), override.maxInventorySteps(),
                    override.maxInventorySkewPpm(), override.orderLevels(), override.updatedByAdminUserId(),
                    override.reason(), override.updatedAt(), version);
            overrides.put(saved.strategyId(), saved);
            return saved;
        }

        @Override
        public void delete(String strategyId) {
            overrides.remove(strategyId);
        }
    }

    private static final class FakeAdminRepository implements MarketMakerAdminRepository {
        private final List<MarketMakerRunEventWrite> events = new ArrayList<>();
        private final List<MarketMakerReferenceSampleWrite> referenceSamples = new ArrayList<>();
        private List<MarketMakerPnlScope> scopes = List.of();
        private CursorPage<MarketMakerRunEventRecord> runEventPage =
                new CursorPage<>(List.of(), null, false, "createdAt.desc", 100);
        private String lastRunEventsPageStrategyId;
        private String lastRunEventsPageSymbol;
        private Long lastRunEventsPageAccountId;
        private String lastRunEventsPageEventType;
        private int lastRunEventsPageLimit;
        private String lastRunEventsPageCursor;
        private String lastRunEventsPageSort;

        @Override
        public void recordRunEvent(MarketMakerRunEventWrite event) {
            events.add(event);
        }

        @Override
        public void recordReferenceSample(MarketMakerReferenceSampleWrite sample) {
            referenceSamples.add(sample);
        }

        @Override
        public List<MarketMakerRunEventRecord> runEvents(String strategyId,
                                                         String symbol,
                                                         Long accountId,
                                                         String eventType,
                                                         int limit) {
            return List.of();
        }

        @Override
        public CursorPage<MarketMakerRunEventRecord> runEventsPage(String strategyId,
                                                                   String symbol,
                                                                   Long accountId,
                                                                   String eventType,
                                                                   int limit,
                                                                   String cursor,
                                                                   String sort) {
            lastRunEventsPageStrategyId = strategyId;
            lastRunEventsPageSymbol = symbol;
            lastRunEventsPageAccountId = accountId;
            lastRunEventsPageEventType = eventType;
            lastRunEventsPageLimit = limit;
            lastRunEventsPageCursor = cursor;
            lastRunEventsPageSort = sort;
            return runEventPage;
        }

        @Override
        public List<MarketMakerPnlAttributionRecord> pnlAttribution(List<MarketMakerPnlScope> scopes,
                                                                    Instant since,
                                                                    Instant until) {
            this.scopes = List.copyOf(scopes);
            return scopes.stream()
                    .map(scope -> new MarketMakerPnlAttributionRecord(
                            scope.strategyId(), scope.symbol(), scope.accountId(), scope.marginMode(),
                            2L, 0L, 1L, 1L, 2L, 10L, 10L, 20L,
                            "1000000", 12L, 2L, 50L, 0L,
                            Instant.parse("2026-01-01T00:00:00Z"),
                            Instant.parse("2026-01-01T00:00:01Z"),
                            Instant.parse("2026-01-01T00:00:02Z")))
                    .toList();
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
        public OrderBatchResponse placeBatch(BatchPlaceOrderRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TestOrderResponse test(PlaceOrderRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AmendOrderResponse amend(AmendOrderRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AmendOrderBatchResponse amendBatch(BatchAmendOrdersRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public OrderResponse closePosition(ClosePositionRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public OrderResponse cancel(CancelOrderRequest request) {
            cancelRequests.add(request);
            return order(request.orderId(), request.userId(), "canceled", OrderSide.BUY,
                    1L, 0L, OrderStatus.CANCELED);
        }

        @Override
        public OrderBatchResponse cancelBatch(BatchCancelOrdersRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public OrderBatchResponse cancelOpen(CancelOpenOrdersRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CancelAllAfterResponse cancelAllAfter(CancelAllAfterRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AlgoOrderResponse placeAlgo(PlaceAlgoOrderRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AlgoOrderResponse cancelAlgo(CancelAlgoOrderRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AlgoOrderBatchResponse cancelOpenAlgo(CancelOpenAlgoOrdersRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AlgoOrderResponse getAlgo(long algoOrderId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AlgoOrderQueryResponse openAlgoOrders(long userId, String symbol, int limit) {
            throw new UnsupportedOperationException();
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
                    10_000_000L, 3, null, null, null, null, null, null, null,
                    InstrumentStatus.TRADING, now, now, now, List.of(), List.of());
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
        public ProductBalanceResponse productBalance(long userId, AccountType accountType, String asset) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ProductBalanceQueryResponse productBalances(long userId, AccountType accountType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ProductTransferResponse transfer(ProductTransferRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PositionModeResponse positionMode(long userId) {
            return new PositionModeResponse(userId, PositionMode.ONE_WAY, Instant.parse("2026-01-01T00:00:00Z"));
        }

        @Override
        public PositionModeResponse updatePositionMode(PositionModeUpdateRequest request) {
            return new PositionModeResponse(request.userId(), request.positionMode(),
                    Instant.parse("2026-01-01T00:00:00Z"));
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
