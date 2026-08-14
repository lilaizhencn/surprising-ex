package com.surprising.trading.matching.service;

import com.surprising.aeron.protocol.CoreExportCodec;
import com.surprising.aeron.protocol.CoreExportEvent;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageCodec;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderStateView;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.OrderBookDepthEvent;
import com.surprising.trading.api.model.OrderBookDepthUpdateType;
import com.surprising.trading.api.model.OrderBookLevel;
import com.surprising.trading.api.model.OrderBookSnapshotResponse;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.PublicTradeEvent;
import com.surprising.trading.matching.config.MatchingProperties;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
public class CoreMarketDataProjection {

    private static final long TRADE_SEQUENCE_MULTIPLIER = 1_000_000L;

    private final MatchingProperties properties;
    private final MatchingAeronGateway aeronGateway;
    private final OrderBookDepthPublisher depthPublisher;
    private final PublicTradePublisher tradePublisher;
    private final LatestPublicTradeCache latestTradeCache;
    private final Map<String, MutableBook> books = new HashMap<>();
    private long appliedExportSequence;

    public CoreMarketDataProjection(
            MatchingProperties properties,
            MatchingAeronGateway aeronGateway,
            OrderBookDepthPublisher depthPublisher,
            PublicTradePublisher tradePublisher,
            LatestPublicTradeCache latestTradeCache) {
        this.properties = properties;
        this.aeronGateway = aeronGateway;
        this.depthPublisher = depthPublisher;
        this.tradePublisher = tradePublisher;
        this.latestTradeCache = latestTradeCache;
    }

    @PostConstruct
    public synchronized void initialize() {
        var bootstrap = aeronGateway.bookState();
        books.clear();
        bootstrap.levels().forEach(level -> adjustLevel(normalizeSymbol(level.symbol()), level.side(),
                level.priceTicks(), level.quantitySteps(), level.orderCount()));
        appliedExportSequence = bootstrap.exportSequence();
    }

    @KafkaListener(topics = "#{__listener.coreEventsTopic()}", groupId = "#{__listener.groupId()}",
            containerFactory = "matchingCoreEventsKafkaListenerContainerFactory")
    public void onEvents(List<ConsumerRecord<String, byte[]>> records) {
        if (records == null) return;
        for (ConsumerRecord<String, byte[]> record : records) {
            if (record.partition() != 0) {
                throw new IllegalStateException("Core events topic must have exactly one partition");
            }
            if (!coreEventsTopic().equals(record.topic())) {
                throw new IllegalStateException("unexpected Core events topic " + record.topic());
            }
            apply(CoreMessageCodec.decode(record.value()));
        }
    }

    synchronized void apply(CoreMessage message) {
        ProductLine productLine = properties.getKafka().getProductLine();
        if (message.header().productLine() != productLine) {
            throw new IllegalStateException("Core event product line mismatch");
        }
        CoreExportEvent event = CoreExportCodec.decodeEvent(message.payload());
        if (event.exportSequence() <= appliedExportSequence) return;
        long expectedSequence = Math.incrementExact(appliedExportSequence);
        if (event.exportSequence() != expectedSequence) {
            throw new IllegalStateException("non-contiguous Core events: expected="
                    + expectedSequence + " actual=" + event.exportSequence());
        }
        Map<Long, CoreOrderStateView> eventOrders = new HashMap<>();
        Set<String> changedSymbols = new LinkedHashSet<>();
        Map<LevelKey, Level> stagedLevels = new LinkedHashMap<>();
        event.changedOrders().forEach(order -> {
            eventOrders.put(order.orderId(), order);
            changedSymbols.add(normalizeSymbol(order.symbol()));
        });
        ExecutionChanges executionChanges = stageExecutions(event, eventOrders, stagedLevels,
                Instant.ofEpochMilli(message.header().submittedAtEpochMillis()));
        for (CoreOrderStateView order : event.changedOrders()) {
            if (executionChanges.makerOrderIds().contains(order.orderId())) continue;
            boolean createdByCommand = order.commandId().equals(event.commandId());
            if ("OPEN".equals(order.status())) {
                if (createdByCommand) {
                    stageLevel(stagedLevels, normalizeSymbol(order.symbol()), order.side(), order.priceTicks(),
                            order.remainingQuantitySteps(), 1);
                }
            } else if (!createdByCommand && order.remainingQuantitySteps() > 0) {
                stageLevel(stagedLevels, normalizeSymbol(order.symbol()), order.side(), order.priceTicks(),
                        Math.negateExact(order.remainingQuantitySteps()), -1);
            }
        }
        stagedLevels.forEach(this::commitLevel);
        appliedExportSequence = event.exportSequence();
        for (PublicTradeEvent trade : executionChanges.trades()) {
            latestTradeCache.put(trade);
            tradePublisher.offer(trade);
        }
        Instant eventTime = Instant.ofEpochMilli(message.header().submittedAtEpochMillis());
        for (String symbol : changedSymbols) {
            depthPublisher.offer(depthEvent(symbol, event.exportSequence(), eventTime,
                    properties.getMarketData().getDepthLevels()));
        }
    }

    public synchronized OrderBookSnapshotResponse snapshot(String symbol, int requestedDepth) {
        String normalized = normalizeSymbol(symbol);
        if (requestedDepth <= 0 || requestedDepth > 500) {
            throw new IllegalArgumentException("depth must be in [1, 500]");
        }
        MutableBook book = books.get(normalized);
        List<OrderBookLevel> bids = book == null ? List.of() : levels(book.bids, requestedDepth);
        List<OrderBookLevel> asks = book == null ? List.of() : levels(book.asks, requestedDepth);
        return new OrderBookSnapshotResponse(normalized, appliedExportSequence, requestedDepth,
                bids, asks, Instant.now());
    }

    public synchronized long appliedExportSequence() {
        return appliedExportSequence;
    }

    public String coreEventsTopic() {
        return properties.getKafka().getCoreEventsTopic();
    }

    public String groupId() {
        return properties.getKafka().getGroupId();
    }

    private ExecutionChanges stageExecutions(CoreExportEvent event, Map<Long, CoreOrderStateView> eventOrders,
                                             Map<LevelKey, Level> stagedLevels, Instant eventTime) {
        Set<Long> makerOrderIds = new HashSet<>();
        List<PublicTradeEvent> trades = new ArrayList<>(event.executions().size());
        for (int index = 0; index < event.executions().size(); index++) {
            var execution = event.executions().get(index);
            CoreOrderStateView taker = requireOrder(eventOrders, execution.takerOrderId());
            CoreOrderStateView maker = requireOrder(eventOrders, execution.makerOrderId());
            if (!taker.symbol().equals(maker.symbol())) {
                throw new IllegalStateException("execution order symbols differ");
            }
            makerOrderIds.add(maker.orderId());
            long countDelta = "OPEN".equals(maker.status()) ? 0 : -1;
            stageLevel(stagedLevels, normalizeSymbol(maker.symbol()), maker.side(), maker.priceTicks(),
                    Math.negateExact(execution.quantitySteps()), countDelta);
            long sequence = Math.addExact(Math.multiplyExact(event.exportSequence(), TRADE_SEQUENCE_MULTIPLIER), index);
            PublicTradeEvent trade = new PublicTradeEvent(event.commandId() + ":" + index, sequence,
                    normalizeSymbol(taker.symbol()), taker.instrumentVersion(), OrderSide.valueOf(taker.side().name()),
                    execution.priceTicks(), execution.quantitySteps(), eventTime, event.commandId().toString());
            trades.add(trade);
        }
        return new ExecutionChanges(Set.copyOf(makerOrderIds), List.copyOf(trades));
    }

    private void stageLevel(Map<LevelKey, Level> stagedLevels, String symbol, CoreOrderSide orderSide,
                            long priceTicks, long quantityDelta, long countDelta) {
        LevelKey key = new LevelKey(symbol, orderSide, priceTicks);
        Level current = stagedLevels.getOrDefault(key, currentLevel(key));
        long quantity = Math.addExact(current.quantitySteps(), quantityDelta);
        long count = Math.addExact(current.orderCount(), countDelta);
        if (quantity < 0 || count < 0 || (quantity == 0) != (count == 0)) {
            throw new IllegalStateException("Core book level delta is inconsistent");
        }
        stagedLevels.put(key, new Level(quantity, count));
    }

    private Level currentLevel(LevelKey key) {
        MutableBook book = books.get(key.symbol());
        if (book == null) return new Level(0, 0);
        Map<Long, Level> side = key.orderSide() == CoreOrderSide.BUY ? book.bids : book.asks;
        return side.getOrDefault(key.priceTicks(), new Level(0, 0));
    }

    private void commitLevel(LevelKey key, Level level) {
        MutableBook book = books.computeIfAbsent(key.symbol(), ignored -> new MutableBook());
        Map<Long, Level> side = key.orderSide() == CoreOrderSide.BUY ? book.bids : book.asks;
        if (level.orderCount() == 0) side.remove(key.priceTicks());
        else side.put(key.priceTicks(), level);
        if (book.bids.isEmpty() && book.asks.isEmpty()) books.remove(key.symbol());
    }

    private void adjustLevel(String symbol, CoreOrderSide orderSide, long priceTicks,
                             long quantityDelta, long countDelta) {
        MutableBook book = books.computeIfAbsent(symbol, ignored -> new MutableBook());
        Map<Long, Level> side = orderSide == CoreOrderSide.BUY ? book.bids : book.asks;
        Level current = side.getOrDefault(priceTicks, new Level(0, 0));
        long quantity = Math.addExact(current.quantitySteps(), quantityDelta);
        long count = Math.addExact(current.orderCount(), countDelta);
        if (quantity < 0 || count < 0 || (quantity == 0) != (count == 0)) {
            throw new IllegalStateException("Core book level delta is inconsistent");
        }
        if (count == 0) side.remove(priceTicks);
        else side.put(priceTicks, new Level(quantity, count));
        if (book.bids.isEmpty() && book.asks.isEmpty()) books.remove(symbol);
    }

    private OrderBookDepthEvent depthEvent(String symbol, long sequence, Instant eventTime, int depth) {
        MutableBook book = books.get(symbol);
        List<OrderBookLevel> bids = book == null ? List.of() : levels(book.bids, depth);
        List<OrderBookLevel> asks = book == null ? List.of() : levels(book.asks, depth);
        return new OrderBookDepthEvent(symbol, sequence, Math.max(0, sequence - 1),
                OrderBookDepthUpdateType.SNAPSHOT, depth, bids, asks, eventTime);
    }

    private static List<OrderBookLevel> levels(Map<Long, Level> source, int depth) {
        List<OrderBookLevel> levels = new ArrayList<>(Math.min(depth, source.size()));
        for (Map.Entry<Long, Level> entry : source.entrySet()) {
            if (levels.size() == depth) break;
            levels.add(new OrderBookLevel(entry.getKey(), entry.getValue().quantitySteps(),
                    entry.getValue().orderCount()));
        }
        return List.copyOf(levels);
    }

    private static CoreOrderStateView requireOrder(Map<Long, CoreOrderStateView> orders, long orderId) {
        CoreOrderStateView order = orders.get(orderId);
        if (order == null) throw new IllegalStateException("execution order metadata is missing from Core event");
        return order;
    }

    private static String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) throw new IllegalArgumentException("symbol is required");
        return symbol.trim().toUpperCase(Locale.ROOT);
    }

    private record Level(long quantitySteps, long orderCount) {
    }

    private record LevelKey(String symbol, CoreOrderSide orderSide, long priceTicks) {
    }

    private record ExecutionChanges(Set<Long> makerOrderIds, List<PublicTradeEvent> trades) {
    }

    private static final class MutableBook {
        private final TreeMap<Long, Level> bids = new TreeMap<>(java.util.Comparator.reverseOrder());
        private final TreeMap<Long, Level> asks = new TreeMap<>();
    }
}
