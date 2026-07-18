package com.surprising.trading.matching.service;

import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.account.api.model.AccountUserCommandType;
import com.surprising.account.api.model.OrderReleaseAccountCommand;
import com.surprising.account.api.model.TradeParticipantRole;
import com.surprising.account.api.model.TradeSideSettlementCommand;
import com.surprising.trading.api.model.MatchResultEvent;
import com.surprising.trading.api.model.MatchTradeEvent;
import com.surprising.trading.api.model.MarketPriceProtection;
import com.surprising.trading.api.model.OrderBookDepthEvent;
import com.surprising.trading.api.model.OrderBookDepthUpdateType;
import com.surprising.trading.api.model.OrderBookLevel;
import com.surprising.trading.api.model.OrderBookSnapshotResponse;
import com.surprising.trading.api.model.OrderCommandEvent;
import com.surprising.trading.api.model.OrderCommandType;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.PublicTradeEvent;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.matching.config.MatchingProperties;
import com.surprising.trading.matching.model.MatchedOrderSnapshot;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class MatchingService {

    private static final Logger log = LoggerFactory.getLogger(MatchingService.class);
    private static final long PUBLIC_TRADE_SEQUENCE_MULTIPLIER = 1_000_000L;

    private final ObjectMapper objectMapper;
    private final MatchingProperties properties;
    private final ExchangeCoreEngine exchangeCoreEngine;
    private final MatchingProtectionRepository protectionRepository;
    private final MatchingSequenceRepository sequenceRepository;
    private final MatchingResultRepository resultRepository;
    private final MatchingOutboxRepository outboxRepository;
    private final OrderBookDepthPublisher depthPublisher;
    private final PublicTradePublisher tradePublisher;
    private final Map<String, DepthState> depthStates = new ConcurrentHashMap<>();

    @Autowired
    public MatchingService(ObjectMapper objectMapper,
                           MatchingProperties properties,
                           ExchangeCoreEngine exchangeCoreEngine,
                           MatchingProtectionRepository protectionRepository,
                           MatchingSequenceRepository sequenceRepository,
                           MatchingResultRepository resultRepository,
                           MatchingOutboxRepository outboxRepository,
                           OrderBookDepthPublisher depthPublisher,
                           PublicTradePublisher tradePublisher) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.exchangeCoreEngine = exchangeCoreEngine;
        this.protectionRepository = protectionRepository;
        this.sequenceRepository = sequenceRepository;
        this.resultRepository = resultRepository;
        this.outboxRepository = outboxRepository;
        this.depthPublisher = depthPublisher;
        this.tradePublisher = tradePublisher;
    }

    MatchingService(ObjectMapper objectMapper,
                    MatchingProperties properties,
                    ExchangeCoreEngine exchangeCoreEngine,
                    MatchingProtectionRepository protectionRepository,
                    MatchingSequenceRepository sequenceRepository,
                    MatchingResultRepository resultRepository,
                    MatchingOutboxRepository outboxRepository) {
        this(objectMapper, properties, exchangeCoreEngine, protectionRepository, sequenceRepository,
                resultRepository, outboxRepository, OrderBookDepthPublisher.NOOP, PublicTradePublisher.NOOP);
    }

    @Transactional
    public void process(OrderCommandEvent command) {
        if (resultRepository.commandResultExists(command.commandId())) {
            return;
        }
        if (!resultRepository.orderExists(command.orderId())) {
            return;
        }
        Instant now = Instant.now();
        MatchingSymbol symbol = exchangeCoreEngine.ensureSymbol(command.symbol())
                .orElse(null);
        if (symbol == null) {
            saveAndPublish(rejected(command, "UNKNOWN_SYMBOL", now));
            return;
        }
        long effectivePriceTicks = command.commandType() == OrderCommandType.PLACE
                ? effectivePriceTicks(command)
                : command.priceTicks();
        if (command.commandType() == OrderCommandType.PLACE && effectivePriceTicks <= 0) {
            saveAndPublish(rejected(command, "MARK_PRICE_UNAVAILABLE", now));
            return;
        }
        if (command.commandType() == OrderCommandType.PLACE
                && protectionRepository.hasOpenOrdersWithDifferentInstrumentVersion(command.symbol(),
                command.instrumentVersion(), command.orderId())) {
            saveAndPublish(rejected(command, "INSTRUMENT_VERSION_OPEN_BOOK_MISMATCH", now));
            return;
        }
        if (command.commandType() == OrderCommandType.PLACE
                && exchangeCoreEngine.wouldTakeLiquidity(command, symbol, effectivePriceTicks)) {
            saveAndPublish(rejected(command, "POST_ONLY_WOULD_TAKE", now));
            return;
        }
        if (wouldSelfTrade(command, effectivePriceTicks)) {
            saveAndPublish(rejected(command, "SELF_TRADE_PREVENTED", now));
            return;
        }

        OrderCommand response = exchangeCoreEngine.submit(command, symbol, effectivePriceTicks);
        publishOrderBookDepth(symbol, response.resultCode, now);
        publishPublicTrades(command, response, now);
        MatchResultEvent result = toResultEvent(command, response, now);
        saveAndPublish(result);
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
        Map<Long, MatchedOrderSnapshot> makerSnapshots = new HashMap<>();
        response.processMatcherEvents(event -> {
            if (event.eventType != MatcherEventType.TRADE) {
                return;
            }
            trades.add(toTrade(command, event, now, makerSnapshots));
        });
        return trades;
    }

    private MatchTradeEvent toTrade(OrderCommandEvent command,
                                    MatcherTradeEvent event,
                                    Instant now,
                                    Map<Long, MatchedOrderSnapshot> makerSnapshots) {
        MatchedOrderSnapshot makerSnapshot = makerSnapshots.computeIfAbsent(event.matchedOrderId,
                resultRepository::orderSnapshot);
        return new MatchTradeEvent(
                sequenceRepository.nextSequence("match-trade"),
                command.commandId(),
                command.symbol(),
                command.orderId(),
                command.instrumentVersion(),
                command.userId(),
                command.side(),
                command.marginMode(),
                command.positionSide(),
                event.matchedOrderId,
                makerSnapshot.instrumentVersion(),
                event.matchedOrderUid,
                makerSnapshot.marginMode(),
                makerSnapshot.positionSide(),
                command.takerFeeRatePpm(),
                makerSnapshot.makerFeeRatePpm(),
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

    private void saveAndPublish(MatchResultEvent result) {
        if (!resultRepository.saveResult(result)) {
            return;
        }
        resultRepository.applyActiveOrderStatus(result);
        for (MatchTradeEvent trade : result.trades()) {
            if (!resultRepository.saveTrade(trade)) {
                continue;
            }
            resultRepository.applyMakerFill(trade);
            enqueueAccountTradeSide(trade, TradeParticipantRole.TAKER);
            enqueueAccountTradeSide(trade, TradeParticipantRole.MAKER);
        }
        enqueueAccountReleaseIfRequired(result);
        outboxRepository.enqueue("MATCH_RESULT", result.commandId(), properties.getKafka().getMatchResultsTopic(),
                result.symbol(), result.commandType().name(), payload(result), result.eventTime());
    }

    private void publishOrderBookDepth(MatchingSymbol symbol, CommandResultCode resultCode, Instant eventTime) {
        if (symbol == null || resultCode != CommandResultCode.SUCCESS) {
            return;
        }
        try {
            OrderBookDepthEvent depthEvent = orderBookDepthSnapshot(symbol, eventTime);
            if (depthEvent != null) {
                depthPublisher.offer(depthEvent);
            }
        } catch (RuntimeException error) {
            log.warn("Public depth publication isolated from financial processing symbol={}: {}",
                    symbol.symbol(), error.getMessage());
        }
    }

    private void publishPublicTrades(OrderCommandEvent command, OrderCommand response, Instant eventTime) {
        if (response.resultCode != CommandResultCode.SUCCESS) {
            return;
        }
        int[] matchIndex = {0};
        try {
            response.processMatcherEvents(event -> {
                if (event.eventType != MatcherEventType.TRADE) {
                    return;
                }
                int index = Math.incrementExact(matchIndex[0]);
                long sequence = Math.addExact(
                        Math.multiplyExact(command.commandId(), PUBLIC_TRADE_SEQUENCE_MULTIPLIER), index);
                tradePublisher.offer(new PublicTradeEvent(
                        command.commandId() + ":" + index,
                        sequence,
                        command.symbol(),
                        command.instrumentVersion(),
                        command.side(),
                        event.price,
                        event.size,
                        eventTime,
                        command.traceId()));
            });
        } catch (RuntimeException error) {
            log.warn("Public trade publication isolated from financial processing symbol={} commandId={}: {}",
                    command.symbol(), command.commandId(), error.getMessage());
        }
    }

    private void enqueueAccountTradeSide(MatchTradeEvent trade, TradeParticipantRole role) {
        long userId = role == TradeParticipantRole.TAKER ? trade.takerUserId() : trade.makerUserId();
        String commandId = tradeSideCommandId(trade, role, userId);
        TradeSideSettlementCommand side = new TradeSideSettlementCommand(trade, role);
        AccountUserCommand command = new AccountUserCommand(
                AccountUserCommand.CURRENT_SCHEMA_VERSION,
                commandId,
                properties.getKafka().getProductLine(),
                userId,
                AccountUserCommandType.TRADE_SIDE_SETTLE,
                "MATCHING",
                properties.getKafka().getProductLine().name() + ":" + trade.symbol() + ":" + trade.tradeId(),
                null,
                payload(side),
                trade.eventTime(),
                trade.traceId());
        outboxRepository.enqueue("ACCOUNT_COMMAND", trade.tradeId(),
                properties.getKafka().getAccountUserCommandsTopic(), command.partitionKey(),
                command.commandType().name(), payload(command), trade.eventTime());
    }

    private void enqueueAccountReleaseIfRequired(MatchResultEvent result) {
        boolean rejected = result.orderStatus() == OrderStatus.REJECTED;
        boolean canceled = result.orderStatus() == OrderStatus.CANCELED;
        boolean terminal = result.orderStatus() == OrderStatus.FILLED || canceled || rejected;
        if (!terminal) {
            return;
        }
        String reason = rejected ? "ORDER_REJECTED" : canceled ? "ORDER_CANCELED" : "ORDER_TERMINAL";
        String dependency = result.trades().isEmpty() ? null
                : tradeSideCommandId(result.trades().get(result.trades().size() - 1),
                TradeParticipantRole.TAKER, result.userId());
        OrderReleaseAccountCommand release = new OrderReleaseAccountCommand(
                result.orderId(), rejected, reason, result.eventTime());
        AccountUserCommand command = new AccountUserCommand(
                AccountUserCommand.CURRENT_SCHEMA_VERSION,
                "ORDER_RELEASE:" + properties.getKafka().getProductLine().name() + ":" + result.orderId()
                        + ":" + result.commandId(),
                properties.getKafka().getProductLine(),
                result.userId(),
                AccountUserCommandType.ORDER_RELEASE,
                "MATCHING",
                String.valueOf(result.orderId()),
                dependency,
                payload(release),
                result.eventTime(),
                result.traceId());
        outboxRepository.enqueue("ACCOUNT_COMMAND", result.commandId(),
                properties.getKafka().getAccountUserCommandsTopic(), command.partitionKey(),
                command.commandType().name(), payload(command), result.eventTime());
    }

    private String tradeSideCommandId(MatchTradeEvent trade, TradeParticipantRole role, long userId) {
        return "TRADE:" + properties.getKafka().getProductLine().name() + ":" + trade.symbol()
                + ":" + trade.tradeId() + ":" + role.name() + ":" + userId;
    }

    private OrderBookDepthEvent orderBookDepthSnapshot(MatchingSymbol symbol, Instant now) {
        int depth = Math.max(1, properties.getEngine().getOrderBookDepthLevels());
        DepthSnapshot current = snapshot(exchangeCoreEngine.requestOrderBook(symbol, depth));
        DepthState state = depthStates.computeIfAbsent(symbol.symbol(), ignored -> new DepthState());
        synchronized (state) {
            if (current.equals(state.snapshot)) {
                return null;
            }
            long sequence = Math.incrementExact(state.lastSequence);
            OrderBookDepthEvent event = new OrderBookDepthEvent(symbol.symbol(), sequence, state.lastSequence,
                    OrderBookDepthUpdateType.SNAPSHOT, depth, List.copyOf(current.bids().values()),
                    List.copyOf(current.asks().values()), now);
            state.snapshot = current;
            state.lastSequence = sequence;
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
    }
}
