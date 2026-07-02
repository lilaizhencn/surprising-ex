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
import com.surprising.marketmaker.provider.model.StrategyRuntimeState;
import com.surprising.price.api.client.MarkPriceRpcApi;
import com.surprising.price.api.model.MarkPriceResponse;
import com.surprising.trading.api.TraceContext;
import com.surprising.trading.api.model.CancelOrderRequest;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.zip.CRC32;
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
    private final Map<String, StrategyRuntimeState> states = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastTradeTimes = new ConcurrentHashMap<>();
    private final Map<String, OrderSide> lastTradeSides = new ConcurrentHashMap<>();
    private final String nodeId;
    private final String orderNonce;

    public MarketMakerService(MarketMakerProperties properties,
                              InstrumentRpcApi instrumentRpcApi,
                              MarkPriceRpcApi markPriceRpcApi,
                              MarketDataRpcApi marketDataRpcApi,
                              OrderRpcApi orderRpcApi,
                              AccountRpcApi accountRpcApi,
                              QuotePlanner quotePlanner,
                              MarketMakerLeaseCoordinator leaseCoordinator) {
        this.properties = properties;
        this.instrumentRpcApi = instrumentRpcApi;
        this.markPriceRpcApi = markPriceRpcApi;
        this.marketDataRpcApi = marketDataRpcApi;
        this.orderRpcApi = orderRpcApi;
        this.accountRpcApi = accountRpcApi;
        this.quotePlanner = quotePlanner;
        this.leaseCoordinator = leaseCoordinator;
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
        List<MarketMakerStrategyResponse> responses = properties.getStrategies().stream()
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

    public MarketMakerStrategyQueryResponse runOnce(MarketMakerRunRequest request) {
        String traceId = TraceContext.currentOrCreate();
        String requestedStrategyId = normalizeOptional(request == null ? null : request.strategyId());
        String requestedSymbol = normalizeOptional(request == null ? null : request.symbol());
        try {
            for (MarketMakerProperties.Strategy strategy : properties.getStrategies()) {
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

    private void runStrategy(MarketMakerProperties.Strategy strategy, String requestedSymbol, String traceId) {
        StrategyRuntimeState state = state(strategy);
        if (!strategy.isEnabled() || state.paused()) {
            state.addSkipped(1L);
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
                quoteAccount(strategy, state, cycleSequence, symbol, instrument, orderBook, markPrice, accountId, now);
            }
            maybeTrade(strategy, state, cycleSequence, symbol, instrument, markPrice, now);
            state.markSuccess(traceId, now);
        } catch (RuntimeException ex) {
            log.warn("Market-maker cycle failed strategyId={} symbol={} error={}",
                    strategy.getStrategyId(), symbol, ex.getMessage());
            state.markFailure(traceId, ex.getMessage(), now);
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
                              Instant now) {
        PositionResponse position = accountRpcApi.position(accountId, symbol, strategy.getMarginMode().name(),
                PositionSide.NET.name());
        QuotePlan plan = quotePlanner.plan(strategy, properties.getQuoting(), properties.getRisk(), instrument,
                orderBook, markPrice, position.signedQuantitySteps());
        List<OrderResponse> openOrders = openOrders(accountId, symbol);
        ReconcileResult result = reconcile(strategy, accountId, symbol, plan, openOrders, cycleSequence, now);
        state.addCanceled(result.canceled());
        state.addSubmitted(result.submitted());
        state.addRejected(result.rejected());
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
                            Instant now) {
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
        } else {
            state.addSubmitted(1L);
            lastTradeTimes.put(tradeKey, now);
            lastTradeSides.put(tradeKey, side);
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

    private MarketMakerProperties.Strategy findStrategy(String strategyId) {
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

    private String resolveNodeId(String configuredNodeId) {
        if (configuredNodeId != null && !configuredNodeId.isBlank()) {
            return configuredNodeId.trim();
        }
        return "market-maker-" + UUID.randomUUID();
    }

    private record ReconcileResult(long submitted, long canceled, long rejected) {
    }

    private record TradeTarget(long priceTicks, long availableQuantitySteps) {
    }
}
