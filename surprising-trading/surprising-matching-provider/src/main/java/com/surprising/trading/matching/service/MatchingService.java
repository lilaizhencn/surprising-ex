package com.surprising.trading.matching.service;

import com.surprising.trading.api.model.MatchResultEvent;
import com.surprising.trading.api.model.MatchTradeEvent;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.MarketPriceProtection;
import com.surprising.trading.api.model.OrderBookDepthEvent;
import com.surprising.trading.api.model.OrderBookDepthUpdateType;
import com.surprising.trading.api.model.OrderBookLevel;
import com.surprising.trading.api.model.OrderBookSnapshotResponse;
import com.surprising.trading.api.model.OrderCommandEvent;
import com.surprising.trading.api.model.OrderCommandType;
import com.surprising.trading.api.model.OrderEventType;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.matching.config.MatchingProperties;
import com.surprising.trading.matching.model.MatchingSymbol;
import com.surprising.trading.matching.repository.MatchingOutboxRepository;
import com.surprising.trading.matching.repository.MatchingProtectionRepository;
import com.surprising.trading.matching.repository.MatchingResultRepository;
import com.surprising.trading.matching.repository.MatchingSequenceRepository;
import exchange.core2.core.common.L2MarketData;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherTradeEvent;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.cmd.OrderCommand;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class MatchingService {

    private final ObjectMapper objectMapper;
    private final MatchingProperties properties;
    private final ExchangeCoreEngine exchangeCoreEngine;
    private final MatchingProtectionRepository protectionRepository;
    private final MatchingSequenceRepository sequenceRepository;
    private final MatchingResultRepository resultRepository;
    private final MatchingOutboxRepository outboxRepository;
    private final Map<String, DepthState> depthStates = new ConcurrentHashMap<>();

    public MatchingService(ObjectMapper objectMapper,
                           MatchingProperties properties,
                           ExchangeCoreEngine exchangeCoreEngine,
                           MatchingProtectionRepository protectionRepository,
                           MatchingSequenceRepository sequenceRepository,
                           MatchingResultRepository resultRepository,
                           MatchingOutboxRepository outboxRepository) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.exchangeCoreEngine = exchangeCoreEngine;
        this.protectionRepository = protectionRepository;
        this.sequenceRepository = sequenceRepository;
        this.resultRepository = resultRepository;
        this.outboxRepository = outboxRepository;
    }

    @Transactional
    public void process(OrderCommandEvent command) {
        if (resultRepository.commandResultExists(command.commandId())) {
            return;
        }
        Instant now = Instant.now();
        MatchingSymbol symbol = exchangeCoreEngine.ensureSymbol(command.symbol())
                .orElse(null);
        if (symbol == null) {
            saveAndPublish(null, rejected(command, "UNKNOWN_SYMBOL", now));
            return;
        }
        long effectivePriceTicks = command.commandType() == OrderCommandType.PLACE
                ? effectivePriceTicks(command)
                : command.priceTicks();
        if (command.commandType() == OrderCommandType.PLACE && effectivePriceTicks <= 0) {
            saveAndPublish(null, rejected(command, "MARK_PRICE_UNAVAILABLE", now));
            return;
        }
        if (command.commandType() == OrderCommandType.PLACE
                && protectionRepository.hasOpenOrdersWithDifferentInstrumentVersion(command.symbol(),
                command.instrumentVersion(), command.orderId())) {
            saveAndPublish(null, rejected(command, "INSTRUMENT_VERSION_OPEN_BOOK_MISMATCH", now));
            return;
        }
        if (command.commandType() == OrderCommandType.PLACE
                && exchangeCoreEngine.wouldTakeLiquidity(command, symbol, effectivePriceTicks)) {
            saveAndPublish(null, rejected(command, "POST_ONLY_WOULD_TAKE", now));
            return;
        }
        if (wouldSelfTrade(command, effectivePriceTicks)) {
            saveAndPublish(null, rejected(command, "SELF_TRADE_PREVENTED", now));
            return;
        }

        OrderCommand response = exchangeCoreEngine.submit(command, symbol, effectivePriceTicks);
        MatchResultEvent result = toResultEvent(command, response, now);
        saveAndPublish(symbol, result);
    }

    public OrderBookSnapshotResponse orderBookSnapshot(String symbol, int requestedDepth) {
        MatchingSymbol matchingSymbol = exchangeCoreEngine.ensureSymbol(symbol)
                .orElseThrow(() -> new IllegalArgumentException("unknown or non-trading symbol: " + symbol));
        int depth = Math.max(1, Math.min(requestedDepth, 200));
        DepthSnapshot current = snapshot(exchangeCoreEngine.requestOrderBook(matchingSymbol, depth));
        return new OrderBookSnapshotResponse(matchingSymbol.symbol(), lastDepthSequence(matchingSymbol.symbol()),
                depth, List.copyOf(current.bids().values()), List.copyOf(current.asks().values()), Instant.now());
    }

    private long effectivePriceTicks(OrderCommandEvent command) {
        if (command.commandType() == OrderCommandType.CANCEL) {
            return command.priceTicks();
        }
        if (command.orderType() == OrderType.LIMIT) {
            return command.priceTicks();
        }
        var protection = properties.getProtection();
        var markTicks = protectionRepository.latestMarkPriceTicks(command.symbol(), command.instrumentVersion(),
                Duration.ofMillis(Math.max(1L, protection.getMarketMaxMarkAgeMs())));
        if (markTicks.isEmpty()) {
            return 0L;
        }
        return MarketPriceProtection.protectedPriceTicks(command.side(), markTicks.getAsLong(),
                protection.getMarketMaxSlippagePpm());
    }

    private boolean wouldSelfTrade(OrderCommandEvent command, long effectivePriceTicks) {
        if (command.commandType() != OrderCommandType.PLACE
                || !properties.getProtection().isSelfTradePreventionEnabled()) {
            return false;
        }
        if (properties.getProtection().isSelfTradePreventionBypassed(command.userId())) {
            return false;
        }
        return protectionRepository.wouldSelfTrade(command.userId(), command.symbol(), command.instrumentVersion(),
                command.side(), effectivePriceTicks);
    }

    private MatchResultEvent toResultEvent(OrderCommandEvent command, OrderCommand response, Instant now) {
        List<MatchTradeEvent> trades = trades(command, response, now);
        long filledQuantity = trades.stream().mapToLong(MatchTradeEvent::quantitySteps)
                .reduce(0L, Math::addExact);
        OrderStatus status = status(command, response.resultCode, trades, filledQuantity);
        return new MatchResultEvent(
                command.commandId(),
                command.orderId(),
                command.userId(),
                command.symbol(),
                command.instrumentVersion(),
                command.commandType(),
                response.resultCode.name(),
                filledQuantity,
                status,
                now,
                trades,
                command.traceId());
    }

    private List<MatchTradeEvent> trades(OrderCommandEvent command, OrderCommand response, Instant now) {
        List<MatchTradeEvent> trades = new ArrayList<>();
        Map<Long, Long> makerVersions = new HashMap<>();
        Map<Long, MarginMode> makerMarginModes = new HashMap<>();
        response.processMatcherEvents(event -> {
            if (event.eventType != MatcherEventType.TRADE) {
                return;
            }
            trades.add(toTrade(command, event, now, makerVersions, makerMarginModes));
        });
        return trades;
    }

    private MatchTradeEvent toTrade(OrderCommandEvent command,
                                    MatcherTradeEvent event,
                                    Instant now,
                                    Map<Long, Long> makerVersions,
                                    Map<Long, MarginMode> makerMarginModes) {
        long makerInstrumentVersion = makerVersions.computeIfAbsent(event.matchedOrderId,
                resultRepository::orderInstrumentVersion);
        MarginMode makerMarginMode = makerMarginModes.computeIfAbsent(event.matchedOrderId,
                resultRepository::orderMarginMode);
        return new MatchTradeEvent(
                sequenceRepository.nextSequence("match-trade"),
                command.commandId(),
                command.symbol(),
                command.orderId(),
                command.instrumentVersion(),
                command.userId(),
                command.side(),
                command.marginMode(),
                event.matchedOrderId,
                makerInstrumentVersion,
                event.matchedOrderUid,
                makerMarginMode,
                event.price,
                event.size,
                event.activeOrderCompleted,
                event.matchedOrderCompleted,
                now,
                command.traceId());
    }

    private MatchResultEvent rejected(OrderCommandEvent command, String reason, Instant now) {
        return new MatchResultEvent(
                command.commandId(),
                command.orderId(),
                command.userId(),
                command.symbol(),
                command.instrumentVersion(),
                command.commandType(),
                reason,
                0L,
                OrderStatus.REJECTED,
                now,
                List.of(),
                command.traceId());
    }

    private OrderStatus status(OrderCommandEvent command,
                               CommandResultCode resultCode,
                               List<MatchTradeEvent> trades,
                               long filledQuantity) {
        if (resultCode != CommandResultCode.SUCCESS) {
            return command.commandType() == OrderCommandType.CANCEL
                    ? OrderStatus.CANCEL_REQUESTED
                    : OrderStatus.REJECTED;
        }
        if (command.commandType() == OrderCommandType.CANCEL) {
            return OrderStatus.CANCELED;
        }
        boolean immediate = isImmediate(command);
        if (filledQuantity == 0) {
            return immediate ? OrderStatus.CANCELED : OrderStatus.ACCEPTED;
        }
        boolean completed = !trades.isEmpty() && trades.get(trades.size() - 1).takerOrderCompleted();
        // IOC/FOK/MARKET orders are terminal even when only part of the submitted quantity traded.
        if (completed && filledQuantity >= command.quantitySteps()) {
            return OrderStatus.FILLED;
        }
        return immediate ? OrderStatus.CANCELED : OrderStatus.PARTIALLY_FILLED;
    }

    private boolean isImmediate(OrderCommandEvent command) {
        return command.orderType() == OrderType.MARKET
                || command.timeInForce() == TimeInForce.IOC
                || command.timeInForce() == TimeInForce.FOK;
    }

    private void saveAndPublish(MatchingSymbol symbol, MatchResultEvent result) {
        if (!resultRepository.saveResult(result)) {
            return;
        }
        resultRepository.applyActiveOrderStatus(result);
        for (MatchTradeEvent trade : result.trades()) {
            if (!resultRepository.saveTrade(trade)) {
                continue;
            }
            resultRepository.applyMakerFill(trade);
            outboxRepository.enqueue("MATCH_TRADE", trade.tradeId(), properties.getKafka().getMatchTradesTopic(),
                    trade.symbol(), OrderEventType.ACCEPTED.name(), payload(trade), trade.eventTime());
        }
        outboxRepository.enqueue("MATCH_RESULT", result.commandId(), properties.getKafka().getMatchResultsTopic(),
                result.symbol(), result.commandType().name(), payload(result), result.eventTime());
        if (symbol != null && "SUCCESS".equals(result.resultCode())) {
            OrderBookDepthEvent depthEvent = orderBookDepthEvent(symbol, result.eventTime());
            if (depthEvent != null) {
                outboxRepository.enqueue("ORDER_BOOK_DEPTH", depthEvent.sequence(),
                        properties.getKafka().getOrderBookDepthTopic(), depthEvent.symbol(),
                        depthEvent.updateType().name(), payload(depthEvent), depthEvent.eventTime());
            }
        }
    }

    private OrderBookDepthEvent orderBookDepthEvent(MatchingSymbol symbol, Instant now) {
        int depth = Math.max(1, properties.getEngine().getOrderBookDepthLevels());
        DepthSnapshot current = snapshot(exchangeCoreEngine.requestOrderBook(symbol, depth));
        DepthState state = depthStates.computeIfAbsent(symbol.symbol(), ignored -> new DepthState());
        synchronized (state) {
            boolean shouldSnapshot = state.snapshot == null
                    || state.eventsSinceSnapshot >= Math.max(1L,
                    properties.getEngine().getOrderBookSnapshotIntervalEvents());
            if (shouldSnapshot) {
                long sequence = sequenceRepository.nextSequence("orderbook-depth");
                OrderBookDepthEvent event = new OrderBookDepthEvent(symbol.symbol(), sequence, state.lastSequence,
                        OrderBookDepthUpdateType.SNAPSHOT, depth, List.copyOf(current.bids().values()),
                        List.copyOf(current.asks().values()), now);
                state.snapshot = current;
                state.lastSequence = sequence;
                state.eventsSinceSnapshot = 0L;
                return event;
            }

            List<OrderBookLevel> bidDeltas = diffLevels(state.snapshot.bids(), current.bids());
            List<OrderBookLevel> askDeltas = diffLevels(state.snapshot.asks(), current.asks());
            if (bidDeltas.isEmpty() && askDeltas.isEmpty()) {
                state.snapshot = current;
                return null;
            }
            long sequence = sequenceRepository.nextSequence("orderbook-depth");
            OrderBookDepthEvent event = new OrderBookDepthEvent(symbol.symbol(), sequence, state.lastSequence,
                    OrderBookDepthUpdateType.DELTA, depth, bidDeltas, askDeltas, now);
            state.snapshot = current;
            state.lastSequence = sequence;
            state.eventsSinceSnapshot++;
            return event;
        }
    }

    private long lastDepthSequence(String symbol) {
        DepthState state = depthStates.get(symbol);
        if (state == null) {
            return 0L;
        }
        synchronized (state) {
            return state.lastSequence;
        }
    }

    private DepthSnapshot snapshot(L2MarketData book) {
        return new DepthSnapshot(levels(book.bidPrices, book.bidVolumes, book.bidOrders, book.bidSize),
                levels(book.askPrices, book.askVolumes, book.askOrders, book.askSize));
    }

    private Map<Long, OrderBookLevel> levels(long[] prices, long[] volumes, long[] orders, int size) {
        Map<Long, OrderBookLevel> levels = new LinkedHashMap<>(Math.max(0, size));
        for (int i = 0; i < size; i++) {
            levels.put(prices[i], new OrderBookLevel(prices[i], volumes[i], orders[i]));
        }
        return levels;
    }

    private List<OrderBookLevel> diffLevels(Map<Long, OrderBookLevel> previous, Map<Long, OrderBookLevel> current) {
        List<OrderBookLevel> changes = new ArrayList<>();
        for (Map.Entry<Long, OrderBookLevel> entry : current.entrySet()) {
            OrderBookLevel old = previous.get(entry.getKey());
            if (!entry.getValue().equals(old)) {
                changes.add(entry.getValue());
            }
        }
        for (Long priceTicks : previous.keySet()) {
            if (!current.containsKey(priceTicks)) {
                changes.add(new OrderBookLevel(priceTicks, 0L, 0L));
            }
        }
        return changes;
    }

    private String payload(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new IllegalStateException("failed to serialize matching event", ex);
        }
    }

    private record DepthSnapshot(Map<Long, OrderBookLevel> bids,
                                 Map<Long, OrderBookLevel> asks) {
    }

    private static final class DepthState {
        private DepthSnapshot snapshot;
        private long lastSequence;
        private long eventsSinceSnapshot;
    }
}
