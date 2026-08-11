package com.surprising.marketmaker.provider.service;

import com.surprising.account.api.client.AccountRpcApi;
import com.surprising.account.api.model.PositionResponse;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.marketmaker.api.model.MarketMakerRunRequest;
import com.surprising.marketmaker.api.model.MarketMakerStrategyQueryResponse;
import com.surprising.marketmaker.api.model.MarketMakerStrategyResponse;
import com.surprising.marketmaker.api.model.MarketMakerStrategyStatus;
import com.surprising.marketmaker.provider.config.MarketMakerProductLineContext;
import com.surprising.marketmaker.provider.config.MarketMakerProperties;
import com.surprising.marketmaker.provider.model.DesiredQuote;
import com.surprising.marketmaker.provider.model.QuotePlan;
import com.surprising.marketmaker.provider.model.ReferenceOrderBookSnapshot;
import com.surprising.marketmaker.provider.model.StrategyConfigOverride;
import com.surprising.marketmaker.provider.model.StrategyRuntimeState;
import com.surprising.marketmaker.provider.repository.MarketMakerReferenceSampleRepository;
import com.surprising.marketmaker.provider.repository.MarketMakerReferenceSampleRepository.MarketMakerReferenceSampleWrite;
import com.surprising.marketmaker.provider.repository.MarketMakerRunEventRepository;
import com.surprising.marketmaker.provider.repository.MarketMakerRunEventRepository.CursorPage;
import com.surprising.marketmaker.provider.repository.MarketMakerRunEventRepository.MarketMakerRunEventRecord;
import com.surprising.marketmaker.provider.repository.MarketMakerRunEventRepository.MarketMakerRunEventWrite;
import com.surprising.marketmaker.provider.repository.MarketMakerStrategyOverrideStore;
import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.api.model.MarkPriceResponse;
import com.surprising.price.consumer.LatestMarkPriceCache;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.TraceContext;
import com.surprising.trading.api.model.BatchCancelOrdersRequest;
import com.surprising.trading.api.model.CancelOrderRequest;
import com.surprising.trading.api.model.BatchPlaceOrderRequest;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderBookLevel;
import com.surprising.trading.api.model.OrderBookSnapshotResponse;
import com.surprising.trading.api.model.OrderBatchResponse;
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
import feign.FeignException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ThreadLocalRandom;
import java.util.zip.CRC32;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class MarketMakerService {

    private static final Logger log = LoggerFactory.getLogger(MarketMakerService.class);
    private static final int MAX_BATCH_PLACE_ORDERS = 20;
    // 预占结果通过账户 Kafka 异步返回。预占中的报价仍然占用一个报价槽位，
    // 若在下一轮被当成非活动订单撤掉，会形成“永远预占不成功”的并发活锁。
    private static final Set<OrderStatus> LIVE_STATUSES = EnumSet.of(
            OrderStatus.ACCEPTED, OrderStatus.PARTIALLY_FILLED, OrderStatus.PENDING_RESERVE);

    private final MarketMakerProperties properties;
    private final InstrumentSnapshotCache instrumentSnapshotCache;
    private final LatestMarkPriceCache markPriceCache;
    private final MarketDataRpcApi marketDataRpcApi;
    private final OrderRpcApi orderRpcApi;
    private final AccountRpcApi accountRpcApi;
    private final QuotePlanner quotePlanner;
    private final ReferenceMarketProvider referenceMarketProvider;
    private final MarketMakerLeaseCoordinator leaseCoordinator;
    private final MarketMakerStrategyOverrideStore overrideStore;
    private final MarketMakerRunEventRepository runEventRepository;
    private final MarketMakerReferenceSampleRepository referenceSampleRepository;
    private final Map<String, StrategyRuntimeState> states = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> cycleLocks = new ConcurrentHashMap<>();
    private final Map<String, CachedOpenOrders> openOrderSnapshots = new ConcurrentHashMap<>();
    private final Map<String, PriceState> priceStates = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastTradeTimes = new ConcurrentHashMap<>();
    private final Map<String, OrderSide> lastTradeSides = new ConcurrentHashMap<>();
    private volatile Map<String, StrategyConfigOverride> strategyOverrides = Map.of();
    private volatile Instant strategyOverridesLoadedAt = Instant.EPOCH;
    private final String nodeId;
    private final String orderNonce;

    @Autowired
    public MarketMakerService(MarketMakerProperties properties,
                              LatestMarkPriceCache markPriceCache,
                              MarketDataRpcApi marketDataRpcApi,
                              OrderRpcApi orderRpcApi,
                              AccountRpcApi accountRpcApi,
                              QuotePlanner quotePlanner,
                              ReferenceMarketProvider referenceMarketProvider,
                              MarketMakerLeaseCoordinator leaseCoordinator,
                              MarketMakerStrategyOverrideStore overrideStore,
                              MarketMakerRunEventRepository runEventRepository,
                              MarketMakerReferenceSampleRepository referenceSampleRepository,
                              InstrumentSnapshotCache instrumentSnapshotCache) {
        this.properties = properties;
        this.instrumentSnapshotCache = instrumentSnapshotCache;
        this.markPriceCache = markPriceCache;
        this.marketDataRpcApi = marketDataRpcApi;
        this.orderRpcApi = orderRpcApi;
        this.accountRpcApi = accountRpcApi;
        this.quotePlanner = quotePlanner;
        this.referenceMarketProvider = referenceMarketProvider;
        this.leaseCoordinator = leaseCoordinator;
        this.overrideStore = overrideStore;
        this.runEventRepository = runEventRepository;
        this.referenceSampleRepository = referenceSampleRepository;
        this.nodeId = resolveNodeId(properties.getEngine().getNodeId());
        this.orderNonce = Long.toUnsignedString(System.currentTimeMillis(), 36)
                + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public void scheduledRun() {
        if (!properties.getEngine().isEnabled()) {
            return;
        }
        runOnce(new MarketMakerRunRequest(null, null));
    }

    public MarketMakerStrategyQueryResponse strategies() {
        return strategies(null);
    }

    private InstrumentResponse currentInstrument(ProductLine productLine, String symbol) {
        if (productLine == null || instrumentSnapshotCache == null
                || !instrumentSnapshotCache.initialized(productLine)) {
            throw new IllegalStateException("做市合约 JVM 快照尚未就绪: " + productLine);
        }
        return instrumentSnapshotCache.current(productLine, symbol)
                .orElseThrow(() -> new IllegalStateException("合约快照中不存在品种: " + productLine + "/" + symbol));
    }

    public MarketMakerStrategyQueryResponse strategies(ProductLine productLine) {
        List<MarketMakerStrategyResponse> responses = strategiesSnapshot(productLine).stream()
                .map(this::response)
                .toList();
        return new MarketMakerStrategyQueryResponse(responses.size(), responses);
    }

    public MarketMakerStrategyResponse strategy(String strategyId) {
        return strategy(strategyId, null);
    }

    public MarketMakerStrategyResponse strategy(String strategyId, ProductLine productLine) {
        return response(findStrategy(strategyId, productLine));
    }

    public MarketMakerStrategyResponse pause(String strategyId) {
        return pause(strategyId, null);
    }

    public MarketMakerStrategyResponse pause(String strategyId, ProductLine productLine) {
        MarketMakerProperties.Strategy strategy = findStrategy(strategyId, productLine);
        state(strategy).pause();
        return response(strategy);
    }

    public MarketMakerStrategyResponse resume(String strategyId) {
        return resume(strategyId, null);
    }

    public MarketMakerStrategyResponse resume(String strategyId, ProductLine productLine) {
        MarketMakerProperties.Strategy strategy = findStrategy(strategyId, productLine);
        state(strategy).resume();
        return response(strategy);
    }

    public MarketMakerStrategyConfigResponse strategyConfig(String strategyId) {
        return strategyConfig(strategyId, null);
    }

    public MarketMakerStrategyConfigResponse strategyConfig(String strategyId, ProductLine productLine) {
        MarketMakerProperties.Strategy configured = findConfiguredStrategy(strategyId, productLine);
        StrategyConfigOverride override = strategyOverrides().get(strategyKey(configured));
        return configResponse(configured, override);
    }

    public MarketMakerStrategyConfigResponse updateStrategyConfig(String strategyId,
                                                                  MarketMakerStrategyConfigUpdateRequest request,
                                                                  String adminUserId) {
        return updateStrategyConfig(strategyId, null, request, adminUserId);
    }

    public MarketMakerStrategyConfigResponse updateStrategyConfig(String strategyId,
                                                                  ProductLine productLine,
                                                                  MarketMakerStrategyConfigUpdateRequest request,
                                                                  String adminUserId) {
        MarketMakerProperties.Strategy configured = findConfiguredStrategy(strategyId, productLine);
        MarketMakerStrategyConfigUpdateRequest safeRequest = request == null
                ? new MarketMakerStrategyConfigUpdateRequest(null, null, null, null, null, null, null, null, null)
                : request;
        String reason = normalizeReason(safeRequest.reason());
        StrategyConfigOverride override = new StrategyConfigOverride(
                configured.getStrategyId(),
                configured.getProductLine(),
                safeRequest.enabled(),
                positiveOrNull(safeRequest.baseQuantitySteps(), "baseQuantitySteps"),
                parseMarginMode(safeRequest.marginMode()),
                nonNegativeOrNull(safeRequest.spreadTicks(), "spreadTicks"),
                nonNegativeOrNull(safeRequest.levelSpacingTicks(), "levelSpacingTicks"),
                positiveOrNull(safeRequest.maxInventorySteps(), "maxInventorySteps"),
                boundedLongOrNull(safeRequest.maxInventorySkewPpm(), 0L, 1_000_000L, "maxInventorySkewPpm"),
                boundedIntOrNull(safeRequest.orderLevels(), 1, 20, "orderLevels"),
                normalizeRequired(adminUserId, "adminUserId"),
                reason,
                Instant.now(),
                0L);
        StrategyConfigOverride saved = null;
        if (override.hasParameterOverride()) {
            saved = overrideStore.save(override);
            putCachedOverride(saved);
        } else {
            overrideStore.delete(configured.getProductLine(), configured.getStrategyId());
            removeCachedOverride(configured);
        }
        return configResponse(configured, saved);
    }

    public MarketMakerStrategyQueryResponse runOnce(MarketMakerRunRequest request) {
        String traceId = TraceContext.currentOrCreate();
        String requestedStrategyId = normalizeOptional(request == null ? null : request.strategyId());
        String requestedSymbol = normalizeOptional(request == null ? null : request.symbol());
        ProductLine requestedProductLine = request == null ? null : request.productLine();
        try {
            for (MarketMakerProperties.Strategy strategy : strategiesSnapshot(requestedProductLine)) {
                if (requestedStrategyId != null && !strategy.getStrategyId().equalsIgnoreCase(requestedStrategyId)) {
                    continue;
                }
                runStrategy(strategy, requestedSymbol, traceId);
            }
            return strategies();
        } finally {
            TraceContext.clear();
        }
    }

    public MarketMakerAdminMetricsResponse adminMetrics(int limit) {
        return adminMetrics(limit, null);
    }

    public MarketMakerAdminMetricsResponse adminMetrics(int limit, ProductLine productLine) {
        int boundedLimit = Math.max(1, Math.min(limit, 500));
        Instant now = Instant.now();
        List<MarketMakerStrategyMetric> rows = new ArrayList<>();
        List<MarketMakerAnomaly> anomalies = new ArrayList<>();
        List<MarketMakerMetricWarning> warnings = new ArrayList<>();
        for (MarketMakerProperties.Strategy strategy : strategiesSnapshot(productLine)) {
            for (String configuredSymbol : strategy.getSymbols()) {
                if (rows.size() >= boundedLimit) {
                    break;
                }
                String symbol = normalizeSymbol(configuredSymbol);
                for (long accountId : strategy.getAccountIds()) {
                    if (rows.size() >= boundedLimit) {
                        break;
                    }
                    rows.add(strategyMetric(strategy, symbol, accountId, now, anomalies, warnings));
                }
            }
        }
        return new MarketMakerAdminMetricsResponse(now, nodeId, totals(productLine, rows, anomalies), rows, anomalies, warnings);
    }

    public MarketMakerRunLogQueryResponse runLogs(String strategyId,
                                                  String symbol,
                                                  Long accountId,
                                                  String eventType,
                                                  int limit) {
        return runLogs(null, strategyId, symbol, accountId, eventType, limit);
    }

    public MarketMakerRunLogQueryResponse runLogs(ProductLine productLine,
                                                  String strategyId,
                                                  String symbol,
                                                  Long accountId,
                                                  String eventType,
                                                  int limit) {
        return new MarketMakerRunLogQueryResponse(
                Instant.now(),
                runEventRepository.find(
                        productLine,
                        normalizeOptional(strategyId),
                        symbol == null || symbol.isBlank() ? null : normalizeSymbol(symbol),
                        accountId,
                        normalizeOptional(eventType),
                        limit));
    }

    public MarketMakerRunLogQueryResponse runLogs(String strategyId,
                                                  String symbol,
                                                  Long accountId,
                                                  String eventType,
                                                  int limit,
                                                  String cursor,
                                                  String sort) {
        return runLogs(null, strategyId, symbol, accountId, eventType, limit, cursor, sort);
    }

    public MarketMakerRunLogQueryResponse runLogs(ProductLine productLine,
                                                  String strategyId,
                                                  String symbol,
                                                  Long accountId,
                                                  String eventType,
                                                  int limit,
                                                  String cursor,
                                                  String sort) {
        CursorPage<MarketMakerRunEventRecord> page = runEventRepository.findPage(
                productLine,
                normalizeOptional(strategyId),
                symbol == null || symbol.isBlank() ? null : normalizeSymbol(symbol),
                accountId,
                normalizeOptional(eventType),
                limit,
                cursor,
                sort);
        return new MarketMakerRunLogQueryResponse(Instant.now(), page.items(), page.nextCursor(),
                page.hasMore(), page.sort(), page.limit());
    }

    private MarketMakerStrategyMetric strategyMetric(MarketMakerProperties.Strategy strategy,
                                                     String symbol,
                                                     long accountId,
                                                     Instant now,
                                                     List<MarketMakerAnomaly> anomalies,
                                                     List<MarketMakerMetricWarning> warnings) {
        StrategyRuntimeState state = state(strategy);
        MarketMakerStrategyStatus strategyStatus = status(strategy, state);
        String strategyId = strategy.getStrategyId();
        ProductLine productLine = strategy.getProductLine();
        String accountPrefix = accountPrefix(strategy, symbol, accountId);
        List<MarketMakerAnomaly> rowAnomalies = new ArrayList<>();
        ProductLine previousProductLine = MarketMakerProductLineContext.current();
        MarketMakerProductLineContext.set(productLine);
        if (!strategy.isEnabled()) {
            rowAnomalies.add(anomaly("INFO", "STRATEGY_DISABLED", strategyId, productLine, symbol, accountId,
                    0, 1, "strategy is disabled by configuration"));
        }
        if (state.paused()) {
            rowAnomalies.add(anomaly("INFO", "STRATEGY_PAUSED", strategyId, productLine, symbol, accountId,
                    1, 0, "strategy is paused at runtime"));
        }
        if (state.lastError() != null) {
            rowAnomalies.add(anomaly("CRITICAL", "LAST_CYCLE_FAILED", strategyId, productLine, symbol, accountId,
                    1, 0, state.lastError()));
        }
        try {
            List<OrderResponse> openOrders = openOrders(productLine, accountId, symbol, now);
            List<OrderResponse> ownedLive = openOrders.stream()
                    .filter(order -> ownsOrder(accountPrefix, order))
                    .filter(this::isLive)
                    .toList();
            long staleOwned = ownedLive.stream().filter(order -> isStale(order, now)).count();
            InstrumentResponse instrument = currentInstrument(productLine, symbol);
            PositionResponse position = currentPosition(strategy, accountId, symbol, instrument);
            OrderBookSnapshotResponse orderBook = marketDataRpcApi.orderBook(symbol,
                    properties.getQuoting().getOrderBookDepth());
            MarkPriceResponse markPrice = currentMarkPrice(productLine, symbol, instrument.version());
            ReferenceOrderBookSnapshot referenceOrderBook = referenceMarketProvider.snapshot(symbol, productLine, instrument);
            QuotePlan plan = !isTradableForProduct(instrument, productLine)
                    ? new QuotePlan(0L, position.signedQuantitySteps(), List.of())
                    : quotePlanner.plan(strategy, properties.getQuoting(), properties.getRisk(), instrument,
                    orderBook, markPrice, position.signedQuantitySteps(), currentVolatility(strategy, symbol),
                    referenceOrderBook);
            int desiredQuotes = plan.quotes().size();
            long matchedDesired = plan.quotes().stream()
                    .filter(quote -> hasLiveQuote(ownedLive, quote, accountPrefix))
                    .count();
            long offTargetOwned = ownedLive.stream()
                    .filter(order -> !isStale(order, now))
                    .filter(order -> plan.quotes().stream().noneMatch(quote -> matchesQuote(order, quote, accountPrefix)))
                    .count();
            long missingDesired = Math.max(0, desiredQuotes - matchedDesired);
            long maxInventory = effectiveMaxInventorySteps(strategy);
            long absInventory = Math.abs(position.signedQuantitySteps());
            long inventoryUsagePpm = maxInventory <= 0 ? 0 : Math.min(10_000_000L,
                    Math.round(absInventory * 1_000_000.0d / maxInventory));
            long bestBid = bestBid(orderBook);
            long bestAsk = bestAsk(orderBook);
            long spreadTicks = bestBid > 0 && bestAsk > 0 ? Math.max(0, bestAsk - bestBid) : 0;
            long midTicks = midPriceTicks(orderBook);
            long spreadPpm = midTicks <= 0 || spreadTicks <= 0 ? 0
                    : Math.round(spreadTicks * 1_000_000.0d / midTicks);
            long quoteCoveragePpm = desiredQuotes <= 0 ? 0
                    : Math.round(matchedDesired * 1_000_000.0d / desiredQuotes);
            long markTicks = markPriceTicks(instrument, markPrice);

            if (inventoryUsagePpm >= 1_000_000L) {
                rowAnomalies.add(anomaly("CRITICAL", "INVENTORY_LIMIT_REACHED", strategyId, productLine, symbol, accountId,
                        absInventory, maxInventory, "signed inventory reached configured limit"));
            } else if (inventoryUsagePpm >= Math.max(0L, effectiveInventorySkewPpm(strategy))) {
                rowAnomalies.add(anomaly("WARN", "INVENTORY_SKEW_HIGH", strategyId, productLine, symbol, accountId,
                        inventoryUsagePpm, effectiveInventorySkewPpm(strategy), "inventory usage exceeds skew threshold"));
            }
            if (desiredQuotes > 0 && missingDesired > 0) {
                rowAnomalies.add(anomaly("WARN", "MISSING_DESIRED_QUOTES", strategyId, productLine, symbol, accountId,
                        missingDesired, desiredQuotes, "some desired quote levels are not live"));
            }
            if (ownedLive.isEmpty() && strategy.isEnabled() && !state.paused()) {
                rowAnomalies.add(anomaly("CRITICAL", "NO_LIVE_QUOTES", strategyId, productLine, symbol, accountId,
                        0, desiredQuotes, "no owned live quotes are present"));
            }
            if (staleOwned > 0) {
                rowAnomalies.add(anomaly("WARN", "STALE_QUOTES", strategyId, productLine, symbol, accountId,
                        staleOwned, 0, "owned live quotes exceed stale age"));
            }
            if (offTargetOwned > 0) {
                rowAnomalies.add(anomaly("WARN", "OFF_TARGET_QUOTES", strategyId, productLine, symbol, accountId,
                        offTargetOwned, 0, "owned live quotes do not match target levels"));
            }
            if (!isTradableForProduct(instrument, productLine)) {
                rowAnomalies.add(anomaly("CRITICAL", "INSTRUMENT_NOT_TRADING", strategyId, productLine, symbol, accountId,
                        1, 0, "instrument is unavailable or not TRADING"));
            }
            anomalies.addAll(rowAnomalies);
            return new MarketMakerStrategyMetric(
                    strategyId, productLine, symbol, accountId, strategyStatus, qualityStatus(rowAnomalies),
                    strategy.isEnabled(), state.paused(), state.cycleSequence(), state.submittedOrders(),
                    state.canceledOrders(), state.rejectedOrders(), state.skippedCycles(),
                    position.signedQuantitySteps(), absInventory, maxInventory, inventoryUsagePpm,
                    position.realizedPnlUnits(), position.updatedAt(), ownedLive.size(),
                    ownedLive.stream().filter(order -> order.side() == OrderSide.BUY).count(),
                    ownedLive.stream().filter(order -> order.side() == OrderSide.SELL).count(),
                    desiredQuotes,
                    plan.quotes().stream().filter(quote -> quote.side() == OrderSide.BUY).count(),
                    plan.quotes().stream().filter(quote -> quote.side() == OrderSide.SELL).count(),
                    matchedDesired, missingDesired, staleOwned, offTargetOwned, bestBid, bestAsk,
                    spreadTicks, spreadPpm, markTicks, quoteCoveragePpm, state.lastTraceId(),
                    state.lastError(), state.lastCycleTime(), null);
        } catch (RuntimeException ex) {
            String message = ex.getMessage();
            anomalies.add(anomaly("CRITICAL", "METRIC_COLLECTION_FAILED", strategyId, productLine, symbol, accountId,
                    1, 0, message));
            warnings.add(new MarketMakerMetricWarning(strategyId, productLine, symbol, accountId, message));
            return new MarketMakerStrategyMetric(
                    strategyId, productLine, symbol, accountId, strategyStatus, "CRITICAL", strategy.isEnabled(), state.paused(),
                    state.cycleSequence(), state.submittedOrders(), state.canceledOrders(), state.rejectedOrders(),
                    state.skippedCycles(), 0, 0, effectiveMaxInventorySteps(strategy), 0, 0, null,
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    state.lastTraceId(), state.lastError(), state.lastCycleTime(), message);
        } finally {
            MarketMakerProductLineContext.set(previousProductLine);
        }
    }

    private MarketMakerMetricsTotals totals(ProductLine productLine,
                                            List<MarketMakerStrategyMetric> rows,
                                            List<MarketMakerAnomaly> anomalies) {
        List<MarketMakerStrategyResponse> strategies = strategies(productLine).strategies();
        return new MarketMakerMetricsTotals(
                strategies.size(),
                strategies.stream().filter(MarketMakerStrategyResponse::configuredEnabled).count(),
                strategies.stream().filter(item -> item.status() == MarketMakerStrategyStatus.RUNNING).count(),
                strategies.stream().filter(item -> item.status() == MarketMakerStrategyStatus.DEGRADED).count(),
                strategies.stream().filter(item -> item.status() == MarketMakerStrategyStatus.PAUSED).count(),
                strategies.stream().filter(item -> item.status() == MarketMakerStrategyStatus.DISABLED).count(),
                rows.size(),
                strategies.stream().mapToLong(MarketMakerStrategyResponse::submittedOrders).sum(),
                strategies.stream().mapToLong(MarketMakerStrategyResponse::canceledOrders).sum(),
                strategies.stream().mapToLong(MarketMakerStrategyResponse::rejectedOrders).sum(),
                strategies.stream().mapToLong(MarketMakerStrategyResponse::skippedCycles).sum(),
                anomalies.size(),
                anomalies.stream().filter(item -> "CRITICAL".equals(item.severity())).count(),
                anomalies.stream().filter(item -> "WARN".equals(item.severity())).count());
    }

    private long effectiveMaxInventorySteps(MarketMakerProperties.Strategy strategy) {
        return strategy.getMaxInventorySteps() == null || strategy.getMaxInventorySteps() <= 0
                ? properties.getRisk().getMaxInventorySteps()
                : strategy.getMaxInventorySteps();
    }

    private long effectiveInventorySkewPpm(MarketMakerProperties.Strategy strategy) {
        return strategy.getMaxInventorySkewPpm() == null
                ? properties.getRisk().getMaxInventorySkewPpm()
                : strategy.getMaxInventorySkewPpm();
    }

    private String qualityStatus(List<MarketMakerAnomaly> anomalies) {
        if (anomalies.stream().anyMatch(item -> "CRITICAL".equals(item.severity()))) {
            return "CRITICAL";
        }
        if (anomalies.stream().anyMatch(item -> "WARN".equals(item.severity()))) {
            return "WARN";
        }
        if (anomalies.stream().anyMatch(item -> "INFO".equals(item.severity()))) {
            return "INFO";
        }
        return "OK";
    }

    private MarketMakerAnomaly anomaly(String severity,
                                       String type,
                                       String strategyId,
                                       ProductLine productLine,
                                       String symbol,
                                       long accountId,
                                       long metricValue,
                                       long threshold,
                                       String summary) {
        return new MarketMakerAnomaly(severity, type, strategyId, productLine, symbol, accountId, metricValue, threshold, summary);
    }

    private void recordRunEvent(MarketMakerProperties.Strategy strategy,
                                String symbol,
                                Long accountId,
                                long cycleSequence,
                                String eventType,
                                long submittedOrders,
                                long canceledOrders,
                                long rejectedOrders,
                                String skippedReason,
                                String errorMessage,
                                String traceId,
                                Instant createdAt) {
        try {
            runEventRepository.record(new MarketMakerRunEventWrite(
                    strategy.getStrategyId(),
                    strategy.getProductLine(),
                    symbol,
                    accountId,
                    nodeId,
                    cycleSequence,
                    eventType,
                    Math.max(0L, submittedOrders),
                    Math.max(0L, canceledOrders),
                    Math.max(0L, rejectedOrders),
                    skippedReason,
                    errorMessage,
                    traceId,
                    createdAt));
        } catch (RuntimeException ex) {
            log.warn("Failed to record market-maker run event strategyId={} symbol={} eventType={} error={}",
                    strategy.getStrategyId(), symbol, eventType, ex.getMessage());
        }
    }

    private void recordReferenceSample(MarketMakerProperties.Strategy strategy,
                                       String symbol,
                                       long cycleSequence,
                                       ReferenceOrderBookSnapshot snapshot,
                                       String traceId,
                                       Instant sampledAt) {
        if (snapshot == null || !snapshot.hasTwoSidedDepth()) {
            return;
        }
        try {
            referenceSampleRepository.record(new MarketMakerReferenceSampleWrite(
                    strategy.getStrategyId(),
                    strategy.getProductLine(),
                    symbol,
                    nodeId,
                    cycleSequence,
                    snapshot.source(),
                    snapshot.transport(),
                    snapshot.bids().size(),
                    snapshot.asks().size(),
                    snapshot.bestBidTicks(),
                    snapshot.bestAskTicks(),
                    snapshot.midPriceTicks(),
                    snapshot.spreadTicks(),
                    snapshot.receivedAt(),
                    traceId,
                    sampledAt));
        } catch (RuntimeException ex) {
            log.warn("Failed to record market-maker reference sample strategyId={} symbol={} error={}",
                    strategy.getStrategyId(), symbol, ex.getMessage());
        }
    }

    private void runStrategy(MarketMakerProperties.Strategy strategy, String requestedSymbol, String traceId) {
        StrategyRuntimeState state = state(strategy);
        if (!strategy.isEnabled() || state.paused()) {
            state.addSkipped(1L);
            recordRunEvent(strategy, null, null, state.cycleSequence(), "SKIPPED",
                    0, 0, 0, !strategy.isEnabled() ? "STRATEGY_DISABLED" : "STRATEGY_PAUSED",
                    null, traceId, Instant.now());
            return;
        }
        long cycleSequence = state.nextCycleSequence();
        for (String configuredSymbol : strategy.getSymbols()) {
            String symbol = normalizeSymbol(configuredSymbol);
            if (requestedSymbol != null && !symbol.equalsIgnoreCase(requestedSymbol)) {
                continue;
            }
            runStrategySymbol(strategy, state, cycleSequence, symbol, traceId);
        }
    }

    private void runStrategySymbol(MarketMakerProperties.Strategy strategy,
                                   StrategyRuntimeState state,
                                   long cycleSequence,
                                   String symbol,
                                   String traceId) {
        String executionKey = strategyKey(strategy) + ":" + symbol;
        AtomicBoolean cycleLock = cycleLocks.computeIfAbsent(executionKey, ignored -> new AtomicBoolean());
        if (!cycleLock.compareAndSet(false, true)) {
            state.addSkipped(1L);
            recordRunEvent(strategy, symbol, null, cycleSequence, "SKIPPED",
                    0, 0, 0, "CYCLE_IN_PROGRESS", null, traceId, Instant.now());
            return;
        }
        ProductLine previousProductLine = MarketMakerProductLineContext.current();
        MarketMakerProductLineContext.set(strategy.getProductLine());
        try {
            if (properties.getCoordination().isEnabled()
                    && !leaseCoordinator.tryAcquire(strategy.getProductLine(), strategy.getStrategyId(), symbol, nodeId,
                    properties.getCoordination().getLeaseDuration())) {
                state.addSkipped(1L);
                recordRunEvent(strategy, symbol, null, cycleSequence, "SKIPPED",
                        0, 0, 0, "LEASE_NOT_ACQUIRED", null, traceId, Instant.now());
                return;
            }
            Instant now = Instant.now();
            try {
                InstrumentResponse instrument = currentInstrument(strategy.getProductLine(), symbol);
                requireTradable(instrument, strategy.getProductLine());
                OrderBookSnapshotResponse orderBook = marketDataRpcApi.orderBook(symbol,
                        properties.getQuoting().getOrderBookDepth());
                MarkPriceResponse markPrice = currentMarkPrice(strategy.getProductLine(), symbol, instrument.version());
                ReferenceOrderBookSnapshot referenceOrderBook = referenceMarketProvider.snapshot(symbol,
                        strategy.getProductLine(), instrument);
                long volatilityTicks = observeVolatility(strategy, symbol, instrument, orderBook, markPrice);
                recordReferenceSample(strategy, symbol, cycleSequence, referenceOrderBook, traceId, now);
                for (long accountId : strategy.getAccountIds()) {
                    quoteAccount(strategy, state, cycleSequence, symbol, instrument, orderBook, markPrice,
                            referenceOrderBook, volatilityTicks, accountId, now, traceId);
                }
                maybeTrade(strategy, state, cycleSequence, symbol, instrument, markPrice, now, traceId);
                state.markSuccess(traceId, now);
                recordRunEvent(strategy, symbol, null, cycleSequence, "CYCLE_SUCCESS",
                        0, 0, 0, null, null, traceId, now);
            } catch (RuntimeException ex) {
                log.warn("Market-maker cycle failed strategyId={} symbol={} error={}",
                        strategy.getStrategyId(), symbol, ex.getMessage());
                state.markFailure(traceId, ex.getMessage(), now);
                recordRunEvent(strategy, symbol, null, cycleSequence, "CYCLE_FAILED",
                        0, 0, 0, null, ex.getMessage(), traceId, now);
            }
        } finally {
            MarketMakerProductLineContext.set(previousProductLine);
            cycleLock.set(false);
        }
    }

    private void quoteAccount(MarketMakerProperties.Strategy strategy,
                              StrategyRuntimeState state,
                              long cycleSequence,
                              String symbol,
                              InstrumentResponse instrument,
                              OrderBookSnapshotResponse orderBook,
                              MarkPriceResponse markPrice,
                              ReferenceOrderBookSnapshot referenceOrderBook,
                              long volatilityTicks,
                              long accountId,
                              Instant now,
                              String traceId) {
        PositionResponse position = currentPosition(strategy, accountId, symbol, instrument);
        QuotePlan plan = quotePlanner.plan(strategy, properties.getQuoting(), properties.getRisk(), instrument,
                orderBook, markPrice, position.signedQuantitySteps(), volatilityTicks, referenceOrderBook);
        List<OrderResponse> openOrders = openOrders(strategy.getProductLine(), accountId, symbol, now);
        ReconcileResult result = reconcile(strategy, accountId, symbol, plan, openOrders, cycleSequence, now);
        state.addCanceled(result.canceled());
        state.addSubmitted(result.submitted());
        state.addRejected(result.rejected());
        recordRunEvent(strategy, symbol, accountId, cycleSequence, "QUOTE_RECONCILED",
                result.submitted(), result.canceled(), result.rejected(), null, result.rejectionReason(), traceId, now);
    }

    private ReconcileResult reconcile(MarketMakerProperties.Strategy strategy,
                                      long accountId,
                                      String symbol,
                                      QuotePlan plan,
                                      List<OrderResponse> openOrders,
                                      long cycleSequence,
                                      Instant now) {
        String accountPrefix = accountPrefix(strategy, symbol, accountId);
        List<OrderResponse> owned = openOrders.stream()
                .filter(order -> ownsOrder(accountPrefix, order))
                .sorted(Comparator.comparing(OrderResponse::createdAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        List<OrderResponse> kept = new ArrayList<>();
        List<CancelOrderRequest> cancelRequests = new ArrayList<>();
        int operationBudget = properties.getQuoting().getMaxOrderOperationsPerCycle();
        for (OrderResponse order : owned) {
            if (shouldKeep(order, plan.quotes(), accountPrefix, now)) {
                kept.add(order);
            } else if (cancelRequests.size() < operationBudget) {
                cancelRequests.add(new CancelOrderRequest(accountId, order.orderId()));
            } else {
                kept.add(order);
            }
        }
        CancelResult cancelResult = cancelOrders(strategy.getProductLine(), accountId, symbol, cancelRequests);
        long canceled = cancelResult.completed();
        for (CancelOrderRequest failedRequest : cancelResult.failed()) {
            owned.stream()
                    .filter(order -> order.orderId() == failedRequest.orderId())
                    .findFirst()
                    .ifPresent(kept::add);
        }

        long submitted = 0L;
        long rejected = 0L;
        String rejectionReason = null;
        int maxOpenOrders = properties.getQuoting().getMaxOpenOrdersPerAccountSymbol();
        List<DesiredQuote> missingQuotes = new ArrayList<>();
        int remainingOperationBudget = Math.max(0, operationBudget - cancelRequests.size());
        for (DesiredQuote quote : plan.quotes()) {
            if (kept.size() >= maxOpenOrders) {
                break;
            }
            if (missingQuotes.size() >= remainingOperationBudget) {
                break;
            }
            if (hasLiveQuote(kept, quote, accountPrefix)) {
                continue;
            }
            missingQuotes.add(quote);
            kept.add(null);
        }
        if (!missingQuotes.isEmpty()) {
            List<PlaceOrderRequest> requests = missingQuotes.stream()
                    .map(quote -> quoteRequest(strategy, accountId, symbol, quote, cycleSequence))
                    .toList();
            for (int start = 0; start < requests.size(); start += MAX_BATCH_PLACE_ORDERS) {
                List<PlaceOrderRequest> batchRequests = requests.subList(start,
                        Math.min(start + MAX_BATCH_PLACE_ORDERS, requests.size()));
                try {
                    var batch = orderRpcApi.placeBatch(new BatchPlaceOrderRequest(batchRequests));
                if (batch == null) {
                    // 兼容旧的嵌入式调用方；生产 Feign 实现始终返回批量结果。
                        for (PlaceOrderRequest request : batchRequests) {
                            OrderResponse response = orderRpcApi.place(request);
                        if (response == null || response.status() == OrderStatus.REJECTED) {
                            rejected++;
                            rejectionReason = firstReason(rejectionReason, response == null
                                    ? "订单服务未返回报价结果" : response.rejectReason());
                            continue;
                        }
                        rememberOrder(strategy.getProductLine(), accountId, symbol, response);
                        submitted++;
                        int placeholder = kept.indexOf(null);
                        if (placeholder >= 0) {
                            kept.set(placeholder, response);
                        } else {
                            kept.add(response);
                        }
                    }
                        continue;
                }
                    for (int i = 0; i < batchRequests.size(); i++) {
                    int resultIndex = i;
                    var item = batch.results().stream()
                            .filter(result -> result.index() == resultIndex)
                            .findFirst()
                            .orElse(null);
                    OrderResponse response = item == null ? null : item.order();
                    if (item == null || !item.success() || response == null
                            || response.status() == OrderStatus.REJECTED) {
                        rejected++;
                        rejectionReason = firstReason(rejectionReason,
                                item == null ? "批量下单缺少结果" : firstReason(item.message(),
                                        response == null ? null : response.rejectReason()));
                        continue;
                    }
                    rememberOrder(strategy.getProductLine(), accountId, symbol, response);
                    submitted++;
                    // 占位元素只用于限制本周期的最大报价数，成功后替换为真实订单。
                    int placeholder = kept.indexOf(null);
                    if (placeholder >= 0) {
                        kept.set(placeholder, response);
                    } else {
                        kept.add(response);
                    }
                }
                } catch (UnsupportedOperationException ex) {
                // 兼容尚未实现批量 RPC 的嵌入式调用方；线上订单服务提供批量接口。
                    for (PlaceOrderRequest request : batchRequests) {
                        OrderResponse response = orderRpcApi.place(request);
                    if (response == null || response.status() == OrderStatus.REJECTED) {
                        rejected++;
                        rejectionReason = firstReason(rejectionReason, response == null
                                ? "订单服务未返回报价结果" : response.rejectReason());
                        continue;
                    }
                    rememberOrder(strategy.getProductLine(), accountId, symbol, response);
                    submitted++;
                    int placeholder = kept.indexOf(null);
                    if (placeholder >= 0) {
                        kept.set(placeholder, response);
                    } else {
                        kept.add(response);
                    }
                }
                } catch (RuntimeException ex) {
                // 批量请求失败时只跳过当前账户的报价，不能让一个账户阻断其他产品线。
                // 下一周期会重新读取 JVM 快照并重试；资金校验仍由下单与账户单写者严格执行。
                    rejected += batchRequests.size();
                rejectionReason = firstReason(rejectionReason, ex.getMessage());
                }
            }
        }
        return new ReconcileResult(submitted, canceled, rejected, rejectionReason);
    }

    /** 只保留第一条拒单原因，避免一次批量报价把运行事件写得不可读。 */
    private String firstReason(String current, String candidate) {
        if (current != null && !current.isBlank()) {
            return current;
        }
        return candidate == null || candidate.isBlank() ? "报价被拒绝" : candidate;
    }

    private boolean shouldKeep(OrderResponse order,
                               List<DesiredQuote> desiredQuotes,
                               String accountPrefix,
                               Instant now) {
        if (!isLive(order) || isStale(order, now)) {
            return false;
        }
        return desiredQuotes.stream().anyMatch(quote -> matchesQuote(order, quote, accountPrefix));
    }

    private boolean hasLiveQuote(List<OrderResponse> orders, DesiredQuote quote, String accountPrefix) {
        return orders.stream()
                .filter(this::isLive)
                .anyMatch(order -> matchesQuote(order, quote, accountPrefix));
    }

    private boolean matchesQuote(OrderResponse order, DesiredQuote quote, String accountPrefix) {
        String expectedPrefix = quotePrefix(accountPrefix, quote.side(), quote.level());
        return order.clientOrderId() != null
                && order.clientOrderId().startsWith(expectedPrefix)
                && order.side() == quote.side()
                && Math.abs(order.priceTicks() - quote.priceTicks())
                <= properties.getQuoting().getRefreshThresholdTicks()
                && order.remainingQuantitySteps() == quote.quantitySteps();
    }

    private boolean isLive(OrderResponse order) {
        return order != null && LIVE_STATUSES.contains(order.status());
    }

    private boolean isStale(OrderResponse order, Instant now) {
        Duration maxAge = properties.getQuoting().getStaleOrderMaxAge();
        return maxAge != null
                && order.updatedAt() != null
                && order.updatedAt().plus(maxAge).isBefore(now);
    }

    private OrderResponse place(MarketMakerProperties.Strategy strategy,
                                long accountId,
                                String symbol,
                                DesiredQuote quote,
                                long cycleSequence) {
        return orderRpcApi.place(quoteRequest(strategy, accountId, symbol, quote, cycleSequence));
    }

    private PlaceOrderRequest quoteRequest(MarketMakerProperties.Strategy strategy,
                                           long accountId,
                                           String symbol,
                                           DesiredQuote quote,
                                           long cycleSequence) {
        String accountPrefix = accountPrefix(strategy, symbol, accountId);
        String clientOrderId = quotePrefix(accountPrefix, quote.side(), quote.level())
                + cycleSequence + "-" + orderNonce;
        return new PlaceOrderRequest(accountId, clientOrderId, symbol, quote.side(),
                OrderType.LIMIT, TimeInForce.GTX, quote.priceTicks(), quote.quantitySteps(),
                strategy.getMarginMode(), PositionSide.NET, false, true);
    }

    private void maybeTrade(MarketMakerProperties.Strategy strategy,
                            StrategyRuntimeState state,
                            long cycleSequence,
                            String symbol,
                            InstrumentResponse instrument,
                            MarkPriceResponse markPrice,
                            Instant now,
                            String traceId) {
        MarketMakerProperties.Trade trade = properties.getTrade();
        if (!trade.isEnabled()) {
            return;
        }
        String tradeKey = strategy.getProductLine().name() + ":" + strategy.getStrategyId() + ":" + symbol;
        Instant lastTradeTime = lastTradeTimes.get(tradeKey);
        if (lastTradeTime != null && lastTradeTime.plusMillis(trade.getMinIntervalMs()).isAfter(now)) {
            return;
        }
        long accountId = activeTradeAccount(strategy, cycleSequence);
        if (accountId <= 0) {
            return;
        }

        OrderBookSnapshotResponse orderBook = marketDataRpcApi.orderBook(symbol,
                properties.getQuoting().getOrderBookDepth());
        PositionResponse position = currentPosition(strategy, accountId, symbol, instrument);
        OrderSide side = tradeSide(tradeKey, trade, instrument, orderBook, markPrice,
                position.signedQuantitySteps());
        if (side == null) {
            return;
        }
        TradeTarget target = tradeTarget(side, orderBook, trade.getSlippageTicks(), trade.getMaxSweepLevels());
        long priceTicks = target.priceTicks();
        long quantitySteps = tradeQuantity(instrument, trade, target.availableQuantitySteps());
        if (priceTicks <= 0 || quantitySteps <= 0) {
            return;
        }

        PlaceOrderRequest request = new PlaceOrderRequest(accountId,
                takerClientOrderId(strategy, symbol, accountId, cycleSequence),
                symbol, side, OrderType.LIMIT, TimeInForce.IOC, priceTicks, quantitySteps,
                strategy.getMarginMode(), PositionSide.NET, false, false);
        OrderResponse response = orderRpcApi.place(request);
        if (response.status() == OrderStatus.REJECTED) {
            state.addRejected(1L);
            recordRunEvent(strategy, symbol, accountId, cycleSequence, "TRADE_REJECTED",
                    0, 0, 1, null, response.rejectReason(), traceId, now);
        } else {
            state.addSubmitted(1L);
            lastTradeTimes.put(tradeKey, now);
            lastTradeSides.put(tradeKey, side);
            recordRunEvent(strategy, symbol, accountId, cycleSequence, "TRADE_SUBMITTED",
                    1, 0, 0, null, null, traceId, now);
        }
    }

    private long activeTradeAccount(MarketMakerProperties.Strategy strategy, long cycleSequence) {
        List<Long> accountIds = properties.getTrade().getAccountIds().isEmpty()
                ? strategy.getAccountIds()
                : properties.getTrade().getAccountIds();
        if (accountIds == null || accountIds.isEmpty()) {
            return 0L;
        }
        return accountIds.get((int) Math.floorMod(cycleSequence, accountIds.size()));
    }

    private OrderSide tradeSide(String tradeKey,
                                MarketMakerProperties.Trade trade,
                                InstrumentResponse instrument,
                                OrderBookSnapshotResponse orderBook,
                                MarkPriceResponse markPrice,
                                long signedPositionSteps) {
        boolean canBuy = bestAsk(orderBook) > 0;
        boolean canSell = bestBid(orderBook) > 0;
        if (!canBuy && !canSell) {
            return null;
        }
        long inventoryThreshold = trade.getInventoryThresholdSteps();
        if (inventoryThreshold > 0 && signedPositionSteps > inventoryThreshold && canSell) {
            return OrderSide.SELL;
        }
        if (inventoryThreshold > 0 && signedPositionSteps < -inventoryThreshold && canBuy) {
            return OrderSide.BUY;
        }

        long markTicks = markPriceTicks(instrument, markPrice);
        long midTicks = midPriceTicks(orderBook);
        if (markTicks > 0 && midTicks > 0) {
            if (markTicks > midTicks && canBuy) {
                return OrderSide.BUY;
            }
            if (markTicks < midTicks && canSell) {
                return OrderSide.SELL;
            }
        }

        OrderSide previous = lastTradeSides.get(tradeKey);
        if (previous == OrderSide.BUY && canSell) {
            return OrderSide.SELL;
        }
        if (previous == OrderSide.SELL && canBuy) {
            return OrderSide.BUY;
        }
        if (canBuy && canSell) {
            return ThreadLocalRandom.current().nextBoolean() ? OrderSide.BUY : OrderSide.SELL;
        }
        return canBuy ? OrderSide.BUY : OrderSide.SELL;
    }

    private TradeTarget tradeTarget(OrderSide side,
                                    OrderBookSnapshotResponse orderBook,
                                    long slippageTicks,
                                    int maxSweepLevels) {
        List<OrderBookLevel> levels = side == OrderSide.BUY
                ? orderBook == null ? List.of() : orderBook.asks()
                : orderBook == null ? List.of() : orderBook.bids();
        if (levels == null || levels.isEmpty()) {
            return new TradeTarget(0L, 0L);
        }
        int targetDistinctLevels = ThreadLocalRandom.current().nextInt(Math.max(1, maxSweepLevels)) + 1;
        long cumulativeQuantity = 0L;
        long targetPriceTicks = 0L;
        long previousPriceTicks = 0L;
        int distinctLevels = 0;
        for (OrderBookLevel level : levels) {
            if (level == null || level.priceTicks() <= 0 || level.quantitySteps() <= 0) {
                continue;
            }
            if (level.priceTicks() != previousPriceTicks) {
                distinctLevels++;
                previousPriceTicks = level.priceTicks();
            }
            if (distinctLevels > targetDistinctLevels) {
                break;
            }
            cumulativeQuantity = Math.addExact(cumulativeQuantity, level.quantitySteps());
            targetPriceTicks = level.priceTicks();
        }
        if (targetPriceTicks <= 0 || cumulativeQuantity <= 0) {
            return new TradeTarget(0L, 0L);
        }
        long slippage = Math.max(0L, slippageTicks);
        if (side == OrderSide.BUY) {
            return new TradeTarget(Math.addExact(targetPriceTicks, slippage), cumulativeQuantity);
        }
        return new TradeTarget(Math.max(1L, targetPriceTicks - slippage), cumulativeQuantity);
    }

    private long bestAvailableQuantity(OrderSide side, OrderBookSnapshotResponse orderBook) {
        OrderBookLevel level = side == OrderSide.BUY ? firstAsk(orderBook) : firstBid(orderBook);
        return level == null ? 0L : level.quantitySteps();
    }

    private long tradeQuantity(InstrumentResponse instrument,
                               MarketMakerProperties.Trade trade,
                               long availableQuantity) {
        long minQuantity = Math.max(trade.getMinQuantitySteps(),
                instrument == null ? 1L : Math.max(1L, instrument.minQuantitySteps()));
        long maxQuantity = Math.max(minQuantity, trade.getMaxQuantitySteps());
        long upperBound = Math.min(maxQuantity, availableQuantity);
        if (upperBound < minQuantity) {
            return 0L;
        }
        return upperBound;
    }

    private long markPriceTicks(InstrumentResponse instrument, MarkPriceResponse markPrice) {
        if (instrument == null || markPrice == null || instrument.priceTickUnits() <= 0
                || markPrice.markPriceUnits() <= 0) {
            return 0L;
        }
        return (markPrice.markPriceUnits() + instrument.priceTickUnits() / 2L) / instrument.priceTickUnits();
    }

    private long observeVolatility(MarketMakerProperties.Strategy strategy,
                                   String symbol,
                                   InstrumentResponse instrument,
                                   OrderBookSnapshotResponse orderBook,
                                   MarkPriceResponse markPrice) {
        long anchor = markPriceTicks(instrument, markPrice);
        if (anchor <= 0) {
            anchor = midPriceTicks(orderBook);
        }
        if (anchor <= 0) {
            return currentVolatility(strategy, symbol);
        }
        return priceStates.computeIfAbsent(strategyKey(strategy) + ":" + symbol, ignored -> new PriceState())
                .observe(anchor);
    }

    private long currentVolatility(MarketMakerProperties.Strategy strategy, String symbol) {
        PriceState state = priceStates.get(strategyKey(strategy) + ":" + symbol);
        return state == null ? 0L : state.volatilityTicks();
    }

    private long midPriceTicks(OrderBookSnapshotResponse orderBook) {
        long bestBid = bestBid(orderBook);
        long bestAsk = bestAsk(orderBook);
        if (bestBid > 0 && bestAsk > 0) {
            return (bestBid + bestAsk) / 2L;
        }
        if (bestBid > 0) {
            return bestBid;
        }
        return bestAsk;
    }

    private long bestBid(OrderBookSnapshotResponse orderBook) {
        OrderBookLevel level = firstBid(orderBook);
        return level == null ? 0L : level.priceTicks();
    }

    private long bestAsk(OrderBookSnapshotResponse orderBook) {
        OrderBookLevel level = firstAsk(orderBook);
        return level == null ? 0L : level.priceTicks();
    }

    private OrderBookLevel firstBid(OrderBookSnapshotResponse orderBook) {
        if (orderBook == null || orderBook.bids() == null || orderBook.bids().isEmpty()) {
            return null;
        }
        return orderBook.bids().get(0);
    }

    private OrderBookLevel firstAsk(OrderBookSnapshotResponse orderBook) {
        if (orderBook == null || orderBook.asks() == null || orderBook.asks().isEmpty()) {
            return null;
        }
        return orderBook.asks().get(0);
    }

    private CancelResult cancelOrders(ProductLine productLine,
                                      long accountId,
                                      String symbol,
                                      List<CancelOrderRequest> requests) {
        if (requests.isEmpty()) {
            return new CancelResult(0L, List.of());
        }
        try {
            OrderBatchResponse response = orderRpcApi.cancelBatch(new BatchCancelOrdersRequest(requests));
            if (response != null) {
                long canceled = response.results().stream()
                        .filter(item -> item != null && item.success())
                        .count();
                List<CancelOrderRequest> failed = new ArrayList<>();
                for (int i = 0; i < requests.size(); i++) {
                    int requestIndex = i;
                    boolean success = response.results().stream()
                            .anyMatch(item -> item != null && item.index() == requestIndex && item.success());
                    if (success) {
                        forgetOrder(productLine, accountId, symbol, requests.get(i).orderId());
                    } else {
                        failed.add(requests.get(i));
                    }
                }
                return new CancelResult(canceled, List.copyOf(failed));
            }
        } catch (UnsupportedOperationException ex) {
            return cancelIndividually(productLine, accountId, symbol, requests);
        } catch (FeignException.NotFound ex) {
            return cancelIndividually(productLine, accountId, symbol, requests);
        } catch (RuntimeException ex) {
            log.warn("做市批量撤单状态不确定 accountId={} symbol={} error={}", accountId, symbol, ex.getMessage());
            return new CancelResult(0L, List.copyOf(requests));
        }
        return cancelIndividually(productLine, accountId, symbol, requests);
    }

    private CancelResult cancelIndividually(ProductLine productLine,
                                            long accountId,
                                            String symbol,
                                            List<CancelOrderRequest> requests) {
        long canceled = 0L;
        List<CancelOrderRequest> failed = new ArrayList<>();
        for (CancelOrderRequest request : requests) {
            try {
                orderRpcApi.cancel(request);
                canceled++;
                forgetOrder(productLine, accountId, symbol, request.orderId());
            } catch (FeignException.NotFound ex) {
                canceled++;
                forgetOrder(productLine, accountId, symbol, request.orderId());
            } catch (RuntimeException ex) {
                failed.add(request);
                log.warn("做市撤单状态不确定 accountId={} orderId={} error={}",
                        accountId, request.orderId(), ex.getMessage());
            }
        }
        return new CancelResult(canceled, List.copyOf(failed));
    }

    private List<OrderResponse> openOrders(ProductLine productLine,
                                            long accountId,
                                            String symbol,
                                            Instant now) {
        String key = orderSnapshotKey(productLine, accountId, symbol);
        CachedOpenOrders cached = openOrderSnapshots.get(key);
        Duration interval = properties.getQuoting().getOrderReconciliationInterval();
        if (cached != null && interval != null && cached.refreshedAt().plus(interval).isAfter(now)) {
            return cached.orders();
        }
        OrderQueryResponse response = orderRpcApi.openOrders(accountId, symbol,
                properties.getQuoting().getMaxOpenOrdersPerAccountSymbol(), null);
        List<OrderResponse> orders = response == null || response.orders() == null
                ? List.of() : List.copyOf(response.orders());
        openOrderSnapshots.put(key, new CachedOpenOrders(orders, now));
        return orders;
    }

    private void rememberOrder(ProductLine productLine, long accountId, String symbol, OrderResponse order) {
        if (order == null) {
            return;
        }
        String key = orderSnapshotKey(productLine, accountId, symbol);
        openOrderSnapshots.computeIfPresent(key, (ignored, cached) -> {
            List<OrderResponse> orders = new ArrayList<>(cached.orders());
            orders.removeIf(existing -> existing != null && existing.orderId() == order.orderId());
            orders.add(order);
            return new CachedOpenOrders(List.copyOf(orders), cached.refreshedAt());
        });
    }

    private void forgetOrder(ProductLine productLine, long accountId, String symbol, long orderId) {
        String key = orderSnapshotKey(productLine, accountId, symbol);
        openOrderSnapshots.computeIfPresent(key, (ignored, cached) -> new CachedOpenOrders(
                cached.orders().stream().filter(order -> order == null || order.orderId() != orderId).toList(),
                cached.refreshedAt()));
    }

    private String orderSnapshotKey(ProductLine productLine, long accountId, String symbol) {
        return productLine.name() + ":" + accountId + ":" + symbol;
    }

    private MarkPriceResponse latestMarkPrice(String symbol, long instrumentVersion) {
        MarkPriceEvent event = markPriceCache.requireFresh(symbol);
        if (event.instrumentVersion() != instrumentVersion) {
            throw new IllegalStateException("mark price instrument version mismatch for " + symbol
                    + ": expected=" + instrumentVersion + ", actual=" + event.instrumentVersion());
        }
        return new MarkPriceResponse(event.symbol(), event.markPrice(), event.markPriceUnits(), event.indexPrice(),
                event.price1(), event.price2(), event.lastTradePrice(), event.bestBidPrice(), event.bestAskPrice(),
                event.fundingRate(), event.nextFundingTime(), event.timeUntilFundingSeconds(), event.basisAverage(),
                event.basisWindowSeconds(), event.clampLow(), event.clampHigh(), event.sequence(), event.status(),
                event.eventTime());
    }

    /** 现货没有持仓对象，做市库存由资产余额约束；这里不能调用永续持仓接口。 */
    private PositionResponse currentPosition(MarketMakerProperties.Strategy strategy,
                                             long accountId,
                                             String symbol,
                                             InstrumentResponse instrument) {
        if (strategy.getProductLine() == ProductLine.SPOT) {
            if (instrument == null || instrument.baseAsset() == null || instrument.baseAsset().isBlank()) {
                throw new IllegalStateException("现货做市缺少基础资产快照");
            }
            // 现货没有持仓对象，使用账户 JVM 快照中的基础资产权益作为库存约束。
            // AccountRpcApi 只访问账户服务本地快照，不会在报价周期内查询数据库。
            var balance = accountRpcApi.balance(accountId, instrument.baseAsset());
            long inventory = balance == null ? 0L : Math.max(0L, balance.equityUnits());
            return new PositionResponse(accountId, symbol, instrument.version(),
                    strategy.getMarginMode(), PositionSide.NET, inventory, 0L, 0L, Instant.now());
        }
        return accountRpcApi.position(accountId, symbol, strategy.getMarginMode().name(), PositionSide.NET.name());
    }

    /** 现货报价锚定盘口，不消费标记价 Topic。 */
    private MarkPriceResponse currentMarkPrice(ProductLine productLine, String symbol, long instrumentVersion) {
        return productLine == ProductLine.SPOT ? null : latestMarkPrice(symbol, instrumentVersion);
    }

    private void requireTradable(InstrumentResponse instrument, ProductLine productLine) {
        if (instrument == null || instrument.status() != InstrumentStatus.TRADING) {
            throw new IllegalStateException("instrument is not in TRADING status");
        }
        if (instrument.contractType() == null || instrument.contractType().productLine() != productLine) {
            throw new IllegalStateException("instrument product line mismatch");
        }
    }

    private boolean isTradableForProduct(InstrumentResponse instrument, ProductLine productLine) {
        return instrument != null
                && instrument.status() == InstrumentStatus.TRADING
                && instrument.contractType() != null
                && instrument.contractType().productLine() == productLine;
    }

    private boolean ownsOrder(String accountPrefix, OrderResponse order) {
        return order != null && order.clientOrderId() != null && order.clientOrderId().startsWith(accountPrefix);
    }

    private String accountPrefix(MarketMakerProperties.Strategy strategy, String symbol, long accountId) {
        return "mm-" + stableToken(strategy.getProductLine().name() + ":" + strategy.getStrategyId() + ":" + symbol)
                + "-" + accountId + "-";
    }

    private String quotePrefix(String accountPrefix, OrderSide side, int level) {
        return accountPrefix + (side == OrderSide.BUY ? "b" : "s") + level + "-";
    }

    private String takerClientOrderId(MarketMakerProperties.Strategy strategy,
                                      String symbol,
                                      long accountId,
                                      long cycleSequence) {
        return "mm-tk-" + stableToken(strategy.getProductLine().name() + ":" + strategy.getStrategyId() + ":" + symbol)
                + "-" + accountId + "-" + cycleSequence + "-"
                + Long.toUnsignedString(ThreadLocalRandom.current().nextLong(), 36);
    }

    private String stableToken(String value) {
        CRC32 crc32 = new CRC32();
        crc32.update(value.getBytes(StandardCharsets.UTF_8));
        return Long.toUnsignedString(crc32.getValue(), 36);
    }

    private List<MarketMakerProperties.Strategy> strategiesSnapshot() {
        return strategiesSnapshot(null);
    }

    private List<MarketMakerProperties.Strategy> strategiesSnapshot(ProductLine productLine) {
        Map<String, StrategyConfigOverride> overrides = strategyOverrides();
        return properties.getStrategies().stream()
                .filter(strategy -> productLine == null || strategy.getProductLine() == productLine)
                .map(strategy -> applyOverride(strategy, overrides.get(strategyKey(strategy))))
                .toList();
    }

    private Map<String, StrategyConfigOverride> strategyOverrides() {
        Instant now = Instant.now();
        if (strategyOverridesLoadedAt.plus(Duration.ofSeconds(1)).isAfter(now)) {
            return strategyOverrides;
        }
        synchronized (this) {
            if (strategyOverridesLoadedAt.plus(Duration.ofSeconds(1)).isAfter(now)) {
                return strategyOverrides;
            }
            try {
                Map<String, StrategyConfigOverride> next = new HashMap<>();
                for (StrategyConfigOverride override : overrideStore.findAll()) {
                    next.put(strategyKey(override.productLine(), override.strategyId()), override);
                }
                strategyOverrides = Map.copyOf(next);
            } catch (RuntimeException ex) {
                log.warn("Failed to load market-maker strategy overrides: {}", ex.getMessage());
            } finally {
                strategyOverridesLoadedAt = now;
            }
            return strategyOverrides;
        }
    }

    private void putCachedOverride(StrategyConfigOverride override) {
        Map<String, StrategyConfigOverride> next = new HashMap<>(strategyOverrides());
        next.put(strategyKey(override.productLine(), override.strategyId()), override);
        strategyOverrides = Map.copyOf(next);
        strategyOverridesLoadedAt = Instant.now();
    }

    private void removeCachedOverride(MarketMakerProperties.Strategy strategy) {
        Map<String, StrategyConfigOverride> next = new HashMap<>(strategyOverrides());
        next.remove(strategyKey(strategy));
        strategyOverrides = Map.copyOf(next);
        strategyOverridesLoadedAt = Instant.now();
    }

    private MarketMakerProperties.Strategy applyOverride(MarketMakerProperties.Strategy configured,
                                                         StrategyConfigOverride override) {
        MarketMakerProperties.Strategy effective = new MarketMakerProperties.Strategy();
        effective.setStrategyId(configured.getStrategyId());
        effective.setProductLine(configured.getProductLine());
        effective.setEnabled(override != null && override.enabled() != null ? override.enabled() : configured.isEnabled());
        effective.setAccountIds(new ArrayList<>(configured.getAccountIds()));
        effective.setSymbols(new ArrayList<>(configured.getSymbols()));
        // 复制只读配置中的启动锚定价，避免数据库覆盖对象重建策略时丢失现货初始化参数。
        effective.setInitialAnchorPriceTicks(configured.getInitialAnchorPriceTicks());
        effective.setBaseQuantitySteps(override != null && override.baseQuantitySteps() != null
                ? override.baseQuantitySteps()
                : configured.getBaseQuantitySteps());
        effective.setMarginMode(override != null && override.marginMode() != null
                ? override.marginMode()
                : configured.getMarginMode());
        effective.setSpreadTicks(override != null && override.spreadTicks() != null
                ? override.spreadTicks()
                : configured.getSpreadTicks());
        effective.setLevelSpacingTicks(override != null && override.levelSpacingTicks() != null
                ? override.levelSpacingTicks()
                : configured.getLevelSpacingTicks());
        effective.setMaxInventorySteps(override != null && override.maxInventorySteps() != null
                ? override.maxInventorySteps()
                : configured.getMaxInventorySteps());
        effective.setMaxInventorySkewPpm(override != null && override.maxInventorySkewPpm() != null
                ? override.maxInventorySkewPpm()
                : configured.getMaxInventorySkewPpm());
        effective.setOrderLevels(override != null && override.orderLevels() != null
                ? override.orderLevels()
                : configured.getOrderLevels());
        return effective;
    }

    private MarketMakerStrategyConfigResponse configResponse(MarketMakerProperties.Strategy configured,
                                                             StrategyConfigOverride override) {
        return new MarketMakerStrategyConfigResponse(
                strategyConfig(configured),
                strategyConfig(applyOverride(configured, override)),
                override);
    }

    private MarketMakerStrategyConfig strategyConfig(MarketMakerProperties.Strategy strategy) {
        return new MarketMakerStrategyConfig(strategy.getStrategyId(), strategy.getProductLine(), strategy.isEnabled(),
                List.copyOf(strategy.getAccountIds()), List.copyOf(strategy.getSymbols()),
                strategy.getBaseQuantitySteps(), strategy.getMarginMode(), strategy.getSpreadTicks(),
                strategy.getLevelSpacingTicks(), strategy.getMaxInventorySteps(), strategy.getMaxInventorySkewPpm(),
                strategy.getOrderLevels());
    }

    private MarketMakerProperties.Strategy findStrategy(String strategyId) {
        return findStrategy(strategyId, null);
    }

    private MarketMakerProperties.Strategy findStrategy(String strategyId, ProductLine productLine) {
        String normalized = normalizeRequired(strategyId, "strategyId");
        return strategiesSnapshot(productLine).stream()
                .filter(strategy -> strategy.getStrategyId().equalsIgnoreCase(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown market-maker strategy: " + strategyId));
    }

    private MarketMakerProperties.Strategy findConfiguredStrategy(String strategyId) {
        return findConfiguredStrategy(strategyId, null);
    }

    private MarketMakerProperties.Strategy findConfiguredStrategy(String strategyId, ProductLine productLine) {
        String normalized = normalizeRequired(strategyId, "strategyId");
        return properties.getStrategies().stream()
                .filter(strategy -> productLine == null || strategy.getProductLine() == productLine)
                .filter(strategy -> strategy.getStrategyId().equalsIgnoreCase(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown market-maker strategy: " + strategyId));
    }

    private StrategyRuntimeState state(MarketMakerProperties.Strategy strategy) {
        return states.computeIfAbsent(strategyKey(strategy), ignored -> new StrategyRuntimeState());
    }

    private MarketMakerStrategyResponse response(MarketMakerProperties.Strategy strategy) {
        StrategyRuntimeState state = state(strategy);
        MarketMakerStrategyStatus status = status(strategy, state);
        return new MarketMakerStrategyResponse(strategy.getStrategyId(), strategy.getProductLine(),
                List.copyOf(strategy.getSymbols()),
                List.copyOf(strategy.getAccountIds()), status, strategy.isEnabled(), state.paused(),
                state.cycleSequence(), state.submittedOrders(), state.canceledOrders(), state.rejectedOrders(),
                state.skippedCycles(), state.lastTraceId(), state.lastError(), state.lastCycleTime());
    }

    private MarketMakerStrategyStatus status(MarketMakerProperties.Strategy strategy, StrategyRuntimeState state) {
        if (!strategy.isEnabled()) {
            return MarketMakerStrategyStatus.DISABLED;
        }
        if (state.paused()) {
            return MarketMakerStrategyStatus.PAUSED;
        }
        return state.lastError() == null ? MarketMakerStrategyStatus.RUNNING : MarketMakerStrategyStatus.DEGRADED;
    }

    private String normalizeRequired(String value, String field) {
        String normalized = normalizeOptional(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private String normalizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeSymbol(String value) {
        String normalized = normalizeRequired(value, "symbol");
        if (!normalized.matches("[A-Z0-9-]{3,64}")) {
            throw new IllegalArgumentException("invalid symbol: " + value);
        }
        return normalized;
    }

    private String normalizeReason(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("reason is required");
        }
        String normalized = value.trim();
        if (normalized.length() > 500) {
            throw new IllegalArgumentException("reason must be at most 500 characters");
        }
        return normalized;
    }

    private MarginMode parseMarginMode(String value) {
        String normalized = normalizeOptional(value);
        if (normalized == null) {
            return null;
        }
        try {
            return MarginMode.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("invalid marginMode: " + value, ex);
        }
    }

    private Long positiveOrNull(Long value, String field) {
        if (value == null) {
            return null;
        }
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    private Long nonNegativeOrNull(Long value, String field) {
        if (value == null) {
            return null;
        }
        if (value < 0) {
            throw new IllegalArgumentException(field + " must be non-negative");
        }
        return value;
    }

    private Long boundedLongOrNull(Long value, long min, long max, String field) {
        if (value == null) {
            return null;
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException(field + " must be between " + min + " and " + max);
        }
        return value;
    }

    private Integer boundedIntOrNull(Integer value, int min, int max, String field) {
        if (value == null) {
            return null;
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException(field + " must be between " + min + " and " + max);
        }
        return value;
    }

    private String strategyKey(MarketMakerProperties.Strategy strategy) {
        return strategy == null ? strategyKey(null, null) : strategyKey(strategy.getProductLine(), strategy.getStrategyId());
    }

    private String strategyKey(ProductLine productLine, String strategyId) {
        ProductLine safeProductLine = productLine == null ? ProductLine.LINEAR_PERPETUAL : productLine;
        String safeStrategyId = strategyId == null ? "" : strategyId.trim().toLowerCase(Locale.ROOT);
        return safeProductLine.name() + ":" + safeStrategyId;
    }

    private String resolveNodeId(String configuredNodeId) {
        if (configuredNodeId != null && !configuredNodeId.isBlank()) {
            return configuredNodeId.trim();
        }
        return "market-maker-" + UUID.randomUUID();
    }

    public record MarketMakerAdminMetricsResponse(Instant generatedAt,
                                                  String nodeId,
                                                  MarketMakerMetricsTotals totals,
                                                  List<MarketMakerStrategyMetric> rows,
                                                  List<MarketMakerAnomaly> anomalies,
                                                  List<MarketMakerMetricWarning> warnings) {
    }

    public record MarketMakerRunLogQueryResponse(Instant generatedAt,
                                                 List<MarketMakerRunEventRecord> events,
                                                 String nextCursor,
                                                 boolean hasMore,
                                                 String sort,
                                                 int limit) {

        public MarketMakerRunLogQueryResponse(Instant generatedAt, List<MarketMakerRunEventRecord> events) {
            this(generatedAt, events, null, false, null, events == null ? 0 : events.size());
        }

        public int count() {
            return events == null ? 0 : events.size();
        }
    }

    public record MarketMakerStrategyConfigResponse(MarketMakerStrategyConfig configured,
                                                    MarketMakerStrategyConfig effective,
                                                    StrategyConfigOverride override) {
    }

    public record MarketMakerStrategyConfig(String strategyId,
                                            ProductLine productLine,
                                            boolean enabled,
                                            List<Long> accountIds,
                                            List<String> symbols,
                                            long baseQuantitySteps,
                                            MarginMode marginMode,
                                            long spreadTicks,
                                            long levelSpacingTicks,
                                            Long maxInventorySteps,
                                            Long maxInventorySkewPpm,
                                            Integer orderLevels) {
    }

    public record MarketMakerStrategyConfigUpdateRequest(Boolean enabled,
                                                         Long baseQuantitySteps,
                                                         String marginMode,
                                                         Long spreadTicks,
                                                         Long levelSpacingTicks,
                                                         Long maxInventorySteps,
                                                         Long maxInventorySkewPpm,
                                                         Integer orderLevels,
                                                         String reason) {
    }

    public record MarketMakerMetricsTotals(long strategyCount,
                                           long enabledStrategies,
                                           long runningStrategies,
                                           long degradedStrategies,
                                           long pausedStrategies,
                                           long disabledStrategies,
                                           long metricRows,
                                           long submittedOrders,
                                           long canceledOrders,
                                           long rejectedOrders,
                                           long skippedCycles,
                                           long anomalyCount,
                                           long criticalAnomalies,
                                           long warnAnomalies) {
    }

    public record MarketMakerStrategyMetric(String strategyId,
                                            ProductLine productLine,
                                            String symbol,
                                            long accountId,
                                            MarketMakerStrategyStatus strategyStatus,
                                            String qualityStatus,
                                            boolean configuredEnabled,
                                            boolean runtimePaused,
                                            long cycleSequence,
                                            long submittedOrders,
                                            long canceledOrders,
                                            long rejectedOrders,
                                            long skippedCycles,
                                            long signedInventorySteps,
                                            long absInventorySteps,
                                            long maxInventorySteps,
                                            long inventoryUsagePpm,
                                            long realizedPnlUnits,
                                            Instant positionUpdatedAt,
                                            long ownedOpenOrders,
                                            long ownedBidOrders,
                                            long ownedAskOrders,
                                            long desiredQuoteCount,
                                            long desiredBidQuotes,
                                            long desiredAskQuotes,
                                            long matchedDesiredQuotes,
                                            long missingDesiredQuotes,
                                            long staleOwnedOrders,
                                            long offTargetOwnedOrders,
                                            long bestBidTicks,
                                            long bestAskTicks,
                                            long spreadTicks,
                                            long spreadPpm,
                                            long markPriceTicks,
                                            long quoteCoveragePpm,
                                            String lastTraceId,
                                            String lastError,
                                            Instant lastCycleTime,
                                            String error) {
    }

    public record MarketMakerAnomaly(String severity,
                                     String type,
                                     String strategyId,
                                     ProductLine productLine,
                                     String symbol,
                                     long accountId,
                                     long metricValue,
                                     long threshold,
                                     String summary) {
    }

    public record MarketMakerMetricWarning(String strategyId,
                                           ProductLine productLine,
                                           String symbol,
                                           long accountId,
                                           String message) {
    }

    private record ReconcileResult(long submitted, long canceled, long rejected, String rejectionReason) {
    }

    private record CachedOpenOrders(List<OrderResponse> orders, Instant refreshedAt) {
    }

    private record CancelResult(long completed, List<CancelOrderRequest> failed) {
    }

    private record TradeTarget(long priceTicks, long availableQuantitySteps) {
    }

    private static final class PriceState {
        private long lastPriceTicks;
        private long volatilityTicks;

        private synchronized long observe(long priceTicks) {
            if (lastPriceTicks > 0) {
                long delta = priceTicks >= lastPriceTicks
                        ? priceTicks - lastPriceTicks : lastPriceTicks - priceTicks;
                volatilityTicks += (delta - volatilityTicks) / 4L;
            }
            lastPriceTicks = priceTicks;
            return volatilityTicks;
        }

        private synchronized long volatilityTicks() {
            return volatilityTicks;
        }
    }

    private static final class NoopMarketMakerRunEventRepository implements MarketMakerRunEventRepository {
        @Override
        public void record(MarketMakerRunEventWrite event) {
        }

        @Override
        public List<MarketMakerRunEventRecord> find(ProductLine productLine,
                                                    String strategyId,
                                                    String symbol,
                                                    Long accountId,
                                                    String eventType,
                                                    int limit) {
            return List.of();
        }

        @Override
        public CursorPage<MarketMakerRunEventRecord> findPage(ProductLine productLine,
                                                              String strategyId,
                                                              String symbol,
                                                              Long accountId,
                                                              String eventType,
                                                              int limit,
                                                              String cursor,
                                                              String sort) {
            return new CursorPage<>(List.of(), null, false, sort, Math.max(1, limit));
        }
    }

    private static final class NoopMarketMakerReferenceSampleRepository
            implements MarketMakerReferenceSampleRepository {
        @Override
        public void record(MarketMakerReferenceSampleWrite sample) {
        }
    }
}
