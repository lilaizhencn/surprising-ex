package com.surprising.trading.matching.service;

import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.account.api.model.AccountUserCommandType;
import com.surprising.account.api.model.AccountType;
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
import com.surprising.trading.matching.repository.MatchingOutboxRepository.MatchingOutboxWrite;
import com.surprising.trading.matching.repository.MatchingProtectionRepository;
import com.surprising.trading.matching.repository.MatchingResultRepository;
import exchange.core2.core.common.L2MarketData;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherTradeEvent;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.cmd.OrderCommand;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
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
                           MatchingResultRepository resultRepository,
                           MatchingOutboxRepository outboxRepository,
                           OrderBookDepthPublisher depthPublisher,
                           PublicTradePublisher tradePublisher) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.exchangeCoreEngine = exchangeCoreEngine;
        this.protectionRepository = protectionRepository;
        this.resultRepository = resultRepository;
        this.outboxRepository = outboxRepository;
        this.depthPublisher = depthPublisher;
        this.tradePublisher = tradePublisher;
    }

    MatchingService(ObjectMapper objectMapper,
                    MatchingProperties properties,
                    ExchangeCoreEngine exchangeCoreEngine,
                    MatchingProtectionRepository protectionRepository,
                    MatchingResultRepository resultRepository,
                    MatchingOutboxRepository outboxRepository) {
        this(objectMapper, properties, exchangeCoreEngine, protectionRepository,
                resultRepository, outboxRepository, OrderBookDepthPublisher.NOOP, PublicTradePublisher.NOOP);
    }

    @Transactional
    public void process(OrderCommandEvent command) {
        process(command, resultRepository.commandState(command.commandId(), command.orderId()),
                ProtectionChecks.REQUIRED, true);
    }

    @Transactional
    public void processBatch(List<OrderCommandEvent> commands) {
        if (commands == null || commands.isEmpty()) {
            return;
        }
        LinkedHashMap<Long, OrderCommandEvent> uniqueCommands = new LinkedHashMap<>(commands.size());
        for (OrderCommandEvent command : commands) {
            if (command == null) {
                throw new IllegalArgumentException("matching command batch contains null");
            }
            OrderCommandEvent duplicate = uniqueCommands.putIfAbsent(command.commandId(), command);
            if (duplicate != null && !duplicate.equals(command)) {
                throw new IllegalArgumentException("conflicting matching commands for commandId="
                        + command.commandId());
            }
        }
        LinkedHashMap<Long, Long> commandOrderIds = new LinkedHashMap<>(uniqueCommands.size());
        uniqueCommands.forEach((commandId, command) -> commandOrderIds.put(commandId, command.orderId()));
        Map<Long, MatchingResultRepository.CommandState> states = resultRepository.commandStates(commandOrderIds);
        Map<Long, ProtectionChecks> protectionChecks = batchProtectionChecks(uniqueCommands.values(), states);
        Map<String, DepthPublication> depthPublications = new LinkedHashMap<>();
        for (OrderCommandEvent command : uniqueCommands.values()) {
            MatchingResultRepository.CommandState state = states.get(command.commandId());
            if (state == null) {
                throw new IllegalStateException("matching command state missing for commandId="
                        + command.commandId());
            }
            MatchingSymbol changedSymbol = process(command, state,
                    protectionChecks.getOrDefault(command.commandId(), ProtectionChecks.REQUIRED), false);
            if (changedSymbol != null) {
                depthPublications.put(changedSymbol.symbol(), new DepthPublication(changedSymbol, Instant.now()));
            }
        }
        // Public depth is an explicitly lossy latest-only stream. Build one final snapshot per changed symbol
        // instead of traversing the same exchange-core book once for every command in the Kafka batch.
        for (DepthPublication publication : depthPublications.values()) {
            publishOrderBookDepth(publication.symbol(), CommandResultCode.SUCCESS, publication.eventTime());
        }
    }

    private Map<Long, ProtectionChecks> batchProtectionChecks(
            Iterable<OrderCommandEvent> commands,
            Map<Long, MatchingResultRepository.CommandState> states) {
        List<OrderCommandEvent> placements = new ArrayList<>();
        Map<String, Long> symbolVersions = new HashMap<>();
        Set<String> symbolsWithMultipleVersions = new HashSet<>();
        Map<UserSymbolKey, Integer> userSymbolCounts = new HashMap<>();
        for (OrderCommandEvent command : commands) {
            MatchingResultRepository.CommandState state = states.get(command.commandId());
            if (state == null || state.resultExists() || !state.orderExists()
                    || command.commandType() != OrderCommandType.PLACE) {
                continue;
            }
            placements.add(command);
            Long previousVersion = symbolVersions.putIfAbsent(command.symbol(), command.instrumentVersion());
            if (previousVersion != null && previousVersion.longValue() != command.instrumentVersion()) {
                symbolsWithMultipleVersions.add(command.symbol());
            }
            if (!properties.getProtection().isInternalMarketMaker(command.userId())) {
                userSymbolCounts.merge(new UserSymbolKey(command.userId(), command.symbol()), 1, Integer::sum);
            }
        }
        if (placements.isEmpty()) {
            return Map.of();
        }

        Set<Long> versionConflicts = protectionRepository
                .commandsWithOpenOrdersAtDifferentInstrumentVersion(placements);
        List<OrderCommandEvent> stableSelfTradeChecks = placements.stream()
                .filter(command -> command.orderType() == OrderType.LIMIT)
                .filter(command -> !properties.getProtection().isInternalMarketMaker(command.userId()))
                .filter(command -> userSymbolCounts.getOrDefault(
                        new UserSymbolKey(command.userId(), command.symbol()), 0) == 1)
                .toList();
        Set<Long> selfTradeConflicts = properties.getProtection().isSelfTradePreventionEnabled()
                ? protectionRepository.commandsThatWouldSelfTrade(stableSelfTradeChecks)
                : Set.of();

        Map<Long, ProtectionChecks> checks = new HashMap<>(placements.size());
        for (OrderCommandEvent command : placements) {
            boolean stableVersionMiss = !symbolsWithMultipleVersions.contains(command.symbol())
                    && !versionConflicts.contains(command.commandId());
            boolean stableSelfTradeMiss = properties.getProtection().isSelfTradePreventionEnabled()
                    && command.orderType() == OrderType.LIMIT
                    && !properties.getProtection().isInternalMarketMaker(command.userId())
                    && userSymbolCounts.getOrDefault(
                            new UserSymbolKey(command.userId(), command.symbol()), 0) == 1
                    && !selfTradeConflicts.contains(command.commandId());
            checks.put(command.commandId(), new ProtectionChecks(stableVersionMiss, stableSelfTradeMiss));
        }
        return Map.copyOf(checks);
    }

    private MatchingSymbol process(OrderCommandEvent command,
                                   MatchingResultRepository.CommandState commandState,
                                   ProtectionChecks protectionChecks,
                                   boolean publishDepth) {
        if (commandState.resultExists()) {
            return null;
        }
        if (!commandState.orderExists()) {
            return null;
        }
        Instant now = Instant.now();
        MatchingSymbol symbol = exchangeCoreEngine.ensureSymbol(command.symbol())
                .orElse(null);
        if (symbol == null) {
            saveAndPublish(rejected(command, "UNKNOWN_SYMBOL", now), command, Map.of());
            return null;
        }
        long effectivePriceTicks = command.commandType() == OrderCommandType.PLACE
                ? effectivePriceTicks(command)
                : command.priceTicks();
        if (command.commandType() == OrderCommandType.PLACE && effectivePriceTicks <= 0) {
            saveAndPublish(rejected(command, "MARK_PRICE_UNAVAILABLE", now), command, Map.of());
            return null;
        }
        if (command.commandType() == OrderCommandType.PLACE
                && !protectionChecks.skipVersionDatabaseCheck()
                && protectionRepository.hasOpenOrdersWithDifferentInstrumentVersion(command.symbol(),
                command.instrumentVersion(), command.orderId())) {
            saveAndPublish(rejected(command, "INSTRUMENT_VERSION_OPEN_BOOK_MISMATCH", now), command, Map.of());
            return null;
        }
        if (command.commandType() == OrderCommandType.PLACE
                && exchangeCoreEngine.wouldTakeLiquidity(command, symbol, effectivePriceTicks)) {
            saveAndPublish(rejected(command, "POST_ONLY_WOULD_TAKE", now), command, Map.of());
            return null;
        }
        if (!protectionChecks.skipSelfTradeDatabaseCheck()
                && wouldSelfTrade(command, effectivePriceTicks)) {
            saveAndPublish(rejected(command, "SELF_TRADE_PREVENTED", now), command, Map.of());
            return null;
        }

        OrderCommand response = exchangeCoreEngine.submit(command, symbol, effectivePriceTicks);
        if (publishDepth) {
            publishOrderBookDepth(symbol, response.resultCode, now);
        }
        publishPublicTrades(command, response, now);
        Map<Long, OrderQuantitySnapshot> makerQuantitySnapshots = new HashMap<>();
        MatchResultEvent result = toResultEvent(command, response, now, makerQuantitySnapshots);
        saveAndPublish(result, command, makerQuantitySnapshots);
        return response.resultCode == CommandResultCode.SUCCESS ? symbol : null;
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
        if (properties.getProtection().isInternalMarketMaker(command.userId())) {
            return false;
        }
        return protectionRepository.wouldSelfTrade(command.userId(), command.symbol(), command.instrumentVersion(),
                command.side(), effectivePriceTicks);
    }

    private MatchResultEvent toResultEvent(OrderCommandEvent command,
                                           OrderCommand response,
                                           Instant now,
                                           Map<Long, OrderQuantitySnapshot> makerQuantitySnapshots) {
        List<MatchTradeEvent> trades = trades(command, response, now, makerQuantitySnapshots);
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

    private List<MatchTradeEvent> trades(OrderCommandEvent command,
                                         OrderCommand response,
                                         Instant now,
                                         Map<Long, OrderQuantitySnapshot> makerQuantitySnapshots) {
        List<RawTrade> rawTrades = new ArrayList<>();
        response.processMatcherEvents(event -> {
            if (event.eventType == MatcherEventType.TRADE) {
                rawTrades.add(new RawTrade(
                        event.matchedOrderId,
                        event.matchedOrderUid,
                        event.price,
                        event.size,
                        event.activeOrderCompleted,
                        event.matchedOrderCompleted));
            }
        });
        if (rawTrades.size() >= PUBLIC_TRADE_SEQUENCE_MULTIPLIER) {
            throw new IllegalStateException("one matching command cannot produce "
                    + PUBLIC_TRADE_SEQUENCE_MULTIPLIER + " or more trades");
        }
        Map<Long, MatchedOrderSnapshot> makerSnapshots = resultRepository.orderSnapshots(
                rawTrades.stream().map(RawTrade::matchedOrderId).toList());
        List<MatchTradeEvent> trades = new ArrayList<>(rawTrades.size());
        Map<Long, Long> makerRemainingQuantities = new HashMap<>();
        for (int i = 0; i < rawTrades.size(); i++) {
            trades.add(toTrade(command, rawTrades.get(i), i + 1, now, makerSnapshots,
                    makerRemainingQuantities, makerQuantitySnapshots));
        }
        return trades;
    }

    private MatchTradeEvent toTrade(OrderCommandEvent command,
                                    RawTrade rawTrade,
                                    int matchIndex,
                                    Instant now,
                                    Map<Long, MatchedOrderSnapshot> makerSnapshots,
                                    Map<Long, Long> makerRemainingQuantities,
                                    Map<Long, OrderQuantitySnapshot> makerQuantitySnapshots) {
        MatchedOrderSnapshot makerSnapshot = makerSnapshots.get(rawTrade.matchedOrderId());
        if (makerSnapshot == null) {
            throw new IllegalStateException("maker order snapshot not found for order " + rawTrade.matchedOrderId());
        }
        long remainingBefore = makerRemainingQuantities.getOrDefault(
                rawTrade.matchedOrderId(), makerSnapshot.remainingQuantitySteps());
        long remainingAfter = Math.subtractExact(remainingBefore, rawTrade.size());
        if (remainingAfter < 0) {
            throw new IllegalStateException("maker fill exceeds remaining quantity for order "
                    + rawTrade.matchedOrderId());
        }
        makerRemainingQuantities.put(rawTrade.matchedOrderId(), remainingAfter);
        long tradeId = deterministicTradeId(command.commandId(), matchIndex);
        MatchTradeEvent trade = new MatchTradeEvent(
                tradeId,
                command.commandId(),
                command.symbol(),
                command.orderId(),
                command.instrumentVersion(),
                command.userId(),
                command.side(),
                command.marginMode(),
                command.positionSide(),
                rawTrade.matchedOrderId(),
                makerSnapshot.instrumentVersion(),
                rawTrade.matchedOrderUid(),
                makerSnapshot.marginMode(),
                makerSnapshot.positionSide(),
                command.takerFeeRatePpm(),
                makerSnapshot.makerFeeRatePpm(),
                rawTrade.price(),
                rawTrade.size(),
                rawTrade.activeOrderCompleted(),
                rawTrade.matchedOrderCompleted(),
                now,
                command.traceId());
        makerQuantitySnapshots.put(tradeId,
                new OrderQuantitySnapshot(
                        makerSnapshot.quantitySteps(), remainingAfter, makerSnapshot.reduceOnly(),
                        makerSnapshot.reservationAccountType(), makerSnapshot.reservationAsset(),
                        makerSnapshot.reservedUnits()));
        return trade;
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

    private void saveAndPublish(MatchResultEvent result,
                                OrderCommandEvent orderCommand,
                                Map<Long, OrderQuantitySnapshot> makerQuantitySnapshots) {
        if (!resultRepository.saveResult(result)) {
            return;
        }
        resultRepository.applyActiveOrderStatus(result);
        boolean containsInternalSelfTrade = false;
        String lastTakerFinancialCommandId = null;
        List<MatchTradeEvent> externalTrades = new ArrayList<>(result.trades().size());
        List<MatchingOutboxWrite> outboxWrites = new ArrayList<>(
                Math.max(1, result.trades().size() * 2 + 2));
        for (MatchTradeEvent trade : result.trades()) {
            if (isInternalSelfTrade(trade)) {
                containsInternalSelfTrade = true;
                enqueueInternalSelfTradeMakerRelease(
                        outboxWrites, trade, requireQuantitySnapshot(trade, makerQuantitySnapshots));
                continue;
            }
            externalTrades.add(trade);
            lastTakerFinancialCommandId = tradeSideCommandId(
                    trade, TradeParticipantRole.TAKER, trade.takerUserId());
            enqueueAccountTradeSide(outboxWrites, trade, TradeParticipantRole.TAKER,
                    orderCommand, makerQuantitySnapshots);
            enqueueAccountTradeSide(outboxWrites, trade, TradeParticipantRole.MAKER,
                    orderCommand, makerQuantitySnapshots);
            if (trade.makerOrderCompleted()) {
                enqueueCompletedMakerRelease(outboxWrites, trade,
                        requireQuantitySnapshot(trade, makerQuantitySnapshots));
            }
        }
        resultRepository.saveTrades(externalTrades);
        resultRepository.applyMakerFills(result.trades());
        enqueueActiveOrderReleaseIfRequired(outboxWrites, result, orderCommand, containsInternalSelfTrade,
                lastTakerFinancialCommandId);
        outboxWrites.add(new MatchingOutboxWrite(
                "MATCH_RESULT",
                result.commandId(),
                properties.getKafka().getMatchResultsTopic(),
                result.symbol(),
                result.commandType().name(),
                payload(result),
                result.eventTime()));
        outboxRepository.enqueueBatch(outboxWrites);
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
        AtomicInteger matchIndex = new AtomicInteger();
        try {
            response.processMatcherEvents(event -> {
                if (event.eventType != MatcherEventType.TRADE) {
                    return;
                }
                int index = matchIndex.incrementAndGet();
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

    private void enqueueAccountTradeSide(List<MatchingOutboxWrite> writes,
                                         MatchTradeEvent trade,
                                         TradeParticipantRole role,
                                         OrderCommandEvent orderCommand,
                                         Map<Long, OrderQuantitySnapshot> makerQuantitySnapshots) {
        long userId = role == TradeParticipantRole.TAKER ? trade.takerUserId() : trade.makerUserId();
        String commandId = tradeSideCommandId(trade, role, userId);
        OrderQuantitySnapshot quantitySnapshot = role == TradeParticipantRole.TAKER
                ? new OrderQuantitySnapshot(
                        orderCommand.quantitySteps(),
                        Math.subtractExact(orderCommand.quantitySteps(), trade.quantitySteps()),
                        orderCommand.reduceOnly(),
                        orderCommand.reservationAccountType(),
                        orderCommand.reservationAsset(),
                        orderCommand.reservedUnits())
                : requireQuantitySnapshot(trade, makerQuantitySnapshots);
        TradeSideSettlementCommand side = new TradeSideSettlementCommand(
                trade, role, quantitySnapshot.quantitySteps(), quantitySnapshot.reduceOnly(),
                accountType(quantitySnapshot.reservationAccountType()), quantitySnapshot.reservationAsset(),
                quantitySnapshot.reservedUnits());
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
        writes.add(new MatchingOutboxWrite(
                "ACCOUNT_COMMAND",
                trade.tradeId(),
                properties.getKafka().getAccountUserCommandsTopic(),
                command.partitionKey(),
                command.commandType().name(),
                payload(command),
                trade.eventTime()));
    }

    private void enqueueInternalSelfTradeMakerRelease(List<MatchingOutboxWrite> writes,
                                                      MatchTradeEvent trade,
                                                      OrderQuantitySnapshot quantitySnapshot) {
        String commandId = "ORDER_RELEASE:" + properties.getKafka().getProductLine().name()
                + ":" + trade.makerOrderId() + ":INTERNAL_SELF_TRADE:" + trade.tradeId();
        enqueueOrderRelease(writes, commandId, trade.tradeId(), trade.makerUserId(), trade.makerOrderId(),
                trade.makerOrderCompleted(), quantitySnapshot.quantitySteps(),
                quantitySnapshot.remainingQuantitySteps(), !quantitySnapshot.reduceOnly(),
                quantitySnapshot.reservationAccountType(), quantitySnapshot.reservationAsset(),
                quantitySnapshot.reservedUnits(),
                "INTERNAL_MARKET_MAKER_SELF_TRADE", null,
                trade.eventTime(), trade.traceId());
    }

    private void enqueueCompletedMakerRelease(List<MatchingOutboxWrite> writes,
                                              MatchTradeEvent trade,
                                              OrderQuantitySnapshot quantitySnapshot) {
        String settlementCommandId = tradeSideCommandId(
                trade, TradeParticipantRole.MAKER, trade.makerUserId());
        String commandId = "ORDER_RELEASE:" + properties.getKafka().getProductLine().name()
                + ":" + trade.makerOrderId() + ":ORDER_TERMINAL:" + trade.tradeId();
        enqueueOrderRelease(writes, commandId, trade.tradeId(), trade.makerUserId(), trade.makerOrderId(),
                true, quantitySnapshot.quantitySteps(), 0L, !quantitySnapshot.reduceOnly(),
                quantitySnapshot.reservationAccountType(), quantitySnapshot.reservationAsset(),
                quantitySnapshot.reservedUnits(), "ORDER_TERMINAL", settlementCommandId,
                trade.eventTime(), trade.traceId());
    }

    private void enqueueActiveOrderReleaseIfRequired(List<MatchingOutboxWrite> writes,
                                                     MatchResultEvent result,
                                                     OrderCommandEvent orderCommand,
                                                     boolean containsInternalSelfTrade,
                                                     String dependencyCommandId) {
        boolean rejected = result.orderStatus() == OrderStatus.REJECTED;
        boolean canceled = result.orderStatus() == OrderStatus.CANCELED;
        boolean terminal = result.orderStatus() == OrderStatus.FILLED || canceled || rejected;
        if (!terminal && !containsInternalSelfTrade) {
            return;
        }
        String reason = rejected
                ? "ORDER_REJECTED"
                : canceled
                        ? "ORDER_CANCELED"
                        : terminal ? "ORDER_TERMINAL" : "INTERNAL_MARKET_MAKER_SELF_TRADE";
        String commandId = "ORDER_RELEASE:" + properties.getKafka().getProductLine().name()
                + ":" + result.orderId() + ":" + result.commandId();
        long remainingQuantitySteps = terminal
                ? 0L
                : Math.subtractExact(orderCommand.quantitySteps(), result.filledQuantitySteps());
        enqueueOrderRelease(writes, commandId, result.commandId(), result.userId(), result.orderId(),
                terminal, orderCommand.quantitySteps(), remainingQuantitySteps,
                !orderCommand.reduceOnly(), orderCommand.reservationAccountType(),
                orderCommand.reservationAsset(), orderCommand.reservedUnits(),
                reason, dependencyCommandId, result.eventTime(), result.traceId());
    }

    private void enqueueOrderRelease(List<MatchingOutboxWrite> writes,
                                     String commandId,
                                     long aggregateId,
                                     long userId,
                                     long orderId,
                                     boolean releaseAll,
                                     long quantitySteps,
                                     long remainingQuantitySteps,
                                     boolean reservationExpected,
                                     String reservationAccountType,
                                     String reservationAsset,
                                     long reservedUnits,
                                     String reason,
                                     String dependencyCommandId,
                                     Instant eventTime,
                                     String traceId) {
        OrderReleaseAccountCommand release = new OrderReleaseAccountCommand(
                orderId, releaseAll, quantitySteps, remainingQuantitySteps,
                reservationExpected, accountType(reservationAccountType),
                reservationAsset, reservedUnits, reason, eventTime);
        AccountUserCommand command = new AccountUserCommand(
                AccountUserCommand.CURRENT_SCHEMA_VERSION,
                commandId,
                properties.getKafka().getProductLine(),
                userId,
                AccountUserCommandType.ORDER_RELEASE,
                "MATCHING",
                String.valueOf(orderId),
                dependencyCommandId,
                payload(release),
                eventTime,
                traceId);
        writes.add(new MatchingOutboxWrite(
                "ACCOUNT_COMMAND",
                aggregateId,
                properties.getKafka().getAccountUserCommandsTopic(),
                command.partitionKey(),
                command.commandType().name(),
                payload(command),
                eventTime));
    }

    private String tradeSideCommandId(MatchTradeEvent trade, TradeParticipantRole role, long userId) {
        return "TRADE:" + properties.getKafka().getProductLine().name() + ":" + trade.symbol()
                + ":" + trade.tradeId() + ":" + role.name() + ":" + userId;
    }

    private AccountType accountType(String value) {
        return value == null || value.isBlank() ? null : AccountType.valueOf(value);
    }

    private boolean isInternalSelfTrade(MatchTradeEvent trade) {
        return isInternalMarketMaker(trade.takerUserId())
                && isInternalMarketMaker(trade.makerUserId());
    }

    private boolean isInternalMarketMaker(long userId) {
        return properties.getProtection().isInternalMarketMaker(userId);
    }

    private long deterministicTradeId(long commandId, int matchIndex) {
        if (matchIndex <= 0 || matchIndex >= PUBLIC_TRADE_SEQUENCE_MULTIPLIER) {
            throw new IllegalArgumentException("match index is outside deterministic trade-id range");
        }
        return Math.addExact(Math.multiplyExact(commandId, PUBLIC_TRADE_SEQUENCE_MULTIPLIER), matchIndex);
    }

    private OrderQuantitySnapshot requireQuantitySnapshot(
            MatchTradeEvent trade,
            Map<Long, OrderQuantitySnapshot> makerQuantitySnapshots) {
        OrderQuantitySnapshot snapshot = makerQuantitySnapshots.get(trade.tradeId());
        if (snapshot == null) {
            throw new IllegalStateException("missing maker quantity snapshot for trade " + trade.tradeId());
        }
        return snapshot;
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

    private record DepthPublication(MatchingSymbol symbol, Instant eventTime) {
    }

    private record RawTrade(
            long matchedOrderId,
            long matchedOrderUid,
            long price,
            long size,
            boolean activeOrderCompleted,
            boolean matchedOrderCompleted) {
    }

    private record OrderQuantitySnapshot(
            long quantitySteps,
            long remainingQuantitySteps,
            boolean reduceOnly,
            String reservationAccountType,
            String reservationAsset,
            long reservedUnits) {

        private OrderQuantitySnapshot {
            if (quantitySteps <= 0 || remainingQuantitySteps < 0 || remainingQuantitySteps > quantitySteps) {
                throw new IllegalArgumentException("invalid order quantity snapshot");
            }
            if (reservedUnits < 0L || (reservedUnits > 0L
                    && (reservationAccountType == null || reservationAccountType.isBlank()
                    || reservationAsset == null || reservationAsset.isBlank()))) {
                throw new IllegalArgumentException("invalid order reservation snapshot");
            }
        }
    }

    private record UserSymbolKey(long userId, String symbol) {
    }

    private record ProtectionChecks(
            boolean skipVersionDatabaseCheck,
            boolean skipSelfTradeDatabaseCheck) {

        private static final ProtectionChecks REQUIRED = new ProtectionChecks(false, false);
    }

    private static final class DepthState {
        private DepthSnapshot snapshot;
        private long lastSequence;
    }
}
