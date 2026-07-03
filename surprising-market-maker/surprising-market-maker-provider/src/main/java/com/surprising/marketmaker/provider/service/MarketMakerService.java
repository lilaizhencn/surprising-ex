package com.surprising.marketmaker.provider.service;

import com.surprising.account.api.client.AccountRpcApi;
import com.surprising.account.api.model.PositionResponse;
import com.surprising.instrument.api.client.InstrumentRpcApi;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.marketmaker.api.model.MarketMakerRunRequest;
import com.surprising.marketmaker.api.model.MarketMakerStrategyQueryResponse;
import com.surprising.marketmaker.api.model.MarketMakerStrategyResponse;
import com.surprising.marketmaker.api.model.MarketMakerStrategyStatus;
import com.surprising.marketmaker.provider.config.MarketMakerProperties;
import com.surprising.marketmaker.provider.model.DesiredQuote;
import com.surprising.marketmaker.provider.model.QuotePlan;
import com.surprising.marketmaker.provider.model.StrategyConfigOverride;
import com.surprising.marketmaker.provider.model.StrategyRuntimeState;
import com.surprising.marketmaker.provider.repository.MarketMakerAdminRepository;
import com.surprising.marketmaker.provider.repository.MarketMakerAdminRepository.CursorPage;
import com.surprising.marketmaker.provider.repository.MarketMakerAdminRepository.MarketMakerPnlAttributionRecord;
import com.surprising.marketmaker.provider.repository.MarketMakerAdminRepository.MarketMakerPnlScope;
import com.surprising.marketmaker.provider.repository.MarketMakerAdminRepository.MarketMakerRunEventRecord;
import com.surprising.marketmaker.provider.repository.MarketMakerAdminRepository.MarketMakerRunEventWrite;
import com.surprising.marketmaker.provider.repository.MarketMakerStrategyOverrideStore;
import com.surprising.price.api.client.MarkPriceRpcApi;
import com.surprising.price.api.model.MarkPriceResponse;
import com.surprising.trading.api.TraceContext;
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
import java.util.concurrent.ThreadLocalRandom;
import java.util.zip.CRC32;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class MarketMakerService {

    private static final Logger log = LoggerFactory.getLogger(MarketMakerService.class);
    private static final Set<OrderStatus> LIVE_STATUSES = EnumSet.of(OrderStatus.ACCEPTED, OrderStatus.PARTIALLY_FILLED);

    private final MarketMakerProperties properties;
    private final InstrumentRpcApi instrumentRpcApi;
    private final MarkPriceRpcApi markPriceRpcApi;
    private final MarketDataRpcApi marketDataRpcApi;
    private final OrderRpcApi orderRpcApi;
    private final AccountRpcApi accountRpcApi;
    private final QuotePlanner quotePlanner;
    private final MarketMakerLeaseCoordinator leaseCoordinator;
    private final MarketMakerStrategyOverrideStore overrideStore;
    private final MarketMakerAdminRepository adminRepository;
    private final Map<String, StrategyRuntimeState> states = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastTradeTimes = new ConcurrentHashMap<>();
    private final Map<String, OrderSide> lastTradeSides = new ConcurrentHashMap<>();
    private volatile Map<String, StrategyConfigOverride> strategyOverrides = Map.of();
    private volatile Instant strategyOverridesLoadedAt = Instant.EPOCH;
    private final String nodeId;
    private final String orderNonce;

    MarketMakerService(MarketMakerProperties properties,
                       InstrumentRpcApi instrumentRpcApi,
                       MarkPriceRpcApi markPriceRpcApi,
                       MarketDataRpcApi marketDataRpcApi,
                       OrderRpcApi orderRpcApi,
                       AccountRpcApi accountRpcApi,
                       QuotePlanner quotePlanner,
                       MarketMakerLeaseCoordinator leaseCoordinator,
                       MarketMakerStrategyOverrideStore overrideStore) {
        this(properties, instrumentRpcApi, markPriceRpcApi, marketDataRpcApi, orderRpcApi, accountRpcApi,
                quotePlanner, leaseCoordinator, overrideStore, new NoopMarketMakerAdminRepository());
    }

    @Autowired
    public MarketMakerService(MarketMakerProperties properties,
                              InstrumentRpcApi instrumentRpcApi,
                              MarkPriceRpcApi markPriceRpcApi,
                              MarketDataRpcApi marketDataRpcApi,
                              OrderRpcApi orderRpcApi,
                              AccountRpcApi accountRpcApi,
                              QuotePlanner quotePlanner,
                              MarketMakerLeaseCoordinator leaseCoordinator,
                              MarketMakerStrategyOverrideStore overrideStore,
                              MarketMakerAdminRepository adminRepository) {
        this.properties = properties;
        this.instrumentRpcApi = instrumentRpcApi;
        this.markPriceRpcApi = markPriceRpcApi;
        this.marketDataRpcApi = marketDataRpcApi;
        this.orderRpcApi = orderRpcApi;
        this.accountRpcApi = accountRpcApi;
        this.quotePlanner = quotePlanner;
        this.leaseCoordinator = leaseCoordinator;
        this.overrideStore = overrideStore;
        this.adminRepository = adminRepository;
        this.nodeId = resolveNodeId(properties.getEngine().getNodeId());
        this.orderNonce = Long.toUnsignedString(System.currentTimeMillis(), 36)
                + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Scheduled(fixedDelayString = "${surprising.market-maker.engine.cycle-delay-ms:250}")
    public void scheduledRun() {
        if (!properties.getEngine().isEnabled()) {
            return;
        }
        runOnce(new MarketMakerRunRequest(null, null));
    }

    public MarketMakerStrategyQueryResponse strategies() {
        List<MarketMakerStrategyResponse> responses = strategiesSnapshot().stream()
                .map(this::response)
                .toList();
        return new MarketMakerStrategyQueryResponse(responses.size(), responses);
    }

    public MarketMakerStrategyResponse strategy(String strategyId) {
        return response(findStrategy(strategyId));
    }

    public MarketMakerStrategyResponse pause(String strategyId) {
        MarketMakerProperties.Strategy strategy = findStrategy(strategyId);
        state(strategy).pause();
        return response(strategy);
    }

    public MarketMakerStrategyResponse resume(String strategyId) {
        MarketMakerProperties.Strategy strategy = findStrategy(strategyId);
        state(strategy).resume();
        return response(strategy);
    }

    public MarketMakerStrategyConfigResponse strategyConfig(String strategyId) {
        MarketMakerProperties.Strategy configured = findConfiguredStrategy(strategyId);
        StrategyConfigOverride override = strategyOverrides().get(strategyKey(configured.getStrategyId()));
        return configResponse(configured, override);
    }

    public MarketMakerStrategyConfigResponse updateStrategyConfig(String strategyId,
                                                                  MarketMakerStrategyConfigUpdateRequest request,
                                                                  String adminUserId) {
        MarketMakerProperties.Strategy configured = findConfiguredStrategy(strategyId);
        MarketMakerStrategyConfigUpdateRequest safeRequest = request == null
                ? new MarketMakerStrategyConfigUpdateRequest(null, null, null, null, null, null, null, null, null)
                : request;
        String reason = normalizeReason(safeRequest.reason());
        StrategyConfigOverride override = new StrategyConfigOverride(
                configured.getStrategyId(),
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
            overrideStore.delete(configured.getStrategyId());
            removeCachedOverride(configured.getStrategyId());
        }
        return configResponse(configured, saved);
    }

    public MarketMakerStrategyQueryResponse runOnce(MarketMakerRunRequest request) {
        String traceId = TraceContext.currentOrCreate();
        String requestedStrategyId = normalizeOptional(request == null ? null : request.strategyId());
        String requestedSymbol = normalizeOptional(request == null ? null : request.symbol());
        try {
            for (MarketMakerProperties.Strategy strategy : strategiesSnapshot()) {
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
        int boundedLimit = Math.max(1, Math.min(limit, 500));
        Instant now = Instant.now();
        List<MarketMakerStrategyMetric> rows = new ArrayList<>();
        List<MarketMakerAnomaly> anomalies = new ArrayList<>();
        List<MarketMakerMetricWarning> warnings = new ArrayList<>();
        for (MarketMakerProperties.Strategy strategy : strategiesSnapshot()) {
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
        return new MarketMakerAdminMetricsResponse(now, nodeId, totals(rows, anomalies), rows, anomalies, warnings);
    }

    public MarketMakerRunLogQueryResponse runLogs(String strategyId,
                                                  String symbol,
                                                  Long accountId,
                                                  String eventType,
                                                  int limit) {
        return new MarketMakerRunLogQueryResponse(
                Instant.now(),
                adminRepository.runEvents(
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
        CursorPage<MarketMakerRunEventRecord> page = adminRepository.runEventsPage(
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

    public MarketMakerPnlAttributionResponse pnlAttribution(String strategyId,
                                                            String symbol,
                                                            Long accountId,
                                                            int windowHours,
                                                            int limit) {
        int boundedWindowHours = Math.max(1, Math.min(windowHours, 24 * 31));
        int boundedLimit = Math.max(1, Math.min(limit, 500));
        Instant until = Instant.now();
        Instant since = until.minus(Duration.ofHours(boundedWindowHours));
        List<MarketMakerPnlScope> scopes = pnlScopes(strategyId, symbol, accountId, boundedLimit);
        List<MarketMakerPnlAttributionRecord> rows = adminRepository.pnlAttribution(scopes, since, until);
        long totalTrades = rows.stream().mapToLong(MarketMakerPnlAttributionRecord::totalTrades).sum();
        long makerTrades = rows.stream().mapToLong(MarketMakerPnlAttributionRecord::makerTrades).sum();
        long takerTrades = rows.stream().mapToLong(MarketMakerPnlAttributionRecord::takerTrades).sum();
        long netFeeUnits = rows.stream().mapToLong(MarketMakerPnlAttributionRecord::netFeeUnits).sum();
        long realizedPnlUnits = rows.stream().mapToLong(MarketMakerPnlAttributionRecord::currentRealizedPnlUnits).sum();
        long signedInventorySteps = rows.stream().mapToLong(MarketMakerPnlAttributionRecord::signedInventorySteps).sum();
        return new MarketMakerPnlAttributionResponse(
                until,
                since,
                boundedWindowHours,
                new MarketMakerPnlAttributionTotals(
                        rows.size(),
                        totalTrades,
                        makerTrades,
                        takerTrades,
                        netFeeUnits,
                        realizedPnlUnits,
                        signedInventorySteps),
                rows);
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
        String accountPrefix = accountPrefix(strategy, symbol, accountId);
        List<MarketMakerAnomaly> rowAnomalies = new ArrayList<>();
        if (!strategy.isEnabled()) {
            rowAnomalies.add(anomaly("INFO", "STRATEGY_DISABLED", strategyId, symbol, accountId,
                    0, 1, "strategy is disabled by configuration"));
        }
        if (state.paused()) {
            rowAnomalies.add(anomaly("INFO", "STRATEGY_PAUSED", strategyId, symbol, accountId,
                    1, 0, "strategy is paused at runtime"));
        }
        if (state.lastError() != null) {
            rowAnomalies.add(anomaly("CRITICAL", "LAST_CYCLE_FAILED", strategyId, symbol, accountId,
                    1, 0, state.lastError()));
        }
        try {
            PositionResponse position = accountRpcApi.position(accountId, symbol, strategy.getMarginMode().name(),
                    PositionSide.NET.name());
            List<OrderResponse> openOrders = openOrders(accountId, symbol);
            List<OrderResponse> ownedLive = openOrders.stream()
                    .filter(order -> ownsOrder(accountPrefix, order))
                    .filter(this::isLive)
                    .toList();
            long staleOwned = ownedLive.stream().filter(order -> isStale(order, now)).count();
            InstrumentResponse instrument = instrumentRpcApi.latest(symbol);
            OrderBookSnapshotResponse orderBook = marketDataRpcApi.orderBook(symbol,
                    properties.getQuoting().getOrderBookDepth());
            MarkPriceResponse markPrice = latestMarkPrice(symbol);
            QuotePlan plan = instrument == null || instrument.status() != InstrumentStatus.TRADING
                    ? new QuotePlan(0L, position.signedQuantitySteps(), List.of())
                    : quotePlanner.plan(strategy, properties.getQuoting(), properties.getRisk(), instrument,
                    orderBook, markPrice, position.signedQuantitySteps());
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
                rowAnomalies.add(anomaly("CRITICAL", "INVENTORY_LIMIT_REACHED", strategyId, symbol, accountId,
                        absInventory, maxInventory, "signed inventory reached configured limit"));
            } else if (inventoryUsagePpm >= Math.max(0L, effectiveInventorySkewPpm(strategy))) {
                rowAnomalies.add(anomaly("WARN", "INVENTORY_SKEW_HIGH", strategyId, symbol, accountId,
                        inventoryUsagePpm, effectiveInventorySkewPpm(strategy), "inventory usage exceeds skew threshold"));
            }
            if (desiredQuotes > 0 && missingDesired > 0) {
                rowAnomalies.add(anomaly("WARN", "MISSING_DESIRED_QUOTES", strategyId, symbol, accountId,
                        missingDesired, desiredQuotes, "some desired quote levels are not live"));
            }
            if (ownedLive.isEmpty() && strategy.isEnabled() && !state.paused()) {
                rowAnomalies.add(anomaly("CRITICAL", "NO_LIVE_QUOTES", strategyId, symbol, accountId,
                        0, desiredQuotes, "no owned live quotes are present"));
            }
            if (staleOwned > 0) {
                rowAnomalies.add(anomaly("WARN", "STALE_QUOTES", strategyId, symbol, accountId,
                        staleOwned, 0, "owned live quotes exceed stale age"));
            }
            if (offTargetOwned > 0) {
                rowAnomalies.add(anomaly("WARN", "OFF_TARGET_QUOTES", strategyId, symbol, accountId,
                        offTargetOwned, 0, "owned live quotes do not match target levels"));
            }
            if (instrument == null || instrument.status() != InstrumentStatus.TRADING) {
                rowAnomalies.add(anomaly("CRITICAL", "INSTRUMENT_NOT_TRADING", strategyId, symbol, accountId,
                        1, 0, "instrument is unavailable or not TRADING"));
            }
            anomalies.addAll(rowAnomalies);
            return new MarketMakerStrategyMetric(
                    strategyId, symbol, accountId, strategyStatus, qualityStatus(rowAnomalies),
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
            anomalies.add(anomaly("CRITICAL", "METRIC_COLLECTION_FAILED", strategyId, symbol, accountId,
                    1, 0, message));
            warnings.add(new MarketMakerMetricWarning(strategyId, symbol, accountId, message));
            return new MarketMakerStrategyMetric(
                    strategyId, symbol, accountId, strategyStatus, "CRITICAL", strategy.isEnabled(), state.paused(),
                    state.cycleSequence(), state.submittedOrders(), state.canceledOrders(), state.rejectedOrders(),
                    state.skippedCycles(), 0, 0, effectiveMaxInventorySteps(strategy), 0, 0, null,
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    state.lastTraceId(), state.lastError(), state.lastCycleTime(), message);
        }
    }

    private MarketMakerMetricsTotals totals(List<MarketMakerStrategyMetric> rows, List<MarketMakerAnomaly> anomalies) {
        List<MarketMakerStrategyResponse> strategies = strategies().strategies();
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

    private List<MarketMakerPnlScope> pnlScopes(String strategyId, String symbol, Long accountId, int limit) {
        String requestedStrategyId = normalizeOptional(strategyId);
        String requestedSymbol = symbol == null || symbol.isBlank() ? null : normalizeSymbol(symbol);
        List<MarketMakerPnlScope> scopes = new ArrayList<>();
        for (MarketMakerProperties.Strategy strategy : strategiesSnapshot()) {
            if (requestedStrategyId != null && !strategy.getStrategyId().equalsIgnoreCase(requestedStrategyId)) {
                continue;
            }
            for (String configuredSymbol : strategy.getSymbols()) {
                String normalizedSymbol = normalizeSymbol(configuredSymbol);
                if (requestedSymbol != null && !normalizedSymbol.equals(requestedSymbol)) {
                    continue;
                }
                for (long configuredAccountId : strategy.getAccountIds()) {
                    if (accountId != null && configuredAccountId != accountId) {
                        continue;
                    }
                    scopes.add(new MarketMakerPnlScope(
                            strategy.getStrategyId(),
                            normalizedSymbol,
                            configuredAccountId,
                            strategy.getMarginMode().name(),
                            accountPrefix(strategy, normalizedSymbol, configuredAccountId)));
                    if (scopes.size() >= limit) {
                        return scopes;
                    }
                }
            }
        }
        return scopes;
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
                                       String symbol,
                                       long accountId,
                                       long metricValue,
                                       long threshold,
                                       String summary) {
        return new MarketMakerAnomaly(severity, type, strategyId, symbol, accountId, metricValue, threshold, summary);
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
            adminRepository.recordRunEvent(new MarketMakerRunEventWrite(
                    strategy.getStrategyId(),
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
        if (properties.getCoordination().isEnabled()
                && !leaseCoordinator.tryAcquire(strategy.getStrategyId(), symbol, nodeId,
                properties.getCoordination().getLeaseDuration())) {
            state.addSkipped(1L);
            recordRunEvent(strategy, symbol, null, cycleSequence, "SKIPPED",
                    0, 0, 0, "LEASE_NOT_ACQUIRED", null, traceId, Instant.now());
            return;
        }
        Instant now = Instant.now();
        try {
            InstrumentResponse instrument = instrumentRpcApi.latest(symbol);
            requireTradable(instrument);
            OrderBookSnapshotResponse orderBook = marketDataRpcApi.orderBook(symbol,
                    properties.getQuoting().getOrderBookDepth());
            MarkPriceResponse markPrice = latestMarkPrice(symbol);
            for (long accountId : strategy.getAccountIds()) {
                quoteAccount(strategy, state, cycleSequence, symbol, instrument, orderBook, markPrice, accountId,
                        now, traceId);
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
    }

    private void quoteAccount(MarketMakerProperties.Strategy strategy,
                              StrategyRuntimeState state,
                              long cycleSequence,
                              String symbol,
                              InstrumentResponse instrument,
                              OrderBookSnapshotResponse orderBook,
                              MarkPriceResponse markPrice,
                              long accountId,
                              Instant now,
                              String traceId) {
        PositionResponse position = accountRpcApi.position(accountId, symbol, strategy.getMarginMode().name(),
                PositionSide.NET.name());
        QuotePlan plan = quotePlanner.plan(strategy, properties.getQuoting(), properties.getRisk(), instrument,
                orderBook, markPrice, position.signedQuantitySteps());
        List<OrderResponse> openOrders = openOrders(accountId, symbol);
        ReconcileResult result = reconcile(strategy, accountId, symbol, plan, openOrders, cycleSequence, now);
        state.addCanceled(result.canceled());
        state.addSubmitted(result.submitted());
        state.addRejected(result.rejected());
        recordRunEvent(strategy, symbol, accountId, cycleSequence, "QUOTE_RECONCILED",
                result.submitted(), result.canceled(), result.rejected(), null, null, traceId, now);
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
        long canceled = 0L;
        for (OrderResponse order : owned) {
            if (shouldKeep(order, plan.quotes(), accountPrefix, now)) {
                kept.add(order);
            } else {
                cancel(accountId, order.orderId());
                canceled++;
            }
        }

        long submitted = 0L;
        long rejected = 0L;
        int maxOpenOrders = properties.getQuoting().getMaxOpenOrdersPerAccountSymbol();
        for (DesiredQuote quote : plan.quotes()) {
            if (kept.size() >= maxOpenOrders) {
                break;
            }
            if (hasLiveQuote(kept, quote, accountPrefix)) {
                continue;
            }
            OrderResponse response = place(strategy, accountId, symbol, quote, cycleSequence);
            if (response.status() == OrderStatus.REJECTED) {
                rejected++;
            } else {
                submitted++;
                kept.add(response);
            }
        }
        return new ReconcileResult(submitted, canceled, rejected);
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
        String accountPrefix = accountPrefix(strategy, symbol, accountId);
        String clientOrderId = quotePrefix(accountPrefix, quote.side(), quote.level())
                + cycleSequence + "-" + orderNonce;
        PlaceOrderRequest request = new PlaceOrderRequest(accountId, clientOrderId, symbol, quote.side(),
                OrderType.LIMIT, TimeInForce.GTX, quote.priceTicks(), quote.quantitySteps(),
                strategy.getMarginMode(), PositionSide.NET, false, true);
        return orderRpcApi.place(request);
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
        String tradeKey = strategy.getStrategyId() + ":" + symbol;
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
        PositionResponse position = accountRpcApi.position(accountId, symbol, strategy.getMarginMode().name(),
                PositionSide.NET.name());
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

    private void cancel(long accountId, long orderId) {
        orderRpcApi.cancel(new CancelOrderRequest(accountId, orderId));
    }

    private List<OrderResponse> openOrders(long accountId, String symbol) {
        OrderQueryResponse response = orderRpcApi.openOrders(accountId, symbol,
                properties.getQuoting().getMaxOpenOrdersPerAccountSymbol());
        return response == null || response.orders() == null ? List.of() : response.orders();
    }

    private MarkPriceResponse latestMarkPrice(String symbol) {
        try {
            return markPriceRpcApi.latestMarkPrice(symbol);
        } catch (RuntimeException ex) {
            log.debug("Mark price unavailable for market-maker symbol={}: {}", symbol, ex.getMessage());
            return null;
        }
    }

    private void requireTradable(InstrumentResponse instrument) {
        if (instrument == null || instrument.status() != InstrumentStatus.TRADING) {
            throw new IllegalStateException("instrument is not in TRADING status");
        }
    }

    private boolean ownsOrder(String accountPrefix, OrderResponse order) {
        return order != null && order.clientOrderId() != null && order.clientOrderId().startsWith(accountPrefix);
    }

    private String accountPrefix(MarketMakerProperties.Strategy strategy, String symbol, long accountId) {
        return "mm-" + stableToken(strategy.getStrategyId() + ":" + symbol) + "-" + accountId + "-";
    }

    private String quotePrefix(String accountPrefix, OrderSide side, int level) {
        return accountPrefix + (side == OrderSide.BUY ? "b" : "s") + level + "-";
    }

    private String takerClientOrderId(MarketMakerProperties.Strategy strategy,
                                      String symbol,
                                      long accountId,
                                      long cycleSequence) {
        return "mm-tk-" + stableToken(strategy.getStrategyId() + ":" + symbol) + "-" + accountId + "-"
                + cycleSequence + "-" + Long.toUnsignedString(ThreadLocalRandom.current().nextLong(), 36);
    }

    private String stableToken(String value) {
        CRC32 crc32 = new CRC32();
        crc32.update(value.getBytes(StandardCharsets.UTF_8));
        return Long.toUnsignedString(crc32.getValue(), 36);
    }

    private List<MarketMakerProperties.Strategy> strategiesSnapshot() {
        Map<String, StrategyConfigOverride> overrides = strategyOverrides();
        return properties.getStrategies().stream()
                .map(strategy -> applyOverride(strategy, overrides.get(strategyKey(strategy.getStrategyId()))))
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
                    next.put(strategyKey(override.strategyId()), override);
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
        next.put(strategyKey(override.strategyId()), override);
        strategyOverrides = Map.copyOf(next);
        strategyOverridesLoadedAt = Instant.now();
    }

    private void removeCachedOverride(String strategyId) {
        Map<String, StrategyConfigOverride> next = new HashMap<>(strategyOverrides());
        next.remove(strategyKey(strategyId));
        strategyOverrides = Map.copyOf(next);
        strategyOverridesLoadedAt = Instant.now();
    }

    private MarketMakerProperties.Strategy applyOverride(MarketMakerProperties.Strategy configured,
                                                         StrategyConfigOverride override) {
        MarketMakerProperties.Strategy effective = new MarketMakerProperties.Strategy();
        effective.setStrategyId(configured.getStrategyId());
        effective.setEnabled(override != null && override.enabled() != null ? override.enabled() : configured.isEnabled());
        effective.setAccountIds(new ArrayList<>(configured.getAccountIds()));
        effective.setSymbols(new ArrayList<>(configured.getSymbols()));
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
        return new MarketMakerStrategyConfig(strategy.getStrategyId(), strategy.isEnabled(),
                List.copyOf(strategy.getAccountIds()), List.copyOf(strategy.getSymbols()),
                strategy.getBaseQuantitySteps(), strategy.getMarginMode(), strategy.getSpreadTicks(),
                strategy.getLevelSpacingTicks(), strategy.getMaxInventorySteps(), strategy.getMaxInventorySkewPpm(),
                strategy.getOrderLevels());
    }

    private MarketMakerProperties.Strategy findStrategy(String strategyId) {
        String normalized = normalizeRequired(strategyId, "strategyId");
        return strategiesSnapshot().stream()
                .filter(strategy -> strategy.getStrategyId().equalsIgnoreCase(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown market-maker strategy: " + strategyId));
    }

    private MarketMakerProperties.Strategy findConfiguredStrategy(String strategyId) {
        String normalized = normalizeRequired(strategyId, "strategyId");
        return properties.getStrategies().stream()
                .filter(strategy -> strategy.getStrategyId().equalsIgnoreCase(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown market-maker strategy: " + strategyId));
    }

    private StrategyRuntimeState state(MarketMakerProperties.Strategy strategy) {
        return states.computeIfAbsent(strategy.getStrategyId(), ignored -> new StrategyRuntimeState());
    }

    private MarketMakerStrategyResponse response(MarketMakerProperties.Strategy strategy) {
        StrategyRuntimeState state = state(strategy);
        MarketMakerStrategyStatus status = status(strategy, state);
        return new MarketMakerStrategyResponse(strategy.getStrategyId(), List.copyOf(strategy.getSymbols()),
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

    private String strategyKey(String strategyId) {
        return strategyId == null ? "" : strategyId.trim().toLowerCase(Locale.ROOT);
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

    public record MarketMakerPnlAttributionResponse(Instant generatedAt,
                                                    Instant since,
                                                    int windowHours,
                                                    MarketMakerPnlAttributionTotals totals,
                                                    List<MarketMakerPnlAttributionRecord> rows) {
    }

    public record MarketMakerPnlAttributionTotals(long rowCount,
                                                  long totalTrades,
                                                  long makerTrades,
                                                  long takerTrades,
                                                  long netFeeUnits,
                                                  long currentRealizedPnlUnits,
                                                  long signedInventorySteps) {
    }

    public record MarketMakerStrategyConfigResponse(MarketMakerStrategyConfig configured,
                                                    MarketMakerStrategyConfig effective,
                                                    StrategyConfigOverride override) {
    }

    public record MarketMakerStrategyConfig(String strategyId,
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
                                     String symbol,
                                     long accountId,
                                     long metricValue,
                                     long threshold,
                                     String summary) {
    }

    public record MarketMakerMetricWarning(String strategyId,
                                           String symbol,
                                           long accountId,
                                           String message) {
    }

    private record ReconcileResult(long submitted, long canceled, long rejected) {
    }

    private record TradeTarget(long priceTicks, long availableQuantitySteps) {
    }

    private static final class NoopMarketMakerAdminRepository implements MarketMakerAdminRepository {
        @Override
        public void recordRunEvent(MarketMakerRunEventWrite event) {
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
            return new CursorPage<>(List.of(), null, false, sort, Math.max(1, limit));
        }

        @Override
        public List<MarketMakerPnlAttributionRecord> pnlAttribution(List<MarketMakerPnlScope> scopes,
                                                                    Instant since,
                                                                    Instant until) {
            return List.of();
        }
    }
}
