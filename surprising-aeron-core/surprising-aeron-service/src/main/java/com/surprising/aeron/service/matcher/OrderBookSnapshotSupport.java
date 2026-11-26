package com.surprising.aeron.service.matcher;

import com.surprising.aeron.exception.FatalMatchingDivergenceException;
import com.surprising.aeron.model.CoreOrderState;
import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.protocol.CoreBookLevelView;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.service.snapshot.state.TradingCoreState;
import exchange.core2.core.SynchronousMatchingEngine;
import exchange.core2.core.common.L2MarketData;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.api.reports.StateHashReportResult;
import exchange.core2.core.processors.journaling.InMemorySerializationProcessor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/** 订单簿快照捕获、活动订单对账、状态哈希折叠与 L2 盘口读取协作器。 */
final class OrderBookSnapshotSupport {
    private final OrderBookEngine engine;

    OrderBookSnapshotSupport(OrderBookEngine engine) {
        this.engine = engine;
    }

    OrderBookEngine.MatcherShardSnapshot captureShardSnapshot(
            int shardId, long snapshotId, long coreSequence, Iterable<CoreOrderState> activeOrders) {
        if (shardId < 0 || shardId >= engine.topology().matchingEngineCount() || snapshotId <= 0
                || coreSequence < 0 || activeOrders == null) {
            throw new IllegalArgumentException("invalid matcher shard snapshot request");
        }
        reconcileOpenOrdersShard(activeOrders, shardId, coreSequence, snapshotId, "matcher snapshot");
        int engineHash = engineStateHash(shardId);
        if (!engine.engines[shardId].storeSnapshot(snapshotId, coreSequence, coreSequence)) {
            throw new FatalMatchingDivergenceException("matcher snapshot", coreSequence, snapshotId,
                    "exchange-core persist failed for shard " + shardId);
        }
        return new OrderBookEngine.MatcherShardSnapshot(shardId, engineHash);
    }

    OrderBookSnapshot finishShardedSnapshot(
            long snapshotId, long coreSequence, long businessStateHash, TradingCoreState state,
            List<OrderBookEngine.MatcherShardSnapshot> shardSnapshots) {
        if (snapshotId <= 0 || coreSequence < 0 || businessStateHash == 0 || state == null
                || shardSnapshots == null || shardSnapshots.size() != engine.topology().matchingEngineCount()) {
            throw new IllegalArgumentException("incomplete matcher shard snapshot set");
        }
        int engineHash = aggregateEngineStateHash(shardSnapshots);
        try {
            List<InMemorySerializationProcessor.SerializedModule> modules =
                    engine.serializationProcessor.exportSnapshot(snapshotId);
            long matcherSequence = maxMatcherSequence(modules);
            Set<Long> userSnapshot = engine.snapshotUsers();
            return new OrderBookSnapshot(state.productLine(), engine.topology(), snapshotId, coreSequence,
                    matcherSequence, engine.matcherEvidence.snapshot(), businessStateHash, engineHash,
                    engine.symbols, userSnapshot, modules);
        } finally {
            engine.serializationProcessor.removeSnapshot(snapshotId);
        }
    }

    void reconcileOpenOrders(Iterable<CoreOrderState> activeOrders, long coreSequence,
                             long snapshotId, String operation) {
        Map<Long, ReconciledOrder> expected = new HashMap<>();
        for (CoreOrderState order : activeOrders) {
            if (order.status() != CoreOrderStatus.OPEN) {
                throw new FatalMatchingDivergenceException(operation, coreSequence, snapshotId,
                        "active-order index contains a terminal order");
            }
            Integer symbolId = engine.symbols.get(order.instrumentId());
            if (symbolId == null) {
                throw new FatalMatchingDivergenceException(operation, coreSequence, snapshotId,
                        "Core open order references an unregistered matcher instrumentId");
            }
            if (expected.put(order.orderId(), new ReconciledOrder(symbolId, order.orderId(), order.userId(),
                    order.side() == CoreOrderSide.BUY ? OrderAction.BID : OrderAction.ASK,
                    order.matchingPriceTicks(), order.quantitySteps(), order.executedQuantitySteps(),
                    order.matchingPriceTicks())) != null) {
                throw new FatalMatchingDivergenceException(operation, coreSequence, snapshotId,
                        "active-order index contains duplicate order ID " + order.orderId());
            }
        }
        Map<Long, ReconciledOrder> actual = new HashMap<>();
        for (SynchronousMatchingEngine matcher : engine.engines) {
            for (SynchronousMatchingEngine.OpenOrder order : matcher.openOrders()) {
                ReconciledOrder reconciled = new ReconciledOrder(order.symbolId(), order.orderId(), order.uid(),
                        order.action(), order.price(), order.size(), order.filled(), order.reserveBidPrice());
                if (actual.put(order.orderId(), reconciled) != null) {
                    throw new FatalMatchingDivergenceException(operation, coreSequence, snapshotId,
                            "exchange-core returned duplicate open order ID " + order.orderId());
                }
            }
        }
        if (!expected.equals(actual)) {
            throw new FatalMatchingDivergenceException(operation, coreSequence, snapshotId,
                    "Core OPEN orders do not exactly match exchange-core open orders"
                            + " (expected=" + expected.size() + ", actual=" + actual.size() + ')');
        }
    }

    void reconcileOpenOrdersShard(Iterable<CoreOrderState> activeOrders, int shardId,
                                  long coreSequence, long snapshotId, String operation) {
        Map<Long, ReconciledOrder> expected = new HashMap<>();
        for (CoreOrderState order : activeOrders) {
            Integer symbolId = engine.symbols.get(order.instrumentId());
            if (symbolId == null || engine.topology().matcherShardId(symbolId) != shardId) continue;
            if (order.status() != CoreOrderStatus.OPEN) {
                throw new FatalMatchingDivergenceException(operation, coreSequence, snapshotId,
                        "active-order index contains a terminal order");
            }
            ReconciledOrder value = new ReconciledOrder(symbolId, order.orderId(), order.userId(),
                    order.side() == CoreOrderSide.BUY ? OrderAction.BID : OrderAction.ASK,
                    order.matchingPriceTicks(), order.quantitySteps(), order.executedQuantitySteps(),
                    order.matchingPriceTicks());
            if (expected.put(order.orderId(), value) != null) {
                throw new FatalMatchingDivergenceException(operation, coreSequence, snapshotId,
                        "active-order index contains duplicate order ID " + order.orderId());
            }
        }
        Map<Long, ReconciledOrder> actual = new HashMap<>();
        for (SynchronousMatchingEngine.OpenOrder order : engine.engines[shardId].openOrders()) {
            ReconciledOrder value = new ReconciledOrder(order.symbolId(), order.orderId(), order.uid(),
                    order.action(), order.price(), order.size(), order.filled(), order.reserveBidPrice());
            if (actual.put(order.orderId(), value) != null) {
                throw new FatalMatchingDivergenceException(operation, coreSequence, snapshotId,
                        "exchange-core returned duplicate open order ID " + order.orderId());
            }
        }
        if (!expected.equals(actual)) {
            throw new FatalMatchingDivergenceException(operation, coreSequence, snapshotId,
                    "Core OPEN orders do not exactly match exchange-core shard " + shardId
                            + " (expected=" + expected.size() + ", actual=" + actual.size() + ')');
        }
    }

    int currentEngineStateHash() {
        if (engine.engines.length == 1) return engineStateHash(0);
        int engineHash = 1;
        for (int shardId = 0; shardId < engine.engines.length; shardId++) {
            engineHash = (engineHash * 31 + shardId) * 31 + engineStateHash(shardId);
        }
        return engineHash;
    }

    int engineStateHash(int shardId) {
        var report = engine.engines[shardId].stateHashReport();
        int engineHash = 1;
        for (Map.Entry<StateHashReportResult.SubmoduleKey, Integer> entry : report.getHashCodes().entrySet()) {
            engineHash = engineHash * 31 + entry.getKey().submodule.code;
            engineHash = engineHash * 31 + entry.getKey().moduleId;
            engineHash = engineHash * 31 + entry.getValue();
        }
        return engineHash;
    }

    static int aggregateEngineStateHash(List<OrderBookEngine.MatcherShardSnapshot> shards) {
        if (shards.size() == 1) return shards.getFirst().engineHash();
        int engineHash = 1;
        for (int shardId = 0; shardId < shards.size(); shardId++) {
            OrderBookEngine.MatcherShardSnapshot shard = shards.get(shardId);
            if (shard.shardId() != shardId) throw new IllegalArgumentException("matcher shards are not ordered");
            engineHash = (engineHash * 31 + shardId) * 31 + shard.engineHash();
        }
        return engineHash;
    }

    List<CoreBookLevelView> orderBookLevels(String requestedSymbol, int depth) {
        if (requestedSymbol == null || requestedSymbol.isBlank() || depth < 1 || depth > 100) {
            throw new IllegalArgumentException("invalid single-instrumentId book query");
        }
        String instrumentId = requestedSymbol.trim().toUpperCase(Locale.ROOT);
        Integer symbolId = engine.symbols.get(instrumentId);
        if (symbolId == null) return List.of();
        return bookLevels(instrumentId, engine.engine(symbolId).orderBook(symbolId, depth));
    }

    OrderBookDepthSnapshot orderBookBootstrapShard(int shardId, int depth) {
        if (shardId < 0 || shardId >= engine.topology().matchingEngineCount() || depth < 1 || depth > 100) {
            throw new IllegalArgumentException("invalid matcher shard bootstrap query");
        }
        List<Map.Entry<String, Integer>> entries = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : engine.symbols.entrySet()) {
            if (engine.topology().matcherShardId(entry.getValue()) == shardId) {
                entries.add(entry);
            }
        }
        entries.sort(Map.Entry.comparingByKey());
        ArrayList<CoreBookLevelView> levels = new ArrayList<>();
        List<String> entrySymbols = new ArrayList<>(entries.size());
        for (Map.Entry<String, Integer> entry : entries) {
            entrySymbols.add(entry.getKey());
            levels.addAll(bookLevels(entry.getKey(), engine.engines[shardId].orderBook(entry.getValue(), depth)));
        }
        return new OrderBookDepthSnapshot(entrySymbols, levels);
    }

    OrderBookDepthSnapshot mergeBookBootstrapShards(List<OrderBookDepthSnapshot> shards) {
        if (shards == null || shards.size() != engine.topology().matchingEngineCount()) {
            throw new IllegalArgumentException("incomplete matcher bootstrap shards");
        }
        ArrayList<String> mergedSymbols = new ArrayList<>();
        ArrayList<CoreBookLevelView> mergedLevels = new ArrayList<>();
        for (OrderBookDepthSnapshot shard : shards) {
            mergedSymbols.addAll(shard.symbols());
            mergedLevels.addAll(shard.levels());
        }
        mergedSymbols.sort(String::compareTo);
        mergedLevels.sort(Comparator.comparing(CoreBookLevelView::instrumentId)
                .thenComparing(CoreBookLevelView::side)
                .thenComparingLong(CoreBookLevelView::priceTicks));
        return new OrderBookDepthSnapshot(mergedSymbols, mergedLevels);
    }

    private static List<CoreBookLevelView> bookLevels(String instrumentId, L2MarketData book) {
        List<CoreBookLevelView> levels = new ArrayList<>(Math.addExact(book.askSize, book.bidSize));
        for (int index = 0; index < book.askSize; index++) {
            levels.add(new CoreBookLevelView(instrumentId, CoreOrderSide.SELL, book.askPrices[index],
                    book.askVolumes[index], book.askOrders[index]));
        }
        for (int index = 0; index < book.bidSize; index++) {
            levels.add(new CoreBookLevelView(instrumentId, CoreOrderSide.BUY, book.bidPrices[index],
                    book.bidVolumes[index], book.bidOrders[index]));
        }
        levels.sort(Comparator.comparing(CoreBookLevelView::side)
                .thenComparingLong(CoreBookLevelView::priceTicks));
        return levels;
    }

    private static long maxMatcherSequence(List<InMemorySerializationProcessor.SerializedModule> modules) {
        if (modules.isEmpty()) throw new NoSuchElementException("modules is empty");
        long max = Long.MIN_VALUE;
        for (int i = 0; i < modules.size(); i++) {
            long seq = modules.get(i).sequence();
            if (seq > max) {
                max = seq;
            }
        }
        return max;
    }

    private record ReconciledOrder(
            int symbolId, long orderId, long userId, OrderAction action,
            long price, long size, long filled, long reserveBidPrice) {
    }
}
