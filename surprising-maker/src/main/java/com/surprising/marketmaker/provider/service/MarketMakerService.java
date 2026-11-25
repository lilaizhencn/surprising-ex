package com.surprising.marketmaker.provider.service;

import com.surprising.marketmaker.provider.model.MarketMakerAdminModels.*;
import com.surprising.marketmaker.provider.model.MarketMakerRunEventPageQuery;

import lombok.extern.slf4j.Slf4j;
import com.surprising.account.api.model.PositionResponse;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.marketmaker.api.model.MarketMakerRunRequest;
import com.surprising.marketmaker.api.model.MarketMakerStrategyQueryResponse;
import com.surprising.marketmaker.api.model.MarketMakerStrategyResponse;
import com.surprising.marketmaker.api.model.MarketMakerStrategyStatus;
import com.surprising.marketmaker.provider.config.MarketMakerProperties;
import com.surprising.marketmaker.provider.client.MakerAeronTradingGateway;
import com.surprising.trading.api.model.MakerQuoteInputs;
import com.surprising.marketmaker.provider.model.DesiredQuote;
import com.surprising.marketmaker.provider.model.ExternalTrade;
import com.surprising.marketmaker.provider.model.QuotePlan;
import com.surprising.marketmaker.provider.model.ReferenceOrderBookSnapshot;
import com.surprising.marketmaker.provider.model.StrategyConfigOverride;
import com.surprising.marketmaker.provider.model.StrategyRuntimeState;
import com.surprising.marketmaker.provider.repository.MarketMakerRunEventRepository;
import com.surprising.marketmaker.provider.repository.MarketMakerRunEventRepository.CursorPage;
import com.surprising.marketmaker.provider.repository.MarketMakerRunEventRepository.MarketMakerRunEventRecord;
import com.surprising.marketmaker.provider.repository.MarketMakerRunEventRepository.MarketMakerRunEventWrite;
import com.surprising.marketmaker.provider.store.MarketMakerStrategyOverrideStore;
import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.api.model.MarkPriceResponse;
import com.surprising.product.api.ProductLine;
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

    private final MarketMakerProperties properties;
    private final InstrumentSnapshotCache instrumentSnapshotCache;
    private final MarketMakerMarkPriceCache markPriceCache;
    private final QuotePlanner quotePlanner;
    private final MakerAeronTradingGateway aeronGateway;
    private final ReferenceMarketProvider referenceMarketProvider;
    private final MarketMakerStrategyOverrideStore overrideStore;
    private final MarketMakerRunEventRepository runEventRepository;
    private final Map<String, StrategyRuntimeState> states = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> cycleLocks = new ConcurrentHashMap<>();
    private final Map<String, CachedOpenOrders> openOrderSnapshots = new ConcurrentHashMap<>();
    private final Map<String, PriceState> priceStates = new ConcurrentHashMap<>();
    private final String nodeId;
    private final String orderNonce;
    // A confirmed rejection can be retried after freeing budget in the same cycle; that is a new order.
    private final java.util.concurrent.atomic.AtomicLong quoteRequestSequence = new java.util.concurrent.atomic.AtomicLong();
    // One bounded diagnostic budget for the process; watchdog failures must not flood logs.
    private final java.util.concurrent.atomic.AtomicLong nextQuoteFailureLogNanos = new java.util.concurrent.atomic.AtomicLong();

    public MarketMakerService(MarketMakerProperties properties,
                              MarketMakerMarkPriceCache markPriceCache,
                              QuotePlanner quotePlanner,
                              ReferenceMarketProvider referenceMarketProvider,
                              MarketMakerStrategyOverrideStore overrideStore,
                              MarketMakerRunEventRepository runEventRepository,
                              InstrumentSnapshotCache instrumentSnapshotCache,
                              MakerAeronTradingGateway aeronGateway) {
        this.aeronGateway = aeronGateway;
        this.properties = properties;
        this.instrumentSnapshotCache = instrumentSnapshotCache;
        this.markPriceCache = markPriceCache;
        this.quotePlanner = quotePlanner;
        this.referenceMarketProvider = referenceMarketProvider;
        this.overrideStore = overrideStore;
        this.runEventRepository = runEventRepository;
        this.nodeId = resolveNodeId(properties.getEngine().getNodeId());
        this.orderNonce = Long.toUnsignedString(System.currentTimeMillis(), 36)
                + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** 一个策略的连续工作线程入口；false 表示暂停、未取得租约或本轮失败。 */
    public boolean runScheduledStrategy(String strategyId, ProductLine productLine) {
        var strategy = findStrategy(strategyId, productLine);
        if (!properties.getEngine().isEnabled()) strategy.setEnabled(false);
        return runStrategy(strategy, null, false);
    }

    /** 测试吃单独立于被动报价；同一策略币对仍只允许一个吃单任务。 */
    public boolean runScheduledTrades(String strategyId, ProductLine productLine) {
        if (!properties.getEngine().isEnabled() || !properties.getTrade().isEnabled()) return false;
        MarketMakerProperties.Strategy strategy = findStrategy(strategyId, productLine);
        if (!strategy.isEnabled() || state(strategy).paused()) return false;

        boolean submitted = false;
        for (String instrumentId : strategy.getInstrumentIds()) submitted |= tradeSymbol(strategy, normalizeSymbol(instrumentId));
        return submitted;
    }

    private boolean tradeSymbol(MarketMakerProperties.Strategy strategy, String instrumentId) {
        if (!properties.getTrade().isEnabled()) return false;
        AtomicBoolean lock = cycleLocks.computeIfAbsent(strategyKey(strategy) + ":" + instrumentId + ":trade",
                ignored -> new AtomicBoolean());
        if (!lock.compareAndSet(false, true)) return false;
        try {
            InstrumentResponse instrument = currentInstrument(strategy.getProductLine(), instrumentId);
            requireTradable(instrument, strategy.getProductLine());
            MarkPriceResponse mark = latestMarkPrice(strategy.getProductLine(), instrumentId, instrument);
            StrategyRuntimeState state = state(strategy);
            return maybeTrade(strategy, state, state.cycleSequence(), instrumentId, instrument, mark, Instant.now());
        } catch (RuntimeException ex) {
            log.warn("Market-maker simulated trade failed strategyId={} instrumentId={} error={}",
                    strategy.getStrategyId(), instrumentId, ex.getMessage());
            return false;
        } finally {
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
        return instrumentSnapshotCache.current(productLine, com.surprising.product.api.utils.InstrumentIds.parse(instrumentId))
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
        StrategyConfigOverride override = overrideStore.find(configured.getProductLine(), configured.getStrategyId()).orElse(null);
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
        } else {
            overrideStore.delete(configured.getProductLine(), configured.getStrategyId());
        }
        return configResponse(configured, saved);
    }

    public MarketMakerStrategyQueryResponse runOnce(MarketMakerRunRequest request) {
        String requestedStrategyId = normalizeOptional(request == null ? null : request.strategyId());
        String requestedSymbol = normalizeOptional(request == null ? null : request.instrumentId());
        ProductLine requestedProductLine = request == null ? null : request.productLine();

        for (MarketMakerProperties.Strategy strategy : strategiesSnapshot(requestedProductLine)) {
            if (requestedStrategyId != null && !strategy.getStrategyId().equalsIgnoreCase(requestedStrategyId)) {
                continue;
            }
            runStrategy(strategy, requestedSymbol, true);
        }
        return strategies();
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
        return new MarketMakerAdminMetricsResponse(now, nodeId,
                MakerMetricsSupport.totals(strategies(productLine).strategies(), rows, anomalies),
                rows, anomalies, warnings);
    }

    public MarketMakerRunLogQueryResponse runLogs(ProductLine productLine, String strategyId, String instrumentId,
                                                  Long accountId, String eventType, MarketMakerRunEventPageQuery query) {
        CursorPage<MarketMakerRunEventRecord> page = runEventRepository.findPage(
                productLine, strategyId, instrumentId, accountId, eventType, query);
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
                    .filter(MakerQuoteMatchingSupport::isLive)
                    .toList();
            InstrumentResponse instrument = currentInstrument(productLine, instrumentId);
            PositionResponse position = currentPosition(strategy, accountId, instrumentId, instrument);
            OrderBookSnapshotResponse orderBook = fetchOrderBook(productLine, instrumentId,
                    properties.getQuoting().getOrderBookDepth());
            MarkPriceResponse markPrice = latestMarkPrice(productLine, instrumentId, instrument);
            ReferenceOrderBookSnapshot referenceOrderBook = referenceMarketProvider.snapshot(instrumentId, productLine, instrument);
            QuotePlan plan = !isTradableForProduct(instrument, productLine)
                    || properties.getReferenceMarket().isEnabled() && referenceOrderBook == null
                    ? new QuotePlan(0L, position.signedQuantitySteps(), List.of(), 0)
                    : quotePlanner.plan(strategy, properties.getQuoting(), properties.getRisk(), instrument,
                    orderBook, markPrice, position.signedQuantitySteps(), currentVolatility(strategy, instrumentId),
                    referenceOrderBook, 0L);
            long staleOwned = properties.getReferenceMarket().isEnabled() && referenceOrderBook == null
                    ? ownedLive.size() : 0;
            int desiredQuotes = plan.quotes().size();
            var quoting = properties.getQuoting();
            long matchedDesired = plan.quotes().stream()
                    .filter(quote -> hasLiveQuote(ownedLive, quote, accountPrefix, quoting))
                    .count();
            long offTargetOwned = ownedLive.stream()
                    .filter(order -> plan.quotes().stream().noneMatch(quote -> matchesQuote(order, quote, accountPrefix, quoting)))
                    .count();
            long missingDesired = Math.max(0, desiredQuotes - matchedDesired);
            long maxInventory = effectiveMaxInventorySteps(strategy);
            long absInventory = Math.abs(position.signedQuantitySteps());
            long inventoryUsagePpm = maxInventory <= 0 ? 0 : Math.min(10_000_000L,
                    Math.round(absInventory * 1_000_000.0d / maxInventory));
            long bestBid = MakerSimulatedTradeSupport.bestBid(orderBook);
            long bestAsk = MakerSimulatedTradeSupport.bestAsk(orderBook);
            long spreadTicks = bestBid > 0 && bestAsk > 0 ? Math.max(0, bestAsk - bestBid) : 0;
            long midTicks = MakerSimulatedTradeSupport.midPriceTicks(orderBook);
            long spreadPpm = midTicks <= 0 || spreadTicks <= 0 ? 0
                    : Math.round(spreadTicks * 1_000_000.0d / midTicks);
            long quoteCoveragePpm = desiredQuotes <= 0 ? 0
                    : Math.round(matchedDesired * 1_000_000.0d / desiredQuotes);
            long markTicks = MakerSimulatedTradeSupport.markPriceTicks(instrument, markPrice);

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
                        staleOwned, 0, "owned live quotes have no fresh reference book"));
            }
            if (offTargetOwned > 0) {
                rowAnomalies.add(anomaly("WARN", "OFF_TARGET_QUOTES", strategyId, productLine, instrumentId, accountId,
                        offTargetOwned, 0, "owned live quotes do not match target levels"));
            }
            if (!isTradableForProduct(instrument, productLine)) {
                rowAnomalies.add(anomaly("CRITICAL", "INSTRUMENT_NOT_TRADING", strategyId, productLine, instrumentId, accountId,
                        1, 0, "instrument is unavailable or not TRADING"));
            }
            MarketMakerLiquidityMetric liquidity = MakerMetricsSupport.linearLiquidity(properties, instrument, orderBook, Instant.now());
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
                    strategyId, productLine, instrumentId, accountId, strategyStatus, MakerMetricsSupport.qualityStatus(rowAnomalies),
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
                    spreadTicks, spreadPpm, markTicks, quoteCoveragePpm, state.lastError(), state.lastCycleTime(), null, liquidity);
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
                    state.lastError(), state.lastCycleTime(), message, null);
        }
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

    private MarketMakerAnomaly anomaly(String severity,
                                       String type,
                                       String strategyId,
                                       ProductLine productLine,
                                       String instrumentId,
                                       long accountId,
                                       long metricValue,
                                       long threshold,
                                       String summary) {
        return MakerMetricsSupport.anomaly(severity, type, strategyId, productLine, instrumentId, accountId, metricValue, threshold, summary);
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
                    createdAt));
        } catch (RuntimeException ex) {
            log.warn("Failed to record market-maker run event strategyId={} instrumentId={} eventType={} error={}",
                    strategy.getStrategyId(), instrumentId, eventType, ex.getMessage());
        }
    }

    private boolean runStrategy(MarketMakerProperties.Strategy strategy, String requestedSymbol, boolean tradeAfterQuote) {
        StrategyRuntimeState state = state(strategy);
        boolean completed = false;
        long cycleSequence = state.nextCycleSequence();
        for (String configuredSymbol : strategy.getInstrumentIds()) {
            String instrumentId = normalizeSymbol(configuredSymbol);
            if (requestedSymbol != null && !instrumentId.equalsIgnoreCase(requestedSymbol)) {
                continue;
            }
            completed |= runStrategySymbol(strategy, state, cycleSequence, instrumentId, tradeAfterQuote);
        }
        return completed;
    }

    /**
     * 当合约规格或状态发生变更时，由定时刷新器实时调用以动态调整做市状态。
     * 若合约处于非交易状态（如停牌或交割），立即撤销盘口挂单排空流动性，防止非交易状态残留挂单。
     */
    public void onInstrumentStatusChanged(ProductLine productLine, String instrumentId, boolean isTradable) {
        if (!isTradable) {
            Instant now = Instant.now();
            for (var strategy : properties.getStrategies()) {
                if (strategy.getProductLine() == productLine && strategy.getInstrumentIds().contains(instrumentId)) {
                    StrategyRuntimeState state = state(strategy);
                    for (long accountId : strategy.getAccountIds()) {
                        String prefix = accountPrefix(strategy, instrumentId, accountId);
                        var requests = openOrders(productLine, accountId, instrumentId, now).stream()
                                .filter(MakerQuoteMatchingSupport::isLive).filter(order -> ownsOrder(prefix, order))
                                .map(order -> new CancelOrderRequest(accountId, order.orderId())).toList();
                        if (!requests.isEmpty()) {
                            var result = cancelOrders(productLine, accountId, instrumentId, requests);
                            state.addCanceled(result.completed());
                            recordRunEvent(strategy, instrumentId, accountId, state.cycleSequence(), "INSTRUMENT_SUSPENDED_DRAIN",
                                    0, result.completed(), result.failed().size(), "INSTRUMENT_NOT_TRADING", null, now);
                        }
                    }
                }
            }
        }
    }

    private boolean runStrategySymbol(MarketMakerProperties.Strategy strategy,
                                   StrategyRuntimeState state,
                                   long cycleSequence,
                                   String instrumentId,
                                   boolean tradeAfterQuote) {
        String executionKey = strategyKey(strategy) + ":" + instrumentId;
        AtomicBoolean cycleLock = cycleLocks.computeIfAbsent(executionKey, ignored -> new AtomicBoolean());
        if (!cycleLock.compareAndSet(false, true)) {
            state.addSkipped(1L);
            recordRunEvent(strategy, instrumentId, null, cycleSequence, "SKIPPED",
                    0, 0, 0, "CYCLE_IN_PROGRESS", null, Instant.now());
            return false;
        }
        try {
            Instant now = Instant.now();
            try {
                if (!strategy.isEnabled() || state.paused()) {
                    for (long accountId : strategy.getAccountIds()) {
                        String prefix = accountPrefix(strategy, instrumentId, accountId);
                        var requests = openOrders(strategy.getProductLine(), accountId, instrumentId, now).stream()
                                .filter(MakerQuoteMatchingSupport::isLive).filter(order -> ownsOrder(prefix, order))
                                .map(order -> new CancelOrderRequest(accountId, order.orderId())).toList();
                        var result = cancelOrders(strategy.getProductLine(), accountId, instrumentId, requests);
                        state.addCanceled(result.completed());
                        recordRunEvent(strategy, instrumentId, accountId, cycleSequence, "STRATEGY_DRAINING",
                                0, result.completed(), result.failed().size(), "STRATEGY_DISABLED_OR_PAUSED", null, now);
                    }
                    return false;
                }
                InstrumentResponse instrument = currentInstrument(strategy.getProductLine(), instrumentId);
                requireTradable(instrument, strategy.getProductLine());
                OrderBookSnapshotResponse orderBook = fetchOrderBook(strategy.getProductLine(), instrumentId,
                        properties.getQuoting().getOrderBookDepth());
                MarkPriceResponse markPrice = latestMarkPrice(strategy.getProductLine(), instrumentId, instrument);
                ReferenceOrderBookSnapshot referenceOrderBook = referenceMarketProvider.snapshot(instrumentId,
                        strategy.getProductLine(), instrument);
                long volatilityTicks = observeVolatility(strategy, instrumentId, instrument, orderBook, markPrice);
                var inputs = fetchQuoteInputs(strategy, instrumentId, instrument);
                validateQuoteInputs(inputs, strategy, instrumentId, instrument.changeId());
                long rejectedQuotes = 0;
                String quoteRejection = null;
                for (var input : inputs.accounts()) {
                    long accountId = input.userId();
                    ReconcileResult result = quoteAccount(strategy, state, cycleSequence, instrumentId, instrument,
                            orderBook, markPrice, referenceOrderBook, volatilityTicks, accountId,
                            input.signedInventorySteps(), input.makerFeeRatePpm(), now);
                    rejectedQuotes += result.rejected();
                    if (result.rejected() > 0 && quoteRejection == null)
                        quoteRejection = result.rejectionReason();
                }
                // A completed RPC is not a successful quote cycle when Core rejected replenishment.
                // Retain successful orders and let the next normal cycle retry under the same risk checks.
                if (rejectedQuotes > 0) {
                    String reason = "Quote replenishment rejected: " + rejectedQuotes + " orders; " + quoteRejection;
                    state.markFailure(reason, now);
                    reportQuoteFailure(strategy, instrumentId, quoteRejection);
                    recordRunEvent(strategy, instrumentId, null, cycleSequence, "CYCLE_FAILED",
                            0, 0, rejectedQuotes, null, reason, now);
                    return false;
                }
                if (properties.getReferenceMarket().isEnabled() && referenceOrderBook == null) {
                    // Existing reconciliation above still drains unsafe owned quotes. An empty
                    // ladder without its required reference is not a successful quoting cycle.
                    String reason = "REFERENCE_MARKET_UNAVAILABLE";
                    state.markFailure(reason, now);
                    reportQuoteFailure(strategy, instrumentId, reason);
                    recordRunEvent(strategy, instrumentId, null, cycleSequence, "CYCLE_FAILED",
                            0, 0, 0, reason, reason, now);
                    return false;
                }
                if (tradeAfterQuote) tradeSymbol(strategy, instrumentId);
                state.markSuccess(now);
                recordRunEvent(strategy, instrumentId, null, cycleSequence, "CYCLE_SUCCESS",
                        0, 0, 0, null, null, now);
                return true;
            } catch (PartialAmendException ex) {
                log.debug("Market-maker cycle partial amend strategyId={} instrumentId={} error={}",
                        strategy.getStrategyId(), instrumentId, ex.getMessage());
                state.markFailure(ex.getMessage(), now);
                recordRunEvent(strategy, instrumentId, null, cycleSequence, "CYCLE_FAILED",
                        0, 0, 0, null, ex.getMessage(), now);
                return false;
            } catch (RuntimeException ex) {
                log.warn("Market-maker cycle failed strategyId={} instrumentId={} error={}",
                        strategy.getStrategyId(), instrumentId, ex.getMessage());
                state.markFailure(ex.getMessage(), now);
                recordRunEvent(strategy, instrumentId, null, cycleSequence, "CYCLE_FAILED",
                        0, 0, 0, null, ex.getMessage(), now);
                return false;
            }
        } finally {
            cycleLock.set(false);
        }
    }

    /** Emit only public strategy/instrument identity and a static rejection code, never account/order data. */
    private void reportQuoteFailure(MarketMakerProperties.Strategy strategy, String instrumentId, String reason) {
        long now = System.nanoTime();
        long next = nextQuoteFailureLogNanos.get();
        if ((next != 0 && now < next)
                || !nextQuoteFailureLogNanos.compareAndSet(next, now + 5_000_000_000L)) return;
        String code = reason != null && reason.length() <= 96 && reason.matches("[A-Z][A-Z0-9_]*")
                ? reason : "QUOTE_REPLENISHMENT_REJECTED";
        log.warn("maker.quote.unavailable product={} strategy={} instrumentId={} code={}",
                strategy.getProductLine(), strategy.getStrategyId(), instrumentId, code);
    }

    private static void validateQuoteInputs(MakerQuoteInputs.Response inputs,
            MarketMakerProperties.Strategy strategy, String instrumentId, long changeId) {
        if (inputs == null || inputs.productLine() != strategy.getProductLine()
                || !instrumentId.equals(inputs.instrumentId()) || inputs.instrumentChangeId() != changeId
                || inputs.accounts().size() != strategy.getAccountIds().size())
            throw new IllegalStateException("做市批量账户数据与产品线、合约版本或账户数量不匹配");
        for (int i = 0; i < inputs.accounts().size(); i++)
            if (inputs.accounts().get(i).userId() != strategy.getAccountIds().get(i))
                throw new IllegalStateException("做市批量账户数据的账户顺序不匹配");
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
                              long inventorySteps,
                              long makerFeeRatePpm,
                              Instant now) {
        List<OrderResponse> openOrders = openOrders(strategy.getProductLine(), accountId, instrumentId, now);
        OrderBookSnapshotResponse otherLiquidity = MakerQuoteMatchingSupport.excludeOwnQuotes(orderBook, openOrders);
        if (properties.getReferenceMarket().isEnabled() && referenceOrderBook == null) {
            // Keep the resting ladder. An empty plan would cancel every owned quote and blank the book.
            recordRunEvent(strategy, instrumentId, accountId, cycleSequence, "QUOTE_HELD",
                    0, 0, 0, "REFERENCE_MARKET_UNAVAILABLE", null, now);
            return new ReconcileResult(0, 0, 0, null);
        }
        QuotePlan plan = quotePlanner.plan(strategy, properties.getQuoting(), properties.getRisk(), instrument,
                        otherLiquidity, markPrice, inventorySteps, volatilityTicks, referenceOrderBook,
                        makerFeeRatePpm);
        ReconcileResult result = reconcile(strategy, accountId, instrumentId, plan, openOrders, cycleSequence, now);
        state.addCanceled(result.canceled());
        state.addSubmitted(result.submitted());
        state.addRejected(result.rejected());
        recordRunEvent(strategy, instrumentId, accountId, cycleSequence, "QUOTE_RECONCILED",
                result.submitted(), result.canceled(), result.rejected(), null, result.rejectionReason(), now);
        return result;
    }

    private ReconcileResult reconcile(MarketMakerProperties.Strategy strategy,
                                      long accountId,
                                      String instrumentId,
                                      QuotePlan plan,
                                      List<OrderResponse> openOrders,
                                      long cycleSequence,
                                      Instant now) {
        String accountPrefix = accountPrefix(strategy, instrumentId, accountId);
        var quoting = properties.getQuoting();
        List<OrderResponse> owned = openOrders.stream()
                .filter(order -> ownsOrder(accountPrefix, order))
                // CANCEL_REQUESTED 已不再是可交易订单，不能继续占用目标报价档位。
                // 会导致所有报价都被跳过，做市策略进入“运行但无盘口”的假健康状态。
                .filter(MakerQuoteMatchingSupport::isLive)
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
        List<OrderResponse> drifted = owned.stream()
                .filter(order -> !shouldKeep(order, plan.quotes(), accountPrefix, quoting))
                .toList();
        List<OrderResponse> crossing = new ArrayList<>();
        List<OrderResponse> nonCrossing = new ArrayList<>();
        for (OrderResponse order : drifted) {
            boolean wouldCross = order.side() == OrderSide.SELL && order.priceTicks() <= highestBid
                    || order.side() == OrderSide.BUY && order.priceTicks() >= lowestAsk;
            if (wouldCross) crossing.add(order);
            else nonCrossing.add(order);
        }
        int maxUpdates = quoting.getMaxQuoteUpdatesPerCycle();
        if (maxUpdates > 0 && nonCrossing.size() > maxUpdates) {
            java.util.Collections.shuffle(nonCrossing, ThreadLocalRandom.current());
            nonCrossing = nonCrossing.subList(0, maxUpdates);
        }
        List<OrderResponse> toUpdate = new ArrayList<>(crossing.size() + nonCrossing.size());
        toUpdate.addAll(crossing);
        toUpdate.addAll(nonCrossing);
        List<CancelOrderRequest> cancelRequests = toUpdate.stream()
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
        var keptById = new HashMap<Long, OrderResponse>(kept.size() * 2);
        for (OrderResponse order : kept) {
            keptById.put(order.orderId(), order);
        }
        // If old oversized quotes occupy the budget, replace one small batch to release it.
        // The existing rejection check below stops us from withdrawing the rest of the ladder.
        for (int start = 0; start < Math.max(1, cancelRequests.size()); start += replacementBatchSize) {
            List<CancelOrderRequest> batch = cancelRequests.subList(start,
                    Math.min(start + replacementBatchSize, cancelRequests.size()));
            List<CancelOrderRequest> cancellations = new ArrayList<>();
            Map<Long, DesiredQuote> amendments = new java.util.LinkedHashMap<>();
            for (CancelOrderRequest request : batch) {
                OrderResponse old = keptById.get(request.orderId());
                DesiredQuote best = quoteReplacement(strategy, plan, old, accountPrefix, highestBid, lowestAsk, quoting);
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
            updated.stream().filter(MakerQuoteMatchingSupport::isLive).forEach(kept::add);
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

    private DesiredQuote quoteReplacement(MarketMakerProperties.Strategy strategy, QuotePlan plan,
            OrderResponse old, String prefix, long bid, long ask, MarketMakerProperties.Quoting quoting) {
        if (old == null) return null;
        int level = MakerQuoteMatchingSupport.quoteLevel(old.clientOrderId(), prefix, old.side());
        if (level < 0) return null;
        boolean linearLiquidity = quoting.getLinearLiquidityTargetNotionalUnits() != 0
                && (strategy.getProductLine() == ProductLine.LINEAR_PERPETUAL
                || strategy.getProductLine() == ProductLine.LINEAR_DELIVERY);
        if (!linearLiquidity && !quoting.isPreferInPlaceAmend()) return null;
        long best = old.side() == OrderSide.BUY ? bid : ask;
        long tolerance = linearLiquidity ? MakerQuoteMatchingSupport.ppmFloor(best, quoting.getLiquiditySlippagePpm()) : 0L;
        for (var quote : plan.quotes()) {
            if (quote.side() != old.side() || quote.level() != level) continue;
            if (!linearLiquidity || Math.abs(quote.priceTicks() - best) <= tolerance) return quote;
        }
        return null;
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
                var receipt = submitAmendBatch(strategy.getProductLine(), new BatchAmendOrdersRequest(batch));
                var result = receiptResult(receipt, AmendOrderBatchResponse.class);
                if (result == null) {
                    throw new IllegalStateException("quote amendments not confirmed: " + receiptMessage(receipt));
                }
                if (result.failed() != 0 || result.completed() != batch.size()
                        || result.results().size() != batch.size()) {
                    throw new PartialAmendException("quote amendments not confirmed: " + receiptMessage(receipt));
                }
                boolean[] confirmed = new boolean[batch.size()];
                List<OrderResponse> replacements = new ArrayList<>(batch.size());
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
                    replacements.add(replacement);
                }
                updateCachedOrders(strategy.getProductLine(), accountId, instrumentId,
                        batch.stream().map(AmendOrderRequest::orderId).toList(), replacements);
                updated.addAll(replacements);
            }
            return updated;
        } catch (PartialAmendException ex) {
            openOrderSnapshots.remove(orderSnapshotKey(strategy.getProductLine(), accountId, instrumentId));
            throw new PartialAmendException("quote amendment outcome requires an actual order query: " + ex.getMessage(), ex);
        } catch (RuntimeException ex) {
            openOrderSnapshots.remove(orderSnapshotKey(strategy.getProductLine(), accountId, instrumentId));
            throw new IllegalStateException("quote amendment outcome requires an actual order query: " + ex.getMessage(), ex);
        }
    }

    private static final class PartialAmendException extends IllegalStateException {
        PartialAmendException(String message) { super(message); }
        PartialAmendException(String message, Throwable cause) { super(message, cause); }
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
        long plannedBids = 0L, plannedAsks = 0L, restingBids = 0L, restingAsks = 0L;
        for (DesiredQuote quote : plan.quotes()) {
            if (quote.side() == OrderSide.BUY) plannedBids = Math.addExact(plannedBids, quote.quantitySteps());
            else if (quote.side() == OrderSide.SELL) plannedAsks = Math.addExact(plannedAsks, quote.quantitySteps());
        }
        for (OrderResponse order : kept) {
            if (order == null) continue;
            if (order.side() == OrderSide.BUY) restingBids = Math.addExact(restingBids, order.remainingQuantitySteps());
            else if (order.side() == OrderSide.SELL) restingAsks = Math.addExact(restingAsks, order.remainingQuantitySteps());
        }
        long bidCapacity = Math.subtractExact(plannedBids, restingBids);
        long askCapacity = Math.subtractExact(plannedAsks, restingAsks);
        for (DesiredQuote quote : plan.quotes()) {
            if (kept.size() >= maxOpenOrders) {
                break;
            }
            // Waiting opposite quotes must be canceled before submitting a crossing post-only replacement.
            if (occupiedOrCrossingQuote(kept, quote, accountPrefix)) {
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
                    OrderCommandReceipt receipt = submitPlaceBatch(strategy.getProductLine(), new BatchPlaceOrderRequest(batchRequests));
                    OrderBatchResponse batch = receiptResult(receipt, OrderBatchResponse.class);
                    if (batch == null) {
                        throw new IllegalStateException(receiptMessage(receipt));
                    }
                    List<OrderResponse> confirmedOrders = new ArrayList<>(batchRequests.size());
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
                        confirmedOrders.add(response);
                        submitted++;
                        // 占位元素只用于限制本周期的最大报价数，成功后替换为真实订单。
                        int placeholder = kept.indexOf(null);
                        if (placeholder >= 0) {
                            kept.set(placeholder, response);
                        } else {
                            kept.add(response);
                        }
                    }
                    updateCachedOrders(strategy.getProductLine(), accountId, instrumentId, List.of(), confirmedOrders);
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

    private String firstReason(String current, String candidate) {
        return MakerQuoteMatchingSupport.firstReason(current, candidate);
    }

    private boolean shouldKeep(OrderResponse order,
                               List<DesiredQuote> desiredQuotes,
                               String accountPrefix,
                               MarketMakerProperties.Quoting quoting) {
        return MakerQuoteMatchingSupport.shouldKeep(order, desiredQuotes, accountPrefix, quoting);
    }

    private boolean hasLiveQuote(List<OrderResponse> orders, DesiredQuote quote, String accountPrefix, MarketMakerProperties.Quoting quoting) {
        return MakerQuoteMatchingSupport.hasLiveQuote(orders, quote, accountPrefix, quoting);
    }

    private boolean occupiedOrCrossingQuote(List<OrderResponse> orders, DesiredQuote quote, String accountPrefix) {
        return MakerQuoteMatchingSupport.occupiedOrCrossingQuote(orders, quote, accountPrefix);
    }

    private boolean matchesQuote(OrderResponse order, DesiredQuote quote, String accountPrefix, MarketMakerProperties.Quoting quoting) {
        return MakerQuoteMatchingSupport.matchesQuote(order, quote, accountPrefix, quoting);
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
                            Instant now) {
        MarketMakerProperties.Trade trade = properties.getTrade();
        if (!trade.isEnabled()) {
            return false;
        }
        long accountId = activeTradeAccount(strategy, cycleSequence);
        if (accountId <= 0) {
            return false;
        }

        List<ExternalTrade> prints = referenceMarketProvider.drainTrades(instrumentId, strategy.getProductLine(),
                trade.getOrdersPerBatch() * 2);
        if (prints.isEmpty()) return false;
        OrderBookSnapshotResponse orderBook = fetchOrderBook(strategy.getProductLine(), instrumentId,
                properties.getQuoting().getOrderBookDepth());
        PositionResponse position = currentPosition(strategy, accountId, instrumentId, instrument);
        List<PlaceOrderRequest> requests = externalPrintOrders(strategy, instrumentId, accountId, cycleSequence,
                instrument, orderBook, position.signedQuantitySteps(), prints);
        if (requests.isEmpty()) {
            log.debug("Market-maker simulated trade skipped empty requests strategyId={} instrumentId={} accountId={} asks={} bids={} pos={}",
                    strategy.getStrategyId(), instrumentId, accountId,
                    orderBook == null || orderBook.asks() == null ? -1 : orderBook.asks().size(),
                    orderBook == null || orderBook.bids() == null ? -1 : orderBook.bids().size(),
                    position.signedQuantitySteps());
            return false;
        }
        if (requests.size() > 1) return tradeBatch(strategy, instrumentId, accountId, cycleSequence,
                requests, state, now);
        PlaceOrderRequest request = requests.getFirst();
        OrderCommandReceipt receipt = submitPlace(strategy.getProductLine(), request);
        OrderResponse response = receiptResult(receipt, OrderResponse.class);
        if (response == null || response.status() == OrderStatus.REJECTED) {
            state.addRejected(1L);
            String reason = response == null ? receiptMessage(receipt) : response.rejectReason();
            log.debug("Market-maker simulated trade rejected strategyId={} instrumentId={} accountId={} reason={}",
                    strategy.getStrategyId(), instrumentId, accountId, reason);
            recordRunEvent(strategy, instrumentId, accountId, cycleSequence, "TRADE_REJECTED",
                    0, 0, 1, null, reason, now);
            return false;
        }
        state.addSubmitted(1L);
        if (response.executedQuantitySteps() <= 0) {
            log.debug("Market-maker simulated trade no fill strategyId={} instrumentId={} accountId={} status={}",
                    strategy.getStrategyId(), instrumentId, accountId, response.status());
            recordRunEvent(strategy, instrumentId, accountId, cycleSequence, "TRADE_NO_FILL",
                    1, 0, 0, "NO_EXECUTION", null, now);
            return false;
        }
        recordRunEvent(strategy, instrumentId, accountId, cycleSequence, "TRADE_EXECUTED",
                1, 0, 0, null, null, now);
        return true;
    }

    private List<PlaceOrderRequest> externalPrintOrders(MarketMakerProperties.Strategy strategy, String instrumentId,
                                                         long accountId, long cycleSequence, InstrumentResponse instrument,
                                                         OrderBookSnapshotResponse book, long position,
                                                         List<ExternalTrade> prints) {
        return MakerSimulatedTradeSupport.externalPrintOrders(properties.getTrade(), strategy, instrumentId,
                accountId, instrument, book, position, prints,
                () -> takerClientOrderId(strategy, instrumentId, accountId, cycleSequence));
    }

    /** Build local simulated orders; each side reserves its own worst-case fill budget for this batch. */
    List<PlaceOrderRequest> simulatedOrders(MarketMakerProperties.Strategy strategy, String instrumentId,
                                           long accountId, long cycleSequence, InstrumentResponse instrument,
                                           OrderBookSnapshotResponse book, MarkPriceResponse mark,
                                           long position, RandomGenerator random) {
        return MakerSimulatedTradeSupport.simulatedOrders(properties.getTrade(), strategy, instrumentId,
                accountId, instrument, book, mark, position, random,
                () -> takerClientOrderId(strategy, instrumentId, accountId, cycleSequence));
    }

    /** Submit ordinary simulated-user orders together; the core validates and settles every order. */
    private boolean tradeBatch(MarketMakerProperties.Strategy strategy, String instrumentId, long accountId,
                               long cycleSequence, List<PlaceOrderRequest> requests,
                               StrategyRuntimeState state, Instant now) {
        int count = requests.size();
        OrderCommandReceipt receipt = submitPlaceBatch(strategy.getProductLine(), new BatchPlaceOrderRequest(requests));
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
                executed > 0 || missingOrderDetails > 0 ? null : "NO_EXECUTION", rejectionReason, now);
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

    private long observeVolatility(MarketMakerProperties.Strategy strategy,
                                   String instrumentId,
                                   InstrumentResponse instrument,
                                   OrderBookSnapshotResponse orderBook,
                                   MarkPriceResponse markPrice) {
        long anchor = MakerSimulatedTradeSupport.markPriceTicks(instrument, markPrice);
        if (anchor <= 0) {
            anchor = MakerSimulatedTradeSupport.midPriceTicks(orderBook);
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
                OrderCommandReceipt receipt = submitCancelBatch(productLine, new BatchCancelOrdersRequest(batchRequests));
                OrderBatchResponse response = receiptResult(receipt, OrderBatchResponse.class);
                if (response == null) {
                    log.warn("做市批量撤单未返回终态结果 accountId={} instrumentId={} receipt={}", accountId, instrumentId,
                            receiptMessage(receipt));
                    failed.addAll(batchRequests);
                    continue;
                }
                List<Long> confirmedIds = new ArrayList<>(batchRequests.size());
                for (int i = 0; i < batchRequests.size(); i++) {
                    int requestIndex = i;
                    boolean success = response.results().stream()
                            .anyMatch(item -> item != null && item.index() == requestIndex && item.success());
                    CancelOrderRequest request = batchRequests.get(i);
                    if (success) {
                        canceled++;
                        confirmedIds.add(request.orderId());
                    } else {
                        failed.add(request);
                    }
                }
                updateCachedOrders(productLine, accountId, instrumentId, confirmedIds, List.of());
            } catch (RuntimeException ex) {
                log.warn("做市批量撤单状态不确定 accountId={} instrumentId={} error={}", accountId, instrumentId, ex.getMessage());
                failed.addAll(batchRequests);
            }
        }
        return new CancelResult(canceled, List.copyOf(failed));
    }

    private <T> T receiptResult(OrderCommandReceipt receipt, Class<T> type) {
        return MakerQuoteMatchingSupport.receiptResult(receipt, type);
    }

    private String receiptMessage(OrderCommandReceipt receipt) {
        return MakerQuoteMatchingSupport.receiptMessage(receipt);
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
        List<OrderResponse> orders = aeronGateway.openOrders(productLine, accountId, instrumentId,
                properties.getQuoting().getMaxOpenOrdersPerAccountSymbol());
        openOrderSnapshots.put(key, new CachedOpenOrders(orders, now));
        return orders;
    }

    /** Update the derived order snapshot once per confirmed batch; uncertain batches invalidate it. */
    private void updateCachedOrders(ProductLine productLine, long accountId, String instrumentId,
            List<Long> removedIds, List<OrderResponse> replacements) {
        if (removedIds.isEmpty() && replacements.isEmpty()) return;
        String key = orderSnapshotKey(productLine, accountId, instrumentId);
        openOrderSnapshots.computeIfPresent(key, (ignored, cached) -> {
            var replacedIds = new java.util.HashSet<>(removedIds);
            for (var order : replacements) replacedIds.add(order.orderId());
            List<OrderResponse> orders = new ArrayList<>(cached.orders().size() + replacements.size());
            for (var order : cached.orders())
                if (!replacedIds.contains(order.orderId())) orders.add(order);
            orders.addAll(replacements);
            return new CachedOpenOrders(List.copyOf(orders), cached.refreshedAt());
        });
    }

    private String orderSnapshotKey(ProductLine productLine, long accountId, String instrumentId) {
        return productLine.name() + ":" + accountId + ":" + instrumentId;
    }

    private MarkPriceResponse latestMarkPrice(ProductLine productLine,
                                              String instrumentId,
                                              InstrumentResponse instrument) {
        long instrumentChangeId = instrument.changeId();
        var cached = markPriceCache.fresh(instrumentId);
        if (cached.isPresent()) {
            MarkPriceEvent event = cached.get();
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
        if (properties.getReferenceMarket().isEnabled()) {
            ReferenceOrderBookSnapshot ref = referenceMarketProvider.snapshot(instrumentId, productLine, instrument);
            if (ref != null && ref.midPriceTicks() > 0 && instrument.priceTickUnits() > 0) {
                long midUnits = Math.multiplyExact(ref.midPriceTicks(), instrument.priceTickUnits());
                return new MarkPriceResponse(instrumentId, null, midUnits, null,
                        null, null, null, null, null,
                        java.math.BigDecimal.ZERO, null, 0L, java.math.BigDecimal.ZERO,
                        0L, null, null, 0L, com.surprising.price.api.model.PriceStatus.HEALTHY,
                        ref.receivedAt() != null ? ref.receivedAt() : Instant.now());
            }
        }
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

    /** 现货没有持仓对象，做市库存由资产余额约束；直连 Aeron 查询用户状态。 */
    private PositionResponse currentPosition(MarketMakerProperties.Strategy strategy,
                                             long accountId,
                                             String instrumentId,
                                             InstrumentResponse instrument) {
        var state = aeronGateway.userState(strategy.getProductLine(), accountId);
        if (strategy.getProductLine() == ProductLine.SPOT) {
            if (instrument == null || instrument.baseAsset() == null || instrument.baseAsset().isBlank()) {
                throw new IllegalStateException("现货做市缺少基础资产快照");
            }
            if (instrument.quantityStepUnits() <= 0) {
                throw new IllegalStateException("现货做市数量步长必须为正数");
            }
            long equity = state == null ? 0L : state.balances().stream()
                    .filter(b -> instrument.baseAsset().equals(b.asset()))
                    .mapToLong(b -> Math.addExact(b.availableUnits(), b.lockedUnits()))
                    .findFirst().orElse(0L);
            long inventory = Math.max(0L, equity) / instrument.quantityStepUnits();
            return new PositionResponse(accountId, instrumentId,
                    strategy.getMarginMode(), PositionSide.NET, inventory, 0L, 0L, Instant.now());
        }
        long signed = state == null ? 0L : state.positions().stream()
                .filter(p -> instrumentId.equals(p.instrumentId())
                        && p.marginMode().name().equals(strategy.getMarginMode().name())
                        && p.positionSide().name().equals(PositionSide.NET.name()))
                .mapToLong(com.surprising.aeron.protocol.CorePositionView::signedQuantitySteps)
                .findFirst().orElse(0L);
        return new PositionResponse(accountId, instrumentId,
                strategy.getMarginMode(), PositionSide.NET, signed, 0L, 0L, Instant.now());
    }

    private OrderBookSnapshotResponse fetchOrderBook(ProductLine productLine, String instrumentId, int depth) {
        return aeronGateway.orderBook(productLine, instrumentId, depth);
    }

    private MakerQuoteInputs.Response fetchQuoteInputs(MarketMakerProperties.Strategy strategy,
                                                       String instrumentId,
                                                       InstrumentResponse instrument) {
        ProductLine productLine = strategy.getProductLine();
        List<MakerQuoteInputs.Account> accountInputs = new ArrayList<>(strategy.getAccountIds().size());
        for (long accountId : strategy.getAccountIds()) {
            var userState = aeronGateway.userState(productLine, accountId);
            long signedInventorySteps;
            if (productLine == ProductLine.SPOT) {
                long equity = userState == null ? 0L : userState.balances().stream()
                        .filter(b -> instrument.baseAsset().equals(b.asset()))
                        .mapToLong(b -> Math.addExact(b.availableUnits(), b.lockedUnits()))
                        .findFirst().orElse(0L);
                signedInventorySteps = Math.max(0L, equity) / Math.max(1L, instrument.quantityStepUnits());
            } else {
                signedInventorySteps = userState == null ? 0L : userState.positions().stream()
                        .filter(p -> instrumentId.equals(p.instrumentId())
                                && p.marginMode().name().equals(strategy.getMarginMode().name())
                                && p.positionSide().name().equals(PositionSide.NET.name()))
                        .mapToLong(com.surprising.aeron.protocol.CorePositionView::signedQuantitySteps)
                        .findFirst().orElse(0L);
            }
            accountInputs.add(new MakerQuoteInputs.Account(accountId, signedInventorySteps, 0L));
        }
        return new MakerQuoteInputs.Response(productLine, instrumentId, instrument.changeId(), accountInputs);
    }

    private OrderCommandReceipt submitPlace(ProductLine productLine, PlaceOrderRequest request) {
        return aeronGateway.place(productLine, request);
    }

    private OrderCommandReceipt submitPlaceBatch(ProductLine productLine, BatchPlaceOrderRequest request) {
        return aeronGateway.placeBatch(productLine, request);
    }

    private OrderCommandReceipt submitAmendBatch(ProductLine productLine, BatchAmendOrdersRequest request) {
        return aeronGateway.amendBatch(productLine, request);
    }

    private OrderCommandReceipt submitCancelBatch(ProductLine productLine, BatchCancelOrdersRequest request) {
        return aeronGateway.cancelBatch(productLine, request);
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

    /** Strategies start from YAML; administrator changes live only in this process. */
    public List<MarketMakerProperties.Strategy> configuredStrategies() {
        return overrideStore.definitions().stream().map(com.surprising.marketmaker.provider.model.MarketMakerStrategyDefinition::strategy).toList();
    }

    public List<com.surprising.marketmaker.provider.model.MarketMakerStrategyDefinition> strategyDefinitions(ProductLine line) {
        if (line == null) throw new IllegalArgumentException("productLine is required");
        return overrideStore.definitions().stream().filter(d -> d.productLine() == line).toList();
    }

    public com.surprising.marketmaker.provider.model.MarketMakerStrategyDefinition saveStrategyDefinition(
            com.surprising.marketmaker.provider.model.MarketMakerStrategyDefinition definition) {
        if (definition.productLine() == null) throw new IllegalArgumentException("productLine is required");
        for (String id : definition.instrumentIds()) {
            var instrument = instrumentSnapshotCache.current(definition.productLine(), Integer.parseInt(id))
                    .orElseThrow(() -> new IllegalArgumentException("instrument is not initialized: " + id));
            if (definition.enabled() && instrument.status() != com.surprising.instrument.api.model.InstrumentStatus.TRADING)
                throw new IllegalArgumentException("enable contract trading before enabling its maker");
            if (definition.baseQuantitySteps() < instrument.minQuantitySteps()
                    || definition.baseQuantitySteps() > instrument.maxQuantitySteps())
                throw new IllegalArgumentException("maker base quantity is outside the contract order limits");
        }
        var old = overrideStore.definitions().stream().filter(d -> d.productLine() == definition.productLine()
                && d.strategyId().equals(definition.strategyId())).findFirst();
        if (old.isPresent() && (!old.get().accountIds().equals(definition.accountIds())
                || !old.get().instrumentIds().equals(definition.instrumentIds())))
            throw new IllegalArgumentException("existing strategy account and instrument bindings cannot change; create a new strategy");
        var saved = overrideStore.saveDefinition(definition);
        return saved;
    }

    private List<MarketMakerProperties.Strategy> strategiesSnapshot() {
        return strategiesSnapshot(null);
    }

    private List<MarketMakerProperties.Strategy> strategiesSnapshot(ProductLine productLine) {
        return configuredStrategies().stream()
                .filter(strategy -> productLine == null || strategy.getProductLine() == productLine)
                .map(strategy -> applyOverride(strategy, overrideStore.find(strategy.getProductLine(), strategy.getStrategyId()).orElse(null)))
                .toList();
    }

    private MarketMakerProperties.Strategy applyOverride(MarketMakerProperties.Strategy configured,
                                                         StrategyConfigOverride override) {
        MarketMakerProperties.Strategy effective = new MarketMakerProperties.Strategy();
        effective.setStrategyId(configured.getStrategyId());
        effective.setProductLine(configured.getProductLine());
        effective.setEnabled(override != null && override.enabled() != null ? override.enabled() : configured.isEnabled());
        effective.setAccountIds(new ArrayList<>(configured.getAccountIds()));
        effective.setInstrumentIds(new ArrayList<>(configured.getInstrumentIds()));
        // 复制只读配置中的启动锚定价，避免内存覆盖对象重建策略时丢失现货初始化参数。
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
        return configuredStrategies().stream()
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
                state.skippedCycles(), state.lastError(), state.lastCycleTime());
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
        return MarketMakerParameterSupport.normalizeRequired(value, field);
    }

    private String normalizeOptional(String value) {
        return MarketMakerParameterSupport.normalizeOptional(value);
    }

    private String normalizeSymbol(String value) {
        return MarketMakerParameterSupport.normalizeSymbol(value);
    }

    private String normalizeReason(String value) {
        return MarketMakerParameterSupport.normalizeReason(value);
    }

    private MarginMode parseMarginMode(String value) {
        return MarketMakerParameterSupport.parseMarginMode(value);
    }

    private Long positiveOrNull(Long value, String field) {
        return MarketMakerParameterSupport.positiveOrNull(value, field);
    }

    private Long nonNegativeOrNull(Long value, String field) {
        return MarketMakerParameterSupport.nonNegativeOrNull(value, field);
    }

    private Long boundedLongOrNull(Long value, long min, long max, String field) {
        return MarketMakerParameterSupport.boundedLongOrNull(value, min, max, field);
    }

    private Integer boundedIntOrNull(Integer value, int min, int max, String field) {
        return MarketMakerParameterSupport.boundedIntOrNull(value, min, max, field);
    }

    private String strategyKey(MarketMakerProperties.Strategy strategy) {
        return MarketMakerParameterSupport.strategyKey(strategy);
    }

    private String strategyKey(ProductLine productLine, String strategyId) {
        return MarketMakerParameterSupport.strategyKey(productLine, strategyId);
    }

    private String resolveNodeId(String configuredNodeId) {
        return MarketMakerParameterSupport.resolveNodeId(configuredNodeId);
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
        private final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();
        private long lastPriceTicks;
        private volatile long volatilityTicks;

        private long observe(long priceTicks) {
            lock.lock();
            try {
                if (lastPriceTicks > 0) {
                    long delta = priceTicks >= lastPriceTicks
                            ? priceTicks - lastPriceTicks : lastPriceTicks - priceTicks;
                    volatilityTicks += (delta - volatilityTicks) / 4L;
                }
                lastPriceTicks = priceTicks;
                return volatilityTicks;
            } finally {
                lock.unlock();
            }
        }

        private long volatilityTicks() {
            return volatilityTicks;
        }
    }

}
