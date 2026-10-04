package com.surprising.marketmaker.provider.service;

import lombok.extern.slf4j.Slf4j;

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
import com.surprising.trading.api.model.AmendOrderRequest;
import com.surprising.trading.api.model.AmendOrderBatchResponse;
import com.surprising.trading.api.model.BatchAmendOrdersRequest;
import com.surprising.trading.api.model.CancelOrderRequest;
import com.surprising.trading.api.model.BatchPlaceOrderRequest;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderBookLevel;
import com.surprising.trading.api.model.OrderBookSnapshotResponse;
import com.surprising.trading.api.model.OrderBatchResponse;
import com.surprising.trading.api.model.OrderCommandReceipt;
import com.surprising.trading.api.model.OrderCommandResult;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ThreadLocalRandom;
import java.util.zip.CRC32;
import java.util.random.RandomGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class MarketMakerService {

    private static final int MAX_BATCH_PLACE_ORDERS = 20;
    private static final int MAX_BATCH_CANCEL_ORDERS = 50;
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
    private volatile Map<String, StrategyConfigOverride> strategyOverrides = Map.of();
    private volatile Instant strategyOverridesLoadedAt = Instant.EPOCH;
    private final String nodeId;
    private final String orderNonce;
    // A confirmed rejection can be retried after freeing budget in the same cycle; that is a new order.
    private final java.util.concurrent.atomic.AtomicLong quoteRequestSequence = new java.util.concurrent.atomic.AtomicLong();

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

    /** 一个策略的连续工作线程入口；false 表示暂停、未取得租约或本轮失败。 */
    public boolean runScheduledStrategy(String strategyId, ProductLine productLine) {
        if (!properties.getEngine().isEnabled()) return false;
        String traceId = TraceContext.currentOrCreate();
        try {
            return runStrategy(findStrategy(strategyId, productLine), null, traceId, false);
        } finally {
            TraceContext.clear();
        }
    }

    /** 测试吃单独立于被动报价；同一策略币对仍只允许一个吃单任务。 */
    public boolean runScheduledTrades(String strategyId, ProductLine productLine) {
        if (!properties.getEngine().isEnabled() || !properties.getTrade().isEnabled()) return false;
        MarketMakerProperties.Strategy strategy = findStrategy(strategyId, productLine);
        if (!strategy.isEnabled() || state(strategy).paused()) return false;
        String traceId = TraceContext.currentOrCreate();
        try {
            boolean submitted = false;
            for (String instrumentId : strategy.getInstrumentIds()) submitted |= tradeSymbol(strategy, normalizeSymbol(instrumentId), traceId);
            return submitted;
        } finally {
            TraceContext.clear();
        }
    }

    private boolean tradeSymbol(MarketMakerProperties.Strategy strategy, String instrumentId, String traceId) {
        if (!properties.getTrade().isEnabled()) return false;
        AtomicBoolean lock = cycleLocks.computeIfAbsent(strategyKey(strategy) + ":" + instrumentId + ":trade",
                ignored -> new AtomicBoolean());
        if (!lock.compareAndSet(false, true)) return false;
        ProductLine previous = MarketMakerProductLineContext.current();
        MarketMakerProductLineContext.set(strategy.getProductLine());
        try {
            if (properties.getCoordination().isEnabled()
                    && !leaseCoordinator.tryAcquire(strategy.getProductLine(), strategy.getStrategyId() + ":trade", instrumentId,
                    nodeId, properties.getCoordination().getLeaseDuration())) return false;
            InstrumentResponse instrument = currentInstrument(strategy.getProductLine(), instrumentId);
            requireTradable(instrument, strategy.getProductLine());
            MarkPriceResponse mark = currentMarkPrice(strategy.getProductLine(), instrumentId, instrument.changeId());
            StrategyRuntimeState state = state(strategy);
            return maybeTrade(strategy, state, state.cycleSequence(), instrumentId, instrument, mark, Instant.now(), traceId);
        } catch (RuntimeException ex) {
            log.warn("Market-maker simulated trade failed strategyId={} instrumentId={} error={}",
                    strategy.getStrategyId(), instrumentId, ex.getMessage());
            return false;
        } finally {
            MarketMakerProductLineContext.set(previous);
            lock.set(false);
        }
    }

    public MarketMakerStrategyQueryResponse strategies() {
        return strategies(null);
    }

    private InstrumentResponse currentInstrument(ProductLine productLine, String instrumentId) {
        if (productLine == null || instrumentSnapshotCache == null
                || !instrumentSnapshotCache.initialized(productLine)) {
            throw new IllegalStateException("做市合约 JVM 快照尚未就绪: " + productLine);
        }
        return instrumentSnapshotCache.current(productLine, com.surprising.product.api.InstrumentIds.parse(instrumentId))
                .orElseThrow(() -> new IllegalStateException("合约快照中不存在品种: " + productLine + "/" + instrumentId));
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
                boundedIntOrNull(safeRequest.orderLevels(), 1, 50, "orderLevels"),
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
        String requestedSymbol = normalizeOptional(request == null ? null : request.instrumentId());
        ProductLine requestedProductLine = request == null ? null : request.productLine();
        try {
            for (MarketMakerProperties.Strategy strategy : strategiesSnapshot(requestedProductLine)) {
                if (requestedStrategyId != null && !strategy.getStrategyId().equalsIgnoreCase(requestedStrategyId)) {
                    continue;
                }
                runStrategy(strategy, requestedSymbol, traceId, true);
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
            for (String configuredSymbol : strategy.getInstrumentIds()) {
                if (rows.size() >= boundedLimit) {
                    break;
                }
                String instrumentId = normalizeSymbol(configuredSymbol);
                for (long accountId : strategy.getAccountIds()) {
                    if (rows.size() >= boundedLimit) {
                        break;
                    }
                    rows.add(strategyMetric(strategy, instrumentId, accountId, now, anomalies, warnings));
                }
            }
        }
        return new MarketMakerAdminMetricsResponse(now, nodeId, totals(productLine, rows, anomalies), rows, anomalies, warnings);
    }

    public MarketMakerRunLogQueryResponse runLogs(String strategyId,
                                                  String instrumentId,
                                                  Long accountId,
                                                  String eventType,
                                                  int limit) {
        return runLogs(null, strategyId, instrumentId, accountId, eventType, limit);
    }

    public MarketMakerRunLogQueryResponse runLogs(ProductLine productLine,
                                                  String strategyId,
                                                  String instrumentId,
                                                  Long accountId,
                                                  String eventType,
                                                  int limit) {
        return new MarketMakerRunLogQueryResponse(
                Instant.now(),
                runEventRepository.find(
                        productLine,
                        normalizeOptional(strategyId),
                        instrumentId == null || instrumentId.isBlank() ? null : normalizeSymbol(instrumentId),
                        accountId,
                        normalizeOptional(eventType),
                        limit));
    }

    public MarketMakerRunLogQueryResponse runLogs(String strategyId,
                                                  String instrumentId,
                                                  Long accountId,
                                                  String eventType,
                                                  int limit,
                                                  String cursor,
                                                  String sort) {
        return runLogs(null, strategyId, instrumentId, accountId, eventType, limit, cursor, sort);
    }

    public MarketMakerRunLogQueryResponse runLogs(ProductLine productLine,
                                                  String strategyId,
                                                  String instrumentId,
                                                  Long accountId,
                                                  String eventType,
                                                  int limit,
                                                  String cursor,
                                                  String sort) {
        CursorPage<MarketMakerRunEventRecord> page = runEventRepository.findPage(
                productLine,
                normalizeOptional(strategyId),
                instrumentId == null || instrumentId.isBlank() ? null : normalizeSymbol(instrumentId),
                accountId,
                normalizeOptional(eventType),
                limit,
                cursor,
                sort);
        return new MarketMakerRunLogQueryResponse(Instant.now(), page.items(), page.nextCursor(),
                page.hasMore(), page.sort(), page.limit());
    }

    private MarketMakerStrategyMetric strategyMetric(MarketMakerProperties.Strategy strategy,
                                                     String instrumentId,
                                                     long accountId,
                                                     Instant now,
                                                     List<MarketMakerAnomaly> anomalies,
                                                     List<MarketMakerMetricWarning> warnings) {
        StrategyRuntimeState state = state(strategy);
        MarketMakerStrategyStatus strategyStatus = status(strategy, state);
        String strategyId = strategy.getStrategyId();
        ProductLine productLine = strategy.getProductLine();
        String accountPrefix = accountPrefix(strategy, instrumentId, accountId);
        List<MarketMakerAnomaly> rowAnomalies = new ArrayList<>();
        ProductLine previousProductLine = MarketMakerProductLineContext.current();
        MarketMakerProductLineContext.set(productLine);
        if (!strategy.isEnabled()) {
            rowAnomalies.add(anomaly("INFO", "STRATEGY_DISABLED", strategyId, productLine, instrumentId, accountId,
                    0, 1, "strategy is disabled by configuration"));
        }
        if (state.paused()) {
            rowAnomalies.add(anomaly("INFO", "STRATEGY_PAUSED", strategyId, productLine, instrumentId, accountId,
                    1, 0, "strategy is paused at runtime"));
        }
        if (state.lastError() != null) {
            rowAnomalies.add(anomaly("CRITICAL", "LAST_CYCLE_FAILED", strategyId, productLine, instrumentId, accountId,
                    1, 0, state.lastError()));
        }
        try {
            List<OrderResponse> openOrders = openOrders(productLine, accountId, instrumentId, now);
            List<OrderResponse> ownedLive = openOrders.stream()
                    .filter(order -> ownsOrder(accountPrefix, order))
                    .filter(this::isLive)
                    .toList();
            long staleOwned = ownedLive.stream().filter(order -> isStale(order, now)).count();
            InstrumentResponse instrument = currentInstrument(productLine, instrumentId);
            PositionResponse position = currentPosition(strategy, accountId, instrumentId, instrument);
            OrderBookSnapshotResponse orderBook = marketDataRpcApi.orderBook(instrumentId,
                    properties.getQuoting().getOrderBookDepth());
            MarkPriceResponse markPrice = currentMarkPrice(productLine, instrumentId, instrument.changeId());
            ReferenceOrderBookSnapshot referenceOrderBook = referenceMarketProvider.snapshot(instrumentId, productLine, instrument);
            QuotePlan plan = !isTradableForProduct(instrument, productLine)
                    || properties.getReferenceMarket().isEnabled() && referenceOrderBook == null
                    ? new QuotePlan(0L, position.signedQuantitySteps(), List.of(), 0)
                    : quotePlanner.plan(strategy, properties.getQuoting(), properties.getRisk(), instrument,
                    orderBook, markPrice, position.signedQuantitySteps(), currentVolatility(strategy, instrumentId),
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

            if (properties.getReferenceMarket().isEnabled() && referenceOrderBook == null) {
                rowAnomalies.add(anomaly("CRITICAL", "REFERENCE_BOOK_UNAVAILABLE", strategyId,
                        productLine, instrumentId, accountId, 0, 1,
                        "fresh external reference order book is required for quoting"));
            }

            if (inventoryUsagePpm >= 1_000_000L) {
                rowAnomalies.add(anomaly("CRITICAL", "INVENTORY_LIMIT_REACHED", strategyId, productLine, instrumentId, accountId,
                        absInventory, maxInventory, "signed inventory reached configured limit"));
            } else if (inventoryUsagePpm >= Math.max(0L, effectiveInventorySkewPpm(strategy))) {
                rowAnomalies.add(anomaly("WARN", "INVENTORY_SKEW_HIGH", strategyId, productLine, instrumentId, accountId,
                        inventoryUsagePpm, effectiveInventorySkewPpm(strategy), "inventory usage exceeds skew threshold"));
            }
            if (desiredQuotes > 0 && missingDesired > 0) {
                rowAnomalies.add(anomaly("WARN", "MISSING_DESIRED_QUOTES", strategyId, productLine, instrumentId, accountId,
                        missingDesired, desiredQuotes, "some desired quote levels are not live"));
            }
            if (plan.suppressedDuplicateQuotes() > 0) {
                rowAnomalies.add(anomaly("WARN", "REDUCED_DISTINCT_DEPTH", strategyId, productLine, instrumentId, accountId,
                        desiredQuotes, desiredQuotes + plan.suppressedDuplicateQuotes(),
                        "price bounds cannot represent every configured level as a distinct executable price"));
            }
            if (ownedLive.isEmpty() && strategy.isEnabled() && !state.paused()) {
                rowAnomalies.add(anomaly("CRITICAL", "NO_LIVE_QUOTES", strategyId, productLine, instrumentId, accountId,
                        0, desiredQuotes, "no owned live quotes are present"));
            }
            if (staleOwned > 0) {
                rowAnomalies.add(anomaly("WARN", "STALE_QUOTES", strategyId, productLine, instrumentId, accountId,
                        staleOwned, 0, "owned live quotes exceed stale age"));
            }
            if (offTargetOwned > 0) {
                rowAnomalies.add(anomaly("WARN", "OFF_TARGET_QUOTES", strategyId, productLine, instrumentId, accountId,
                        offTargetOwned, 0, "owned live quotes do not match target levels"));
            }
            if (!isTradableForProduct(instrument, productLine)) {
                rowAnomalies.add(anomaly("CRITICAL", "INSTRUMENT_NOT_TRADING", strategyId, productLine, instrumentId, accountId,
                        1, 0, "instrument is unavailable or not TRADING"));
            }
            MarketMakerLiquidityMetric liquidity = linearLiquidity(instrument, orderBook, now);
            if (liquidity != null && liquidity.targetNotionalUnits() > 0) {
                if (!liquidity.fresh()) rowAnomalies.add(anomaly("CRITICAL", "LIQUIDITY_BOOK_STALE", strategyId,
                        productLine, instrumentId, accountId, 0, 1, "fresh two-sided depth is required for liquidity verification"));
                if (liquidity.bidNotionalWithinBandUnits() < liquidity.targetNotionalUnits()
                        || liquidity.askNotionalWithinBandUnits() < liquidity.targetNotionalUnits()) {
                    rowAnomalies.add(anomaly("CRITICAL", "INSUFFICIENT_EXECUTABLE_DEPTH", strategyId,
                            productLine, instrumentId, accountId,
                            Math.min(liquidity.bidNotionalWithinBandUnits(), liquidity.askNotionalWithinBandUnits()),
                            liquidity.targetNotionalUnits(), "displayed depth inside the slippage band is below the configured target"));
                }
            }
            anomalies.addAll(rowAnomalies);
            return new MarketMakerStrategyMetric(
                    strategyId, productLine, instrumentId, accountId, strategyStatus, qualityStatus(rowAnomalies),
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
                    state.lastError(), state.lastCycleTime(), null, liquidity);
        } catch (RuntimeException ex) {
            String message = ex.getMessage();
            anomalies.add(anomaly("CRITICAL", "METRIC_COLLECTION_FAILED", strategyId, productLine, instrumentId, accountId,
                    1, 0, message));
            warnings.add(new MarketMakerMetricWarning(strategyId, productLine, instrumentId, accountId, message));
            return new MarketMakerStrategyMetric(
                    strategyId, productLine, instrumentId, accountId, strategyStatus, "CRITICAL", strategy.isEnabled(), state.paused(),
                    state.cycleSequence(), state.submittedOrders(), state.canceledOrders(), state.rejectedOrders(),
                    state.skippedCycles(), 0, 0, effectiveMaxInventorySteps(strategy), 0, 0, null,
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    state.lastTraceId(), state.lastError(), state.lastCycleTime(), message, null);
        } finally {
            MarketMakerProductLineContext.set(previousProductLine);
        }
    }

    private MarketMakerLiquidityMetric linearLiquidity(InstrumentResponse instrument,
                                                       OrderBookSnapshotResponse book, Instant now) {
        if (instrument.contractType() != com.surprising.instrument.api.model.ContractType.LINEAR_PERPETUAL
                && instrument.contractType() != com.surprising.instrument.api.model.ContractType.LINEAR_DELIVERY) return null;
        long ppm = properties.getQuoting().getLiquiditySlippagePpm();
        boolean fresh = book.eventTime() != null && !book.eventTime().isAfter(now)
                && !book.eventTime().plus(properties.getReferenceMarket().getMaxAge()).isBefore(now)
                && bestBid(book) > 0 && bestAsk(book) > bestBid(book);
        return new MarketMakerLiquidityMetric(ppm, properties.getQuoting().getLinearLiquidityTargetNotionalUnits(),
                bandNotional(book.bids(), OrderSide.SELL, ppm, instrument.notionalMultiplierUnits()),
                bandNotional(book.asks(), OrderSide.BUY, ppm, instrument.notionalMultiplierUnits()),
                book.sequence(), book.eventTime(), fresh);
    }

    private long bandNotional(List<OrderBookLevel> levels, OrderSide takingSide, long ppm, long multiplier) {
        if (levels == null || levels.isEmpty()) return 0;
        // Native depth can be returned in ascending price order on both sides.
        levels = levels.stream().sorted(takingSide == OrderSide.SELL
                ? Comparator.comparingLong(OrderBookLevel::priceTicks).reversed()
                : Comparator.comparingLong(OrderBookLevel::priceTicks)).toList();
        long best = levels.getFirst().priceTicks();
        if (best <= 0 || multiplier <= 0) throw new IllegalStateException("invalid linear liquidity units");
        var threshold = java.math.BigInteger.valueOf(best).multiply(java.math.BigInteger.valueOf(ppm));
        var notional = java.math.BigInteger.ZERO;
        long previous = best;
        for (OrderBookLevel level : levels) {
            long price = level.priceTicks();
            if (price <= 0 || level.quantitySteps() < 0
                    || takingSide == OrderSide.BUY && price < previous
                    || takingSide == OrderSide.SELL && price > previous)
                throw new IllegalStateException("invalid liquidity book ordering or amount");
            previous = price;
            var distance = java.math.BigInteger.valueOf(takingSide == OrderSide.BUY ? price - best : best - price)
                    .multiply(java.math.BigInteger.valueOf(1_000_000L));
            if (distance.compareTo(threshold) > 0) break;
            notional = notional.add(java.math.BigInteger.valueOf(price)
                    .multiply(java.math.BigInteger.valueOf(level.quantitySteps()))
                    .multiply(java.math.BigInteger.valueOf(multiplier)));
        }
        return notional.longValueExact();
    }

    public record MarketMakerLiquidityMetric(long slippagePpm, long targetNotionalUnits,
            long bidNotionalWithinBandUnits, long askNotionalWithinBandUnits,
            long bookSequence, Instant bookEventTime, boolean fresh) { }

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
                                       String instrumentId,
                                       long accountId,
                                       long metricValue,
                                       long threshold,
                                       String summary) {
        return new MarketMakerAnomaly(severity, type, strategyId, productLine, instrumentId, accountId, metricValue, threshold, summary);
    }

    private void recordRunEvent(MarketMakerProperties.Strategy strategy,
                                String instrumentId,
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
                    instrumentId,
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
            log.warn("Failed to record market-maker run event strategyId={} instrumentId={} eventType={} error={}",
                    strategy.getStrategyId(), instrumentId, eventType, ex.getMessage());
        }
    }

    private void recordReferenceSample(MarketMakerProperties.Strategy strategy,
                                       String instrumentId,
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
                    instrumentId,
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
            log.warn("Failed to record market-maker reference sample strategyId={} instrumentId={} error={}",
                    strategy.getStrategyId(), instrumentId, ex.getMessage());
        }
    }

    private boolean runStrategy(MarketMakerProperties.Strategy strategy, String requestedSymbol, String traceId, boolean tradeAfterQuote) {
        StrategyRuntimeState state = state(strategy);
        if (!strategy.isEnabled() || state.paused()) {
            state.addSkipped(1L);
            recordRunEvent(strategy, null, null, state.cycleSequence(), "SKIPPED",
                    0, 0, 0, !strategy.isEnabled() ? "STRATEGY_DISABLED" : "STRATEGY_PAUSED",
                    null, traceId, Instant.now());
            return false;
        }
        boolean completed = false;
        long cycleSequence = state.nextCycleSequence();
        for (String configuredSymbol : strategy.getInstrumentIds()) {
            String instrumentId = normalizeSymbol(configuredSymbol);
            if (requestedSymbol != null && !instrumentId.equalsIgnoreCase(requestedSymbol)) {
                continue;
            }
            completed |= runStrategySymbol(strategy, state, cycleSequence, instrumentId, traceId, tradeAfterQuote);
        }
        return completed;
    }

    private boolean runStrategySymbol(MarketMakerProperties.Strategy strategy,
                                   StrategyRuntimeState state,
                                   long cycleSequence,
                                   String instrumentId,
                                   String traceId,
                                   boolean tradeAfterQuote) {
        String executionKey = strategyKey(strategy) + ":" + instrumentId;
        AtomicBoolean cycleLock = cycleLocks.computeIfAbsent(executionKey, ignored -> new AtomicBoolean());
        if (!cycleLock.compareAndSet(false, true)) {
            state.addSkipped(1L);
            recordRunEvent(strategy, instrumentId, null, cycleSequence, "SKIPPED",
                    0, 0, 0, "CYCLE_IN_PROGRESS", null, traceId, Instant.now());
            return false;
        }
        ProductLine previousProductLine = MarketMakerProductLineContext.current();
        MarketMakerProductLineContext.set(strategy.getProductLine());
        try {
            if (properties.getCoordination().isEnabled()
                    && !leaseCoordinator.tryAcquire(strategy.getProductLine(), strategy.getStrategyId(), instrumentId, nodeId,
                    properties.getCoordination().getLeaseDuration())) {
                state.addSkipped(1L);
                recordRunEvent(strategy, instrumentId, null, cycleSequence, "SKIPPED",
                        0, 0, 0, "LEASE_NOT_ACQUIRED", null, traceId, Instant.now());
                return false;
            }
            Instant now = Instant.now();
            try {
                InstrumentResponse instrument = currentInstrument(strategy.getProductLine(), instrumentId);
                requireTradable(instrument, strategy.getProductLine());
                OrderBookSnapshotResponse orderBook = marketDataRpcApi.orderBook(instrumentId,
                        properties.getQuoting().getOrderBookDepth());
                MarkPriceResponse markPrice = currentMarkPrice(strategy.getProductLine(), instrumentId, instrument.changeId());
                ReferenceOrderBookSnapshot referenceOrderBook = referenceMarketProvider.snapshot(instrumentId,
                        strategy.getProductLine(), instrument);
                long volatilityTicks = observeVolatility(strategy, instrumentId, instrument, orderBook, markPrice);
                recordReferenceSample(strategy, instrumentId, cycleSequence, referenceOrderBook, traceId, now);
                long rejectedQuotes = 0;
                String quoteRejection = null;
                for (long accountId : strategy.getAccountIds()) {
                    ReconcileResult result = quoteAccount(strategy, state, cycleSequence, instrumentId, instrument,
                            orderBook, markPrice, referenceOrderBook, volatilityTicks, accountId, now, traceId);
                    rejectedQuotes += result.rejected();
                    if (result.rejected() > 0 && quoteRejection == null)
                        quoteRejection = result.rejectionReason();
                }
                // A completed RPC is not a successful quote cycle when Core rejected replenishment.
                // Retain successful orders and let the next normal cycle retry under the same risk checks.
                if (rejectedQuotes > 0) {
                    String reason = "Quote replenishment rejected: " + rejectedQuotes + " orders; " + quoteRejection;
                    state.markFailure(traceId, reason, now);
                    recordRunEvent(strategy, instrumentId, null, cycleSequence, "CYCLE_FAILED",
                            0, 0, rejectedQuotes, null, reason, traceId, now);
                    return false;
                }
                if (tradeAfterQuote) tradeSymbol(strategy, instrumentId, traceId);
                state.markSuccess(traceId, now);
                recordRunEvent(strategy, instrumentId, null, cycleSequence, "CYCLE_SUCCESS",
                        0, 0, 0, null, null, traceId, now);
                return true;
            } catch (RuntimeException ex) {
                log.warn("Market-maker cycle failed strategyId={} instrumentId={} error={}",
                        strategy.getStrategyId(), instrumentId, ex.getMessage());
                state.markFailure(traceId, ex.getMessage(), now);
                recordRunEvent(strategy, instrumentId, null, cycleSequence, "CYCLE_FAILED",
                        0, 0, 0, null, ex.getMessage(), traceId, now);
                return false;
            }
        } finally {
            MarketMakerProductLineContext.set(previousProductLine);
            cycleLock.set(false);
        }
    }

    private ReconcileResult quoteAccount(MarketMakerProperties.Strategy strategy,
                              StrategyRuntimeState state,
                              long cycleSequence,
                              String instrumentId,
                              InstrumentResponse instrument,
                              OrderBookSnapshotResponse orderBook,
                              MarkPriceResponse markPrice,
                              ReferenceOrderBookSnapshot referenceOrderBook,
                              long volatilityTicks,
                              long accountId,
                              Instant now,
                              String traceId) {
        PositionResponse position = currentPosition(strategy, accountId, instrumentId, instrument);
        List<OrderResponse> openOrders = openOrders(strategy.getProductLine(), accountId, instrumentId, now);
        OrderBookSnapshotResponse otherLiquidity = excludeOwnQuotes(orderBook, openOrders);
        QuotePlan plan = properties.getReferenceMarket().isEnabled() && referenceOrderBook == null
                ? new QuotePlan(0L, position.signedQuantitySteps(), List.of(), 0)
                : quotePlanner.plan(strategy, properties.getQuoting(), properties.getRisk(), instrument,
                        otherLiquidity, markPrice, position.signedQuantitySteps(), volatilityTicks, referenceOrderBook);
        ReconcileResult result = reconcile(strategy, accountId, instrumentId, plan, openOrders, cycleSequence, now);
        state.addCanceled(result.canceled());
        state.addSubmitted(result.submitted());
        state.addRejected(result.rejected());
        recordRunEvent(strategy, instrumentId, accountId, cycleSequence, "QUOTE_RECONCILED",
                result.submitted(), result.canceled(), result.rejected(), null, result.rejectionReason(), traceId, now);
        return result;
    }

    /** 报价只避让其他参与者的盘口；自己的旧报价交由分批撤补处理，不能反向锁住参考价。 */
    private OrderBookSnapshotResponse excludeOwnQuotes(OrderBookSnapshotResponse book, List<OrderResponse> orders) {
        return new OrderBookSnapshotResponse(book.instrumentId(), book.sequence(), book.depth(),
                excludeOwnSide(book.bids(), orders, OrderSide.BUY),
                excludeOwnSide(book.asks(), orders, OrderSide.SELL), book.eventTime());
    }

    private List<OrderBookLevel> excludeOwnSide(List<OrderBookLevel> levels, List<OrderResponse> orders,
                                               OrderSide side) {
        return levels.stream().filter(level -> {
            long ownQuantity = orders.stream().filter(this::isLive)
                    .filter(order -> order.side() == side && order.priceTicks() == level.priceTicks())
                    .mapToLong(OrderResponse::remainingQuantitySteps).sum();
            return level.quantitySteps() > ownQuantity;
        }).toList();
    }

    private ReconcileResult reconcile(MarketMakerProperties.Strategy strategy,
                                      long accountId,
                                      String instrumentId,
                                      QuotePlan plan,
                                      List<OrderResponse> openOrders,
                                      long cycleSequence,
                                      Instant now) {
        String accountPrefix = accountPrefix(strategy, instrumentId, accountId);
        List<OrderResponse> owned = openOrders.stream()
                .filter(order -> ownsOrder(accountPrefix, order))
                // CANCEL_REQUESTED 已不再是可交易订单，不能继续占用目标报价档位。
                // 会导致所有报价都被跳过，做市策略进入“运行但无盘口”的假健康状态。
                .filter(this::isLive)
                .sorted(Comparator.comparing(OrderResponse::createdAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        // Keep the remaining ladder live while each small replacement batch is confirmed.
        List<OrderResponse> kept = new ArrayList<>(owned);
        long highestBid = plan.quotes().stream().filter(q -> q.side() == OrderSide.BUY)
                .mapToLong(DesiredQuote::priceTicks).max().orElse(0L);
        long lowestAsk = plan.quotes().stream().filter(q -> q.side() == OrderSide.SELL)
                .mapToLong(DesiredQuote::priceTicks).min().orElse(Long.MAX_VALUE);
        long previousBid = owned.stream().filter(order -> order.side() == OrderSide.BUY)
                .mapToLong(OrderResponse::priceTicks).max().orElse(0);
        long previousAsk = owned.stream().filter(order -> order.side() == OrderSide.SELL)
                .mapToLong(OrderResponse::priceTicks).min().orElse(Long.MAX_VALUE);
        List<CancelOrderRequest> cancelRequests = owned.stream()
                .filter(order -> !shouldKeep(order, plan.quotes(), accountPrefix, now))
                // Move quotes that would cross the new opposite side first, preserving post-only semantics.
                .sorted(Comparator.<OrderResponse>comparingInt(order ->
                        order.side() == OrderSide.SELL && order.priceTicks() <= highestBid
                                || order.side() == OrderSide.BUY && order.priceTicks() >= lowestAsk ? 0 : 1)
                        // Improving the best price needs the deep quote first; moving it away
                        // needs it last, so small replacement quotes never lead an empty band.
                        .thenComparingLong(order -> (order.side() == OrderSide.BUY
                                ? highestBid > previousBid : lowestAsk < previousAsk)
                                ? -order.remainingQuantitySteps() : order.remainingQuantitySteps()))
                .map(order -> new CancelOrderRequest(accountId, order.orderId()))
                .toList();
        int replacementBatchSize = Math.max(1, Math.min(MAX_BATCH_PLACE_ORDERS, owned.size() / 10));
        long canceled = 0L;
        long submitted = 0L;
        long rejected = 0L;
        String rejectionReason = null;
        // Refill slots consumed by trades before withdrawing any still-live liquidity.
        ReconcileResult refill = placeMissingQuotes(strategy, accountId, instrumentId, plan,
                kept, accountPrefix, cycleSequence);
        submitted += refill.submitted();
        rejected += refill.rejected();
        rejectionReason = refill.rejectionReason();
        if (cancelRequests.isEmpty()) return new ReconcileResult(submitted, canceled, rejected, rejectionReason);
        // If old oversized quotes occupy the budget, replace one small batch to release it.
        // The existing rejection check below stops us from withdrawing the rest of the ladder.
        for (int start = 0; start < Math.max(1, cancelRequests.size()); start += replacementBatchSize) {
            List<CancelOrderRequest> batch = cancelRequests.subList(start,
                    Math.min(start + replacementBatchSize, cancelRequests.size()));
            List<CancelOrderRequest> cancellations = new ArrayList<>();
            Map<Long, DesiredQuote> amendments = new java.util.LinkedHashMap<>();
            for (CancelOrderRequest request : batch) {
                OrderResponse old = kept.stream().filter(order -> order.orderId() == request.orderId())
                        .findFirst().orElse(null);
                DesiredQuote best = liquidityQuoteReplacement(strategy, plan, old, accountPrefix, highestBid, lowestAsk);
                if (best == null) cancellations.add(request);
                else amendments.put(request.orderId(), best);
            }
            CancelResult result = cancelOrders(strategy.getProductLine(), accountId, instrumentId, cancellations);
            canceled += result.completed();
            for (CancelOrderRequest request : cancellations) {
                if (!result.failed().contains(request)) {
                    kept.removeIf(order -> order.orderId() == request.orderId());
                }
            }
            if (!amendments.isEmpty() && !result.failed().isEmpty()) {
                rejected += result.failed().size();
                rejectionReason = "cancellation is uncertain; preserve the deep quotes";
                break;
            }
            List<OrderResponse> updated = amendQuotes(strategy, accountId, instrumentId, amendments, cycleSequence);
            kept.removeIf(order -> amendments.containsKey(order.orderId()));
            updated.stream().filter(this::isLive).forEach(kept::add);
            canceled += amendments.size();
            submitted += amendments.size();
            ReconcileResult replacement = placeMissingQuotes(strategy, accountId, instrumentId, plan,
                    kept, accountPrefix, cycleSequence);
            submitted += replacement.submitted();
            rejected += replacement.rejected();
            if (replacement.rejectionReason() != null) rejectionReason = replacement.rejectionReason();
            // Do not thin the rest of the book when replacement or cancellation is uncertain.
            if (replacement.rejected() > 0 || !result.failed().isEmpty()) break;
        }
        return new ReconcileResult(submitted, canceled, rejected, rejectionReason);
    }

    private DesiredQuote liquidityQuoteReplacement(MarketMakerProperties.Strategy strategy, QuotePlan plan,
            OrderResponse old, String prefix, long bid, long ask) {
        if (old == null || properties.getQuoting().getLinearLiquidityTargetNotionalUnits() == 0
                || (strategy.getProductLine() != ProductLine.LINEAR_PERPETUAL
                && strategy.getProductLine() != ProductLine.LINEAR_DELIVERY)) return null;
        return plan.quotes().stream().filter(quote -> quote.side() == old.side()
                && java.math.BigInteger.valueOf(Math.abs(quote.priceTicks() - (quote.side() == OrderSide.BUY ? bid : ask)))
                        .multiply(java.math.BigInteger.valueOf(1_000_000L)).compareTo(
                                java.math.BigInteger.valueOf(quote.side() == OrderSide.BUY ? bid : ask)
                                        .multiply(java.math.BigInteger.valueOf(properties.getQuoting().getLiquiditySlippagePpm()))) <= 0
                && old.clientOrderId().startsWith(quotePrefix(prefix, quote.side(), quote.level())))
                .findFirst().orElse(null);
    }

    /** Batch transport with per-item confirmation; a batch is not an all-or-nothing transaction. */
    private List<OrderResponse> amendQuotes(MarketMakerProperties.Strategy strategy, long accountId,
            String instrumentId, Map<Long, DesiredQuote> amendments, long cycleSequence) {
        if (amendments.isEmpty()) return List.of();
        List<AmendOrderRequest> requests = amendments.entrySet().stream().map(entry -> {
            var quote = quoteRequest(strategy, accountId, instrumentId, entry.getValue(), cycleSequence);
            return new AmendOrderRequest(accountId, entry.getKey(), quote.clientOrderId(),
                    quote.priceTicks(), quote.quantitySteps(), TimeInForce.GTX, true);
        }).toList();
        List<OrderResponse> updated = new ArrayList<>();
        try {
            for (int start = 0; start < requests.size(); start += 20) {
                var batch = requests.subList(start, Math.min(start + 20, requests.size()));
                var receipt = orderRpcApi.amendBatch(new BatchAmendOrdersRequest(batch));
                var result = receiptResult(receipt, AmendOrderBatchResponse.class);
                if (result == null || result.failed() != 0 || result.completed() != batch.size()
                        || result.results().size() != batch.size())
                    throw new IllegalStateException("quote amendments not confirmed: " + receiptMessage(receipt));
                boolean[] confirmed = new boolean[batch.size()];
                for (var item : result.results()) {
                    if (!item.success() || item.index() < 0 || item.index() >= batch.size()
                            || confirmed[item.index()] || item.amend() == null
                            || item.amend().replacementOrder() == null)
                        throw new IllegalStateException("invalid quote amendment result");
                    var replacement = item.amend().replacementOrder();
                    var request = batch.get(item.index());
                    if (replacement.status() == OrderStatus.REJECTED
                            || !request.newClientOrderId().equals(replacement.clientOrderId()))
                        throw new IllegalStateException("quote amendment identity or status mismatch");
                    confirmed[item.index()] = true;
                    forgetOrder(strategy.getProductLine(), accountId, instrumentId, request.orderId());
                    rememberOrder(strategy.getProductLine(), accountId, instrumentId, replacement);
                    updated.add(replacement);
                }
            }
            return updated;
        } catch (RuntimeException ex) {
            openOrderSnapshots.remove(orderSnapshotKey(strategy.getProductLine(), accountId, instrumentId));
            throw new IllegalStateException("quote amendment outcome requires an actual order query: " + ex.getMessage(), ex);
        }
    }

    private ReconcileResult placeMissingQuotes(MarketMakerProperties.Strategy strategy,
                                               long accountId,
                                               String instrumentId,
                                               QuotePlan plan,
                                               List<OrderResponse> kept,
                                               String accountPrefix,
                                               long cycleSequence) {
        long submitted = 0L;
        long rejected = 0L;
        String rejectionReason = null;
        int maxOpenOrders = properties.getQuoting().getMaxOpenOrdersPerAccountSymbol();
        List<DesiredQuote> missingQuotes = new ArrayList<>();
        // Existing orders still consume Core's pending-position limit until cancellation completes.
        // Never briefly exceed this side's planned ladder while refilling traded slots.
        long bidCapacity = Math.subtractExact(
                plan.quotes().stream().filter(quote -> quote.side() == OrderSide.BUY)
                        .mapToLong(DesiredQuote::quantitySteps).reduce(0L, Math::addExact),
                kept.stream().filter(order -> order != null && order.side() == OrderSide.BUY)
                        .mapToLong(OrderResponse::remainingQuantitySteps).reduce(0L, Math::addExact));
        long askCapacity = Math.subtractExact(
                plan.quotes().stream().filter(quote -> quote.side() == OrderSide.SELL)
                        .mapToLong(DesiredQuote::quantitySteps).reduce(0L, Math::addExact),
                kept.stream().filter(order -> order != null && order.side() == OrderSide.SELL)
                        .mapToLong(OrderResponse::remainingQuantitySteps).reduce(0L, Math::addExact));
        for (DesiredQuote quote : plan.quotes()) {
            if (kept.size() >= maxOpenOrders) {
                break;
            }
            // Waiting opposite quotes must be canceled before submitting a crossing post-only replacement.
            boolean blockedByOwnQuote = kept.stream().filter(this::isLive).anyMatch(order ->
                    order.side() != quote.side() && (quote.side() == OrderSide.BUY
                            ? quote.priceTicks() >= order.priceTicks() : quote.priceTicks() <= order.priceTicks()));
            if (blockedByOwnQuote || hasOwnedQuoteSlot(kept, quote, accountPrefix)) {
                continue;
            }
            if (quote.side() == OrderSide.BUY && quote.quantitySteps() > bidCapacity
                    || quote.side() == OrderSide.SELL && quote.quantitySteps() > askCapacity) {
                continue;
            }
            missingQuotes.add(quote);
            kept.add(null);
            if (quote.side() == OrderSide.BUY) bidCapacity -= quote.quantitySteps();
            else askCapacity -= quote.quantitySteps();
        }
        if (!missingQuotes.isEmpty()) {
            List<PlaceOrderRequest> requests = missingQuotes.stream()
                    .map(quote -> quoteRequest(strategy, accountId, instrumentId, quote, cycleSequence))
                    .toList();
            for (int start = 0; start < requests.size(); start += MAX_BATCH_PLACE_ORDERS) {
                List<PlaceOrderRequest> batchRequests = requests.subList(start,
                        Math.min(start + MAX_BATCH_PLACE_ORDERS, requests.size()));
                try {
                    OrderCommandReceipt receipt = orderRpcApi.placeBatch(new BatchPlaceOrderRequest(batchRequests));
                    OrderBatchResponse batch = receiptResult(receipt, OrderBatchResponse.class);
                    if (batch == null) {
                        throw new IllegalStateException(receiptMessage(receipt));
                    }
                    for (int i = 0; i < batchRequests.size(); i++) {
                        int resultIndex = i;
                        var item = batch.results().stream()
                                .filter(result -> result.index() == resultIndex)
                                .findFirst()
                                .orElse(null);
                        OrderResponse response = item == null ? null : item.order();
                        if (item == null || item.success() && response == null) {
                            throw new IllegalStateException("quote batch outcome is unknown; reconcile live orders before retry");
                        }
                        if (!item.success()
                                || response.status() == OrderStatus.REJECTED) {
                            rejected++;
                            rejectionReason = firstReason(rejectionReason,
                                    firstReason(item.message(),
                                            response == null ? null : response.rejectReason()));
                            continue;
                        }
                        rememberOrder(strategy.getProductLine(), accountId, instrumentId, response);
                        submitted++;
                        // 占位元素只用于限制本周期的最大报价数，成功后替换为真实订单。
                        int placeholder = kept.indexOf(null);
                        if (placeholder >= 0) {
                            kept.set(placeholder, response);
                        } else {
                            kept.add(response);
                        }
                    }
                } catch (RuntimeException ex) {
                    // Core may have accepted the batch before the response was lost. Stop this
                    // cycle before canceling more liquidity, then query actual orders next time.
                    openOrderSnapshots.remove(orderSnapshotKey(strategy.getProductLine(), accountId, instrumentId));
                    throw new IllegalStateException("quote replenishment outcome is unknown", ex);
                }
            }
        }
        kept.removeIf(java.util.Objects::isNull);
        return new ReconcileResult(submitted, 0L, rejected, rejectionReason);
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
        return desiredQuotes.stream().anyMatch(quote -> isFreshQuote(order, quote, accountPrefix, now));
    }

    private boolean isFreshQuote(OrderResponse order,
                                 DesiredQuote quote,
                                 String accountPrefix,
                                 Instant now) {
        return isLive(order) && !isStale(order, now) && matchesQuote(order, quote, accountPrefix);
    }

    private boolean hasLiveQuote(List<OrderResponse> orders, DesiredQuote quote, String accountPrefix) {
        return orders.stream()
                .filter(this::isLive)
                .anyMatch(order -> matchesQuote(order, quote, accountPrefix));
    }

    private boolean hasOwnedQuoteSlot(List<OrderResponse> orders, DesiredQuote quote, String accountPrefix) {
        String prefix = quotePrefix(accountPrefix, quote.side(), quote.level());
        return orders.stream().anyMatch(order -> order != null && order.clientOrderId() != null
                && order.clientOrderId().startsWith(prefix));
    }

    private boolean matchesQuote(OrderResponse order, DesiredQuote quote, String accountPrefix) {
        String expectedPrefix = quotePrefix(accountPrefix, quote.side(), quote.level());
        return order.clientOrderId() != null
                && order.clientOrderId().startsWith(expectedPrefix)
                && order.side() == quote.side()
                && Math.abs(order.priceTicks() - quote.priceTicks())
                <= Math.max(properties.getQuoting().getRefreshThresholdTicks(),
                        java.math.BigInteger.valueOf(quote.priceTicks())
                                .multiply(java.math.BigInteger.valueOf(properties.getQuoting().getRefreshTolerancePpm()))
                                .divide(java.math.BigInteger.valueOf(1_000_000L)).longValueExact())
                && quantityWithinRefreshTolerance(order.remainingQuantitySteps(), quote.quantitySteps());
    }

    private boolean quantityWithinRefreshTolerance(long resting, long desired) {
        long ppm = properties.getQuoting().getQuantityRefreshTolerancePpm();
        if (ppm == 0L) return resting == desired;
        if (resting < 0L || desired <= 0L) return false;
        long tolerance = (desired / 1_000_000L) * ppm
                + (desired % 1_000_000L) * ppm / 1_000_000L;
        long difference = resting >= desired ? resting - desired : desired - resting;
        return difference <= tolerance;
    }

    private boolean isLive(OrderResponse order) {
        return order != null && LIVE_STATUSES.contains(order.status());
    }

    private boolean isStale(OrderResponse order, Instant now) {
        Duration maxAge = properties.getQuoting().getStaleOrderMaxAge();
        return maxAge != null
                && order.createdAt() != null
                && order.createdAt().plus(maxAge).isBefore(now);
    }

    private PlaceOrderRequest quoteRequest(MarketMakerProperties.Strategy strategy,
                                           long accountId,
                                           String instrumentId,
                                           DesiredQuote quote,
                                           long cycleSequence) {
        String accountPrefix = accountPrefix(strategy, instrumentId, accountId);
        String clientOrderId = quotePrefix(accountPrefix, quote.side(), quote.level())
                + cycleSequence + "-" + Long.toUnsignedString(quoteRequestSequence.incrementAndGet(), 36) + "-" + orderNonce;
        TimeInForce timeInForce = TimeInForce.GTX;
        return new PlaceOrderRequest(accountId, clientOrderId, instrumentId, quote.side(),
                OrderType.LIMIT, timeInForce, quote.priceTicks(), quote.quantitySteps(),
                strategy.getMarginMode(), PositionSide.NET, false, true);
    }

    private boolean maybeTrade(MarketMakerProperties.Strategy strategy,
                            StrategyRuntimeState state,
                            long cycleSequence,
                            String instrumentId,
                            InstrumentResponse instrument,
                            MarkPriceResponse markPrice,
                            Instant now,
                            String traceId) {
        MarketMakerProperties.Trade trade = properties.getTrade();
        if (!trade.isEnabled()) {
            return false;
        }
        long accountId = activeTradeAccount(strategy, cycleSequence);
        if (accountId <= 0) {
            return false;
        }

        OrderBookSnapshotResponse orderBook = marketDataRpcApi.orderBook(instrumentId,
                properties.getQuoting().getOrderBookDepth());
        PositionResponse position = currentPosition(strategy, accountId, instrumentId, instrument);
        List<PlaceOrderRequest> requests = simulatedOrders(strategy, instrumentId, accountId, cycleSequence,
                instrument, orderBook, markPrice, position.signedQuantitySteps(), ThreadLocalRandom.current());
        if (requests.isEmpty()) return false;
        if (requests.size() > 1) return tradeBatch(strategy, instrumentId, accountId, cycleSequence,
                requests, state, traceId, now);
        PlaceOrderRequest request = requests.getFirst();
        OrderCommandReceipt receipt = orderRpcApi.place(request);
        OrderResponse response = receiptResult(receipt, OrderResponse.class);
        if (response == null || response.status() == OrderStatus.REJECTED) {
            state.addRejected(1L);
            recordRunEvent(strategy, instrumentId, accountId, cycleSequence, "TRADE_REJECTED",
                    0, 0, 1, null, response == null ? receiptMessage(receipt) : response.rejectReason(), traceId, now);
            return false;
        }
        state.addSubmitted(1L);
        if (response.executedQuantitySteps() <= 0) {
            recordRunEvent(strategy, instrumentId, accountId, cycleSequence, "TRADE_NO_FILL",
                    1, 0, 0, "NO_EXECUTION", null, traceId, now);
            return false;
        }
        recordRunEvent(strategy, instrumentId, accountId, cycleSequence, "TRADE_EXECUTED",
                1, 0, 0, null, null, traceId, now);
        return true;
    }

    /** Build local simulated orders; each side reserves its own worst-case fill budget for this batch. */
    List<PlaceOrderRequest> simulatedOrders(MarketMakerProperties.Strategy strategy, String instrumentId,
                                           long accountId, long cycleSequence, InstrumentResponse instrument,
                                           OrderBookSnapshotResponse book, MarkPriceResponse mark,
                                           long position, RandomGenerator random) {
        if (!instrument.marketOrderEnabled()) return List.of();
        MarketMakerProperties.Trade trade = properties.getTrade();
        long buyAvailable = tradeTarget(OrderSide.BUY, book, trade.getSlippageTicks(),
                trade.getMaxSweepLevels(), random).availableQuantitySteps();
        long sellAvailable = tradeTarget(OrderSide.SELL, book, trade.getSlippageTicks(),
                trade.getMaxSweepLevels(), random).availableQuantitySteps();
        long threshold = trade.getInventoryThresholdSteps();
        if (threshold > 0) {
            // Do not assume opposite-side orders fill: any subset of fills must respect the cap.
            buyAvailable = Math.min(buyAvailable, position < -threshold ? Math.negateExact(position)
                    : Math.max(0, Math.subtractExact(threshold, position)));
            sellAvailable = Math.min(sellAvailable, position > threshold ? position
                    : Math.max(0, Math.addExact(threshold, position)));
        }
        long minimum = Math.max(trade.getMinQuantitySteps(), instrument.minQuantitySteps());
        long maximum = Math.min(trade.getMaxQuantitySteps(), instrument.maxQuantitySteps());
        if (minimum <= 0 || maximum < minimum) return List.of();
        List<PlaceOrderRequest> requests = new ArrayList<>(trade.getOrdersPerBatch());
        for (int i = 0; i < trade.getOrdersPerBatch(); i++) {
            boolean canBuy = buyAvailable >= minimum;
            boolean canSell = sellAvailable >= minimum;
            if (!canBuy && !canSell) break;
            double buyProbability = 0.5;
            if (threshold > 0) buyProbability -= 0.35 * Math.clamp((double) position / threshold, -1, 1);
            long markTicks = markPriceTicks(instrument, mark);
            long midTicks = midPriceTicks(book);
            if (markTicks > 0 && midTicks > 0) buyProbability += 0.1 * Long.compare(markTicks, midTicks);
            OrderSide side = !canSell || (canBuy && random.nextDouble() < buyProbability)
                    ? OrderSide.BUY : OrderSide.SELL;
            long available = side == OrderSide.BUY ? buyAvailable : sellAvailable;
            long quantity = tradeQuantity(minimum, Math.min(maximum, available), random);
            requests.add(new PlaceOrderRequest(accountId,
                    takerClientOrderId(strategy, instrumentId, accountId, cycleSequence), instrumentId, side,
                    OrderType.MARKET, TimeInForce.IOC, 0L, quantity, strategy.getMarginMode(),
                    PositionSide.NET, false, false));
            if (side == OrderSide.BUY) buyAvailable -= quantity;
            else sellAvailable -= quantity;
        }
        return requests;
    }

    /** Submit ordinary simulated-user orders together; the core validates and settles every order. */
    private boolean tradeBatch(MarketMakerProperties.Strategy strategy, String instrumentId, long accountId,
                               long cycleSequence, List<PlaceOrderRequest> requests,
                               StrategyRuntimeState state, String traceId, Instant now) {
        int count = requests.size();
        OrderCommandReceipt receipt = orderRpcApi.placeBatch(new BatchPlaceOrderRequest(requests));
        OrderBatchResponse response = receiptResult(receipt, OrderBatchResponse.class);
        if (response == null) throw new IllegalStateException(receiptMessage(receipt));
        int accepted = 0;
        int executed = 0;
        int missingOrderDetails = 0;
        String rejectionReason = null;
        for (var result : response.results()) {
            if (result.success() && (result.order() == null || result.order().status() != OrderStatus.REJECTED)) {
                accepted++;
                // Completed batch commands may omit terminal orders. Missing details cannot prove no fill.
                if (result.order() == null) missingOrderDetails++;
                else if (result.order().executedQuantitySteps() > 0) executed++;
            } else if (rejectionReason == null) {
                rejectionReason = result.order() != null && result.order().rejectReason() != null
                        ? result.order().rejectReason() : result.message();
            }
        }
        state.addSubmitted(accepted);
        state.addRejected(count - accepted);
        recordRunEvent(strategy, instrumentId, accountId, cycleSequence,
                executed > 0 ? "TRADE_EXECUTED" : missingOrderDetails > 0 ? "TRADE_SUBMITTED"
                        : accepted > 0 ? "TRADE_NO_FILL" : "TRADE_REJECTED", accepted, 0, count - accepted,
                executed > 0 || missingOrderDetails > 0 ? null : "NO_EXECUTION", rejectionReason, traceId, now);
        return executed > 0 || missingOrderDetails > 0;
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

    private TradeTarget tradeTarget(OrderSide side,
                                    OrderBookSnapshotResponse orderBook,
                                    long slippageTicks,
                                    int maxSweepLevels, RandomGenerator random) {
        List<OrderBookLevel> levels = side == OrderSide.BUY
                ? orderBook == null ? List.of() : orderBook.asks()
                : orderBook == null ? List.of() : orderBook.bids();
        if (levels == null || levels.isEmpty()) {
            return new TradeTarget(0L, 0L);
        }
        int targetDistinctLevels = random.nextInt(Math.max(1, maxSweepLevels)) + 1;
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

    private long tradeQuantity(long minimum, long maximum, RandomGenerator random) {
        // More small orders than large ones, always integer multiples of the instrument step.
        long range = maximum - minimum + 1;
        return minimum + Math.min(random.nextLong(range), random.nextLong(range));
    }

    private long markPriceTicks(InstrumentResponse instrument, MarkPriceResponse markPrice) {
        if (instrument == null || markPrice == null || instrument.priceTickUnits() <= 0
                || markPrice.markPriceUnits() <= 0) {
            return 0L;
        }
        return (markPrice.markPriceUnits() + instrument.priceTickUnits() / 2L) / instrument.priceTickUnits();
    }

    private long observeVolatility(MarketMakerProperties.Strategy strategy,
                                   String instrumentId,
                                   InstrumentResponse instrument,
                                   OrderBookSnapshotResponse orderBook,
                                   MarkPriceResponse markPrice) {
        long anchor = markPriceTicks(instrument, markPrice);
        if (anchor <= 0) {
            anchor = midPriceTicks(orderBook);
        }
        if (anchor <= 0) {
            return currentVolatility(strategy, instrumentId);
        }
        return priceStates.computeIfAbsent(strategyKey(strategy) + ":" + instrumentId, ignored -> new PriceState())
                .observe(anchor);
    }

    private long currentVolatility(MarketMakerProperties.Strategy strategy, String instrumentId) {
        PriceState state = priceStates.get(strategyKey(strategy) + ":" + instrumentId);
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
                                      String instrumentId,
                                      List<CancelOrderRequest> requests) {
        if (requests.isEmpty()) {
            return new CancelResult(0L, List.of());
        }
        long canceled = 0L;
        List<CancelOrderRequest> failed = new ArrayList<>();
        for (int start = 0; start < requests.size(); start += MAX_BATCH_CANCEL_ORDERS) {
            List<CancelOrderRequest> batchRequests = requests.subList(start,
                    Math.min(start + MAX_BATCH_CANCEL_ORDERS, requests.size()));
            try {
                OrderCommandReceipt receipt = orderRpcApi.cancelBatch(new BatchCancelOrdersRequest(batchRequests));
                OrderBatchResponse response = receiptResult(receipt, OrderBatchResponse.class);
                if (response == null) {
                    log.warn("做市批量撤单未返回终态结果 accountId={} instrumentId={} receipt={}", accountId, instrumentId,
                            receiptMessage(receipt));
                    failed.addAll(batchRequests);
                    continue;
                }
                for (int i = 0; i < batchRequests.size(); i++) {
                    int requestIndex = i;
                    boolean success = response.results().stream()
                            .anyMatch(item -> item != null && item.index() == requestIndex && item.success());
                    CancelOrderRequest request = batchRequests.get(i);
                    if (success) {
                        canceled++;
                        forgetOrder(productLine, accountId, instrumentId, request.orderId());
                    } else {
                        failed.add(request);
                    }
                }
            } catch (RuntimeException ex) {
                log.warn("做市批量撤单状态不确定 accountId={} instrumentId={} error={}", accountId, instrumentId, ex.getMessage());
                failed.addAll(batchRequests);
            }
        }
        return new CancelResult(canceled, List.copyOf(failed));
    }

    private <T> T receiptResult(OrderCommandReceipt receipt, Class<T> type) {
        OrderCommandResult result = receipt == null ? null : receipt.result();
        return result != null && type.isInstance(result) ? type.cast(result) : null;
    }

    private String receiptMessage(OrderCommandReceipt receipt) {
        if (receipt == null) {
            return "订单服务未返回命令回执";
        }
        return receipt.code() + ": " + receipt.message();
    }

    private List<OrderResponse> openOrders(ProductLine productLine,
                                            long accountId,
                                            String instrumentId,
                                            Instant now) {
        String key = orderSnapshotKey(productLine, accountId, instrumentId);
        CachedOpenOrders cached = openOrderSnapshots.get(key);
        Duration interval = properties.getQuoting().getOrderReconciliationInterval();
        if (cached != null && interval != null && cached.refreshedAt().plus(interval).isAfter(now)) {
            return cached.orders();
        }
        OrderQueryResponse response = orderRpcApi.openOrders(accountId, instrumentId,
                properties.getQuoting().getMaxOpenOrdersPerAccountSymbol(), null);
        if (response == null || response.orders() == null) {
            throw new IllegalStateException("open order snapshot is unavailable");
        }
        List<OrderResponse> orders = List.copyOf(response.orders());
        openOrderSnapshots.put(key, new CachedOpenOrders(orders, now));
        return orders;
    }

    private void rememberOrder(ProductLine productLine, long accountId, String instrumentId, OrderResponse order) {
        if (order == null) {
            return;
        }
        String key = orderSnapshotKey(productLine, accountId, instrumentId);
        openOrderSnapshots.computeIfPresent(key, (ignored, cached) -> {
            List<OrderResponse> orders = new ArrayList<>(cached.orders());
            orders.removeIf(existing -> existing != null && existing.orderId() == order.orderId());
            orders.add(order);
            return new CachedOpenOrders(List.copyOf(orders), cached.refreshedAt());
        });
    }

    private void forgetOrder(ProductLine productLine, long accountId, String instrumentId, long orderId) {
        String key = orderSnapshotKey(productLine, accountId, instrumentId);
        openOrderSnapshots.computeIfPresent(key, (ignored, cached) -> new CachedOpenOrders(
                cached.orders().stream().filter(order -> order == null || order.orderId() != orderId).toList(),
                cached.refreshedAt()));
    }

    private String orderSnapshotKey(ProductLine productLine, long accountId, String instrumentId) {
        return productLine.name() + ":" + accountId + ":" + instrumentId;
    }

    private MarkPriceResponse latestMarkPrice(String instrumentId, long instrumentChangeId) {
        MarkPriceEvent event = markPriceCache.requireFresh(instrumentId);
        if (event.instrumentChangeId() != instrumentChangeId) {
            throw new IllegalStateException("mark price instrument version mismatch for " + instrumentId
                    + ": expected=" + instrumentChangeId + ", actual=" + event.instrumentChangeId());
        }
        return new MarkPriceResponse(event.instrumentId(), event.markPrice(), event.markPriceUnits(), event.indexPrice(),
                event.price1(), event.price2(), event.lastTradePrice(), event.bestBidPrice(), event.bestAskPrice(),
                event.fundingRate(), event.nextFundingTime(), event.timeUntilFundingSeconds(), event.basisAverage(),
                event.basisWindowSeconds(), event.clampLow(), event.clampHigh(), event.sequence(), event.status(),
                event.eventTime());
    }

    /** 现货没有持仓对象，做市库存由资产余额约束；这里不能调用永续持仓接口。 */
    private PositionResponse currentPosition(MarketMakerProperties.Strategy strategy,
                                             long accountId,
                                             String instrumentId,
                                             InstrumentResponse instrument) {
        if (strategy.getProductLine() == ProductLine.SPOT) {
            if (instrument == null || instrument.baseAsset() == null || instrument.baseAsset().isBlank()) {
                throw new IllegalStateException("现货做市缺少基础资产快照");
            }
            // 现货没有持仓对象，使用账户 JVM 快照中的基础资产权益作为库存约束。
            // AccountRpcApi 只访问账户服务本地快照，不会在报价周期内查询数据库。
            var balance = accountRpcApi.balance(accountId, instrument.baseAsset());
            long inventory = balance == null ? 0L : Math.max(0L, balance.equityUnits());
            return new PositionResponse(accountId, instrumentId,
                    strategy.getMarginMode(), PositionSide.NET, inventory, 0L, 0L, Instant.now());
        }
        return accountRpcApi.position(accountId, instrumentId, strategy.getMarginMode().name(), PositionSide.NET.name());
    }

    private MarkPriceResponse currentMarkPrice(ProductLine productLine, String instrumentId, long instrumentChangeId) {
        return latestMarkPrice(instrumentId, instrumentChangeId);
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

    private String accountPrefix(MarketMakerProperties.Strategy strategy, String instrumentId, long accountId) {
        return "mm-" + stableToken(strategy.getProductLine().name() + ":" + strategy.getStrategyId() + ":" + instrumentId)
                + "-" + accountId + "-";
    }

    private String quotePrefix(String accountPrefix, OrderSide side, int level) {
        return accountPrefix + (side == OrderSide.BUY ? "b" : "s") + level + "-";
    }

    private String takerClientOrderId(MarketMakerProperties.Strategy strategy,
                                      String instrumentId,
                                      long accountId,
                                      long cycleSequence) {
        return "mm-tk-" + stableToken(strategy.getProductLine().name() + ":" + strategy.getStrategyId() + ":" + instrumentId)
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
        effective.setInstrumentIds(new ArrayList<>(configured.getInstrumentIds()));
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
                List.copyOf(strategy.getAccountIds()), List.copyOf(strategy.getInstrumentIds()),
                strategy.getBaseQuantitySteps(), strategy.getMarginMode(), strategy.getSpreadTicks(),
                strategy.getLevelSpacingTicks(), strategy.getMaxInventorySteps(), strategy.getMaxInventorySkewPpm(),
                strategy.getOrderLevels());
    }

    private MarketMakerProperties.Strategy findStrategy(String strategyId, ProductLine productLine) {
        String normalized = normalizeRequired(strategyId, "strategyId");
        return strategiesSnapshot(productLine).stream()
                .filter(strategy -> strategy.getStrategyId().equalsIgnoreCase(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown market-maker strategy: " + strategyId));
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
                List.copyOf(strategy.getInstrumentIds()),
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
        String normalized = normalizeRequired(value, "instrumentId");
        if (!com.surprising.product.api.InstrumentIds.valid(normalized)) {
            throw new IllegalArgumentException("invalid instrumentId: " + value);
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
                                            List<String> instrumentIds,
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
                                            String instrumentId,
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
                                            String error,
                                            MarketMakerLiquidityMetric liquidity) {
    }

    public record MarketMakerAnomaly(String severity,
                                     String type,
                                     String strategyId,
                                     ProductLine productLine,
                                     String instrumentId,
                                     long accountId,
                                     long metricValue,
                                     long threshold,
                                     String summary) {
    }

    public record MarketMakerMetricWarning(String strategyId,
                                           ProductLine productLine,
                                           String instrumentId,
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
                                                    String instrumentId,
                                                    Long accountId,
                                                    String eventType,
                                                    int limit) {
            return List.of();
        }

        @Override
        public CursorPage<MarketMakerRunEventRecord> findPage(ProductLine productLine,
                                                              String strategyId,
                                                              String instrumentId,
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
