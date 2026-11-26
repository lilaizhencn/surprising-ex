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

    private final MarketMakerProperties properties;
    private final InstrumentSnapshotCache instrumentSnapshotCache;
    private final QuotePlanner quotePlanner;
    private final ReferenceMarketProvider referenceMarketProvider;
    private final MarketMakerStrategyOverrideStore overrideStore;
    private final MarketMakerRunEventRepository runEventRepository;
    private final MarketMakerMarketFeed marketFeed;
    private final MarketMakerQuoteReconciler quoteReconciler;
    private final MarketMakerTradeExecutor tradeExecutor;
    private final MarketMakerMetricsAssembler metricsAssembler;
    private final Map<String, StrategyRuntimeState> states = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> cycleLocks = new ConcurrentHashMap<>();
    private final String nodeId;
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
        this.properties = properties;
        this.instrumentSnapshotCache = instrumentSnapshotCache;
        this.quotePlanner = quotePlanner;
        this.referenceMarketProvider = referenceMarketProvider;
        this.overrideStore = overrideStore;
        this.runEventRepository = runEventRepository;
        this.nodeId = resolveNodeId(properties.getEngine().getNodeId());
        String orderNonce = Long.toUnsignedString(System.currentTimeMillis(), 36)
                + "-" + UUID.randomUUID().toString().substring(0, 8);
        this.marketFeed = new MarketMakerMarketFeed(properties, instrumentSnapshotCache, markPriceCache,
                aeronGateway, referenceMarketProvider);
        this.quoteReconciler = new MarketMakerQuoteReconciler(this, properties, aeronGateway, marketFeed, orderNonce);
        this.tradeExecutor = new MarketMakerTradeExecutor(this, properties, aeronGateway, referenceMarketProvider, marketFeed);
        this.metricsAssembler = new MarketMakerMetricsAssembler(this, properties, quotePlanner,
                referenceMarketProvider, marketFeed, tradeExecutor, nodeId);
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
            InstrumentResponse instrument = marketFeed.currentInstrument(strategy.getProductLine(), instrumentId);
            requireTradable(instrument, strategy.getProductLine());
            MarkPriceResponse mark = marketFeed.latestMarkPrice(strategy.getProductLine(), instrumentId, instrument);
            StrategyRuntimeState state = state(strategy);
            return tradeExecutor.maybeTrade(strategy, state, state.cycleSequence(), instrumentId, instrument, mark, Instant.now());
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
        return metricsAssembler.adminMetrics(limit, productLine);
    }

    public MarketMakerRunLogQueryResponse runLogs(ProductLine productLine, String strategyId, String instrumentId,
                                                  Long accountId, String eventType, MarketMakerRunEventPageQuery query) {
        CursorPage<MarketMakerRunEventRecord> page = runEventRepository.findPage(
                productLine, strategyId, instrumentId, accountId, eventType, query);
        return new MarketMakerRunLogQueryResponse(Instant.now(), page.items(), page.nextCursor(),
                page.hasMore(), page.sort(), page.limit());
    }

    void recordRunEvent(MarketMakerProperties.Strategy strategy,
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
                        var requests = marketFeed.openOrders(productLine, accountId, instrumentId, now).stream()
                                .filter(MakerQuoteMatchingSupport::isLive).filter(order -> ownsOrder(prefix, order))
                                .map(order -> new CancelOrderRequest(accountId, order.orderId())).toList();
                        if (!requests.isEmpty()) {
                            var result = quoteReconciler.cancelOrders(productLine, accountId, instrumentId, requests);
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
                        var requests = marketFeed.openOrders(strategy.getProductLine(), accountId, instrumentId, now).stream()
                                .filter(MakerQuoteMatchingSupport::isLive).filter(order -> ownsOrder(prefix, order))
                                .map(order -> new CancelOrderRequest(accountId, order.orderId())).toList();
                        var result = quoteReconciler.cancelOrders(strategy.getProductLine(), accountId, instrumentId, requests);
                        state.addCanceled(result.completed());
                        recordRunEvent(strategy, instrumentId, accountId, cycleSequence, "STRATEGY_DRAINING",
                                0, result.completed(), result.failed().size(), "STRATEGY_DISABLED_OR_PAUSED", null, now);
                    }
                    return false;
                }
                InstrumentResponse instrument = marketFeed.currentInstrument(strategy.getProductLine(), instrumentId);
                requireTradable(instrument, strategy.getProductLine());
                OrderBookSnapshotResponse orderBook = marketFeed.fetchOrderBook(strategy.getProductLine(), instrumentId,
                        properties.getQuoting().getOrderBookDepth());
                MarkPriceResponse markPrice = marketFeed.latestMarkPrice(strategy.getProductLine(), instrumentId, instrument);
                ReferenceOrderBookSnapshot referenceOrderBook = referenceMarketProvider.snapshot(instrumentId,
                        strategy.getProductLine(), instrument);
                long volatilityTicks = tradeExecutor.observeVolatility(strategy, instrumentId, instrument, orderBook, markPrice);
                var inputs = marketFeed.fetchQuoteInputs(strategy, instrumentId, instrument);
                validateQuoteInputs(inputs, strategy, instrumentId, instrument.changeId());
                long rejectedQuotes = 0;
                String quoteRejection = null;
                for (var input : inputs.accounts()) {
                    long accountId = input.userId();
                    MarketMakerQuoteReconciler.ReconcileResult result = quoteAccount(strategy, state, cycleSequence,
                            instrumentId, instrument, orderBook, markPrice, referenceOrderBook, volatilityTicks,
                            accountId, input.signedInventorySteps(), input.makerFeeRatePpm(), now);
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
            } catch (MarketMakerQuoteReconciler.PartialAmendException ex) {
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

    private MarketMakerQuoteReconciler.ReconcileResult quoteAccount(MarketMakerProperties.Strategy strategy,
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
        List<OrderResponse> openOrders = marketFeed.openOrders(strategy.getProductLine(), accountId, instrumentId, now);
        OrderBookSnapshotResponse otherLiquidity = MakerQuoteMatchingSupport.excludeOwnQuotes(orderBook, openOrders);
        if (properties.getReferenceMarket().isEnabled() && referenceOrderBook == null) {
            recordRunEvent(strategy, instrumentId, accountId, cycleSequence, "QUOTE_HELD",
                    0, 0, 0, "REFERENCE_MARKET_UNAVAILABLE", null, now);
            return new MarketMakerQuoteReconciler.ReconcileResult(0, 0, 0, null);
        }
        QuotePlan plan = quotePlanner.plan(strategy, properties.getQuoting(), properties.getRisk(), instrument,
                otherLiquidity, markPrice, inventorySteps, volatilityTicks, referenceOrderBook, makerFeeRatePpm);
        MarketMakerQuoteReconciler.ReconcileResult result = quoteReconciler.reconcile(
                strategy, accountId, instrumentId, plan, openOrders, cycleSequence);
        state.addCanceled(result.canceled());
        state.addSubmitted(result.submitted());
        state.addRejected(result.rejected());
        recordRunEvent(strategy, instrumentId, accountId, cycleSequence, "QUOTE_RECONCILED",
                result.submitted(), result.canceled(), result.rejected(), null, result.rejectionReason(), now);
        return result;
    }

    /** Build local simulated orders; each side reserves its own worst-case fill budget for this batch. */
    List<PlaceOrderRequest> simulatedOrders(MarketMakerProperties.Strategy strategy, String instrumentId,
                                            long accountId, long cycleSequence, InstrumentResponse instrument,
                                            OrderBookSnapshotResponse book, MarkPriceResponse mark,
                                            long position, RandomGenerator random) {
        return tradeExecutor.simulatedOrders(strategy, instrumentId, accountId, cycleSequence,
                instrument, book, mark, position, random);
    }

    private void requireTradable(InstrumentResponse instrument, ProductLine productLine) {
        if (instrument == null || instrument.status() != InstrumentStatus.TRADING) {
            throw new IllegalStateException("instrument is not in TRADING status");
        }
        if (instrument.contractType() == null || instrument.contractType().productLine() != productLine) {
            throw new IllegalStateException("instrument product line mismatch");
        }
    }

    boolean isTradableForProduct(InstrumentResponse instrument, ProductLine productLine) {
        return instrument != null
                && instrument.status() == InstrumentStatus.TRADING
                && instrument.contractType() != null
                && instrument.contractType().productLine() == productLine;
    }

    boolean ownsOrder(String accountPrefix, OrderResponse order) {
        return order != null && order.clientOrderId() != null && order.clientOrderId().startsWith(accountPrefix);
    }

    String accountPrefix(MarketMakerProperties.Strategy strategy, String instrumentId, long accountId) {
        return "mm-" + stableToken(strategy.getProductLine().name() + ":" + strategy.getStrategyId() + ":" + instrumentId)
                + "-" + accountId + "-";
    }

    String quotePrefix(String accountPrefix, OrderSide side, int level) {
        return accountPrefix + (side == OrderSide.BUY ? "b" : "s") + level + "-";
    }

    String takerClientOrderId(MarketMakerProperties.Strategy strategy,
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

    List<MarketMakerProperties.Strategy> strategiesSnapshot(ProductLine productLine) {
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

    StrategyRuntimeState state(MarketMakerProperties.Strategy strategy) {
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

    MarketMakerStrategyStatus status(MarketMakerProperties.Strategy strategy, StrategyRuntimeState state) {
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
}
