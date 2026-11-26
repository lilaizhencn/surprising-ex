package com.surprising.marketmaker.provider.service;

import com.surprising.account.api.model.PositionResponse;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.marketmaker.provider.client.MakerAeronTradingGateway;
import com.surprising.marketmaker.provider.config.MarketMakerProperties;
import com.surprising.marketmaker.provider.model.ExternalTrade;
import com.surprising.marketmaker.provider.model.StrategyRuntimeState;
import com.surprising.price.api.model.MarkPriceResponse;
import com.surprising.trading.api.model.BatchPlaceOrderRequest;
import com.surprising.trading.api.model.OrderBatchResponse;
import com.surprising.trading.api.model.OrderBookSnapshotResponse;
import com.surprising.trading.api.model.OrderCommandReceipt;
import com.surprising.trading.api.model.OrderResponse;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.PlaceOrderRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.random.RandomGenerator;
import lombok.extern.slf4j.Slf4j;

/** 做市模拟吃单与短期价格波动率观测协作器。 */
@Slf4j
final class MarketMakerSimulatedTrades {
    private final MarketMakerService service;
    private final MarketMakerProperties properties;
    private final MakerAeronTradingGateway aeronGateway;
    private final ReferenceMarketProvider referenceMarketProvider;
    private final MarketMakerMarketFeed marketFeed;
    private final Map<String, PriceState> priceStates = new ConcurrentHashMap<>();

    MarketMakerSimulatedTrades(MarketMakerService service,
                               MarketMakerProperties properties,
                               MakerAeronTradingGateway aeronGateway,
                               ReferenceMarketProvider referenceMarketProvider,
                               MarketMakerMarketFeed marketFeed) {
        this.service = service;
        this.properties = properties;
        this.aeronGateway = aeronGateway;
        this.referenceMarketProvider = referenceMarketProvider;
        this.marketFeed = marketFeed;
    }

    boolean maybeTrade(MarketMakerProperties.Strategy strategy, StrategyRuntimeState state,
                       long cycleSequence, String instrumentId, InstrumentResponse instrument,
                       MarkPriceResponse markPrice, Instant now) {
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
        OrderBookSnapshotResponse orderBook = marketFeed.fetchOrderBook(strategy.getProductLine(), instrumentId,
                properties.getQuoting().getOrderBookDepth());
        PositionResponse position = marketFeed.currentPosition(strategy, accountId, instrumentId, instrument);
        List<PlaceOrderRequest> requests = MakerSimulatedTradeSupport.externalPrintOrders(properties.getTrade(),
                strategy, instrumentId, accountId, instrument, orderBook, position.signedQuantitySteps(), prints,
                () -> service.takerClientOrderId(strategy, instrumentId, accountId, cycleSequence));
        if (requests.isEmpty()) {
            log.debug("Market-maker simulated trade skipped empty requests strategyId={} instrumentId={} accountId={} asks={} bids={} pos={}",
                    strategy.getStrategyId(), instrumentId, accountId,
                    orderBook == null || orderBook.asks() == null ? -1 : orderBook.asks().size(),
                    orderBook == null || orderBook.bids() == null ? -1 : orderBook.bids().size(),
                    position.signedQuantitySteps());
            return false;
        }
        if (requests.size() > 1) {
            return tradeBatch(strategy, instrumentId, accountId, cycleSequence, requests, state, now);
        }
        PlaceOrderRequest request = requests.getFirst();
        OrderCommandReceipt receipt = aeronGateway.place(strategy.getProductLine(), request);
        OrderResponse response = MakerQuoteMatchingSupport.receiptResult(receipt, OrderResponse.class);
        if (response == null || response.status() == OrderStatus.REJECTED) {
            state.addRejected(1L);
            String reason = response == null ? MakerQuoteMatchingSupport.receiptMessage(receipt) : response.rejectReason();
            log.debug("Market-maker simulated trade rejected strategyId={} instrumentId={} accountId={} reason={}",
                    strategy.getStrategyId(), instrumentId, accountId, reason);
            service.recordRunEvent(strategy, instrumentId, accountId, cycleSequence, "TRADE_REJECTED",
                    0, 0, 1, null, reason, now);
            return false;
        }
        state.addSubmitted(1L);
        if (response.executedQuantitySteps() <= 0) {
            log.debug("Market-maker simulated trade no fill strategyId={} instrumentId={} accountId={} status={}",
                    strategy.getStrategyId(), instrumentId, accountId, response.status());
            service.recordRunEvent(strategy, instrumentId, accountId, cycleSequence, "TRADE_NO_FILL",
                    1, 0, 0, "NO_EXECUTION", null, now);
            return false;
        }
        service.recordRunEvent(strategy, instrumentId, accountId, cycleSequence, "TRADE_EXECUTED",
                1, 0, 0, null, null, now);
        return true;
    }

    List<PlaceOrderRequest> simulatedOrders(MarketMakerProperties.Strategy strategy, String instrumentId,
                                            long accountId, long cycleSequence, InstrumentResponse instrument,
                                            OrderBookSnapshotResponse book, MarkPriceResponse mark,
                                            long position, RandomGenerator random) {
        return MakerSimulatedTradeSupport.simulatedOrders(properties.getTrade(), strategy, instrumentId,
                accountId, instrument, book, mark, position, random,
                () -> service.takerClientOrderId(strategy, instrumentId, accountId, cycleSequence));
    }

    private boolean tradeBatch(MarketMakerProperties.Strategy strategy, String instrumentId, long accountId,
                               long cycleSequence, List<PlaceOrderRequest> requests,
                               StrategyRuntimeState state, Instant now) {
        int count = requests.size();
        OrderCommandReceipt receipt = aeronGateway.placeBatch(strategy.getProductLine(), new BatchPlaceOrderRequest(requests));
        OrderBatchResponse response = MakerQuoteMatchingSupport.receiptResult(receipt, OrderBatchResponse.class);
        if (response == null) throw new IllegalStateException(MakerQuoteMatchingSupport.receiptMessage(receipt));
        int accepted = 0;
        int executed = 0;
        int missingOrderDetails = 0;
        String rejectionReason = null;
        for (var result : response.results()) {
            if (result.success() && (result.order() == null || result.order().status() != OrderStatus.REJECTED)) {
                accepted++;
                if (result.order() == null) missingOrderDetails++;
                else if (result.order().executedQuantitySteps() > 0) executed++;
            } else if (rejectionReason == null) {
                rejectionReason = result.order() != null && result.order().rejectReason() != null
                        ? result.order().rejectReason() : result.message();
            }
        }
        state.addSubmitted(accepted);
        state.addRejected(count - accepted);
        service.recordRunEvent(strategy, instrumentId, accountId, cycleSequence,
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

    long observeVolatility(MarketMakerProperties.Strategy strategy, String instrumentId,
                           InstrumentResponse instrument, OrderBookSnapshotResponse orderBook,
                           MarkPriceResponse markPrice) {
        long anchor = MakerSimulatedTradeSupport.markPriceTicks(instrument, markPrice);
        if (anchor <= 0) {
            anchor = MakerSimulatedTradeSupport.midPriceTicks(orderBook);
        }
        if (anchor <= 0) {
            return currentVolatility(strategy, instrumentId);
        }
        return priceStates.computeIfAbsent(
                MarketMakerParameterSupport.strategyKey(strategy) + ":" + instrumentId,
                ignored -> new PriceState()).observe(anchor);
    }

    long currentVolatility(MarketMakerProperties.Strategy strategy, String instrumentId) {
        PriceState state = priceStates.get(MarketMakerParameterSupport.strategyKey(strategy) + ":" + instrumentId);
        return state == null ? 0L : state.volatilityTicks();
    }

    private static final class PriceState {
        private final ReentrantLock lock = new ReentrantLock();
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
