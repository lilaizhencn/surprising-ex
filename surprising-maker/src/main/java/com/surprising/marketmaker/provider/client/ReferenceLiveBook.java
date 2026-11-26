package com.surprising.marketmaker.provider.client;

import com.surprising.marketmaker.provider.model.ExternalTrade;
import com.surprising.marketmaker.provider.model.ReferenceOrderBookLevel;
import com.surprising.marketmaker.provider.model.ReferenceOrderBookSnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** 外部参考市场实时订单簿状态与成交记录带。 */
final class ReferenceLiveBook {

    private static final int MAX_QUEUED_PRINTS = 64;
    private static final int MAX_SEEN_TRADE_IDS = 4096;

    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final NavigableMap<Long, Long> bids = new TreeMap<>(Comparator.reverseOrder());
    private final NavigableMap<Long, Long> asks = new TreeMap<>();

    boolean isEmpty() {
        rwLock.readLock().lock();
        try {
            return bids.isEmpty() || asks.isEmpty();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    void replace(List<ReferenceOrderBookLevel> bidLevels,
                 List<ReferenceOrderBookLevel> askLevels) {
        rwLock.writeLock().lock();
        try {
            bids.clear();
            asks.clear();
            putAll(bids, bidLevels);
            putAll(asks, askLevels);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    void applyDeltas(List<ReferenceOrderBookLevel> bidLevels,
                     List<ReferenceOrderBookLevel> askLevels) {
        rwLock.writeLock().lock();
        try {
            applyDelta(bids, bidLevels);
            applyDelta(asks, askLevels);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    ReferenceOrderBookSnapshot snapshot(String source,
                                        String instrumentId,
                                        Instant receivedAt,
                                        int depthLevels) {
        rwLock.readLock().lock();
        try {
            List<ReferenceOrderBookLevel> bidLevels = topLevels(bids, depthLevels);
            List<ReferenceOrderBookLevel> askLevels = topLevels(asks, depthLevels);
            if (bidLevels.isEmpty() || askLevels.isEmpty()) {
                return null;
            }
            return new ReferenceOrderBookSnapshot(source, "WEBSOCKET", instrumentId, bidLevels, askLevels, receivedAt);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    private void putAll(NavigableMap<Long, Long> side, List<ReferenceOrderBookLevel> levels) {
        for (ReferenceOrderBookLevel level : levels) {
            if (level.priceTicks() > 0 && level.quantitySteps() > 0) {
                side.put(level.priceTicks(), level.quantitySteps());
            }
        }
    }

    private void applyDelta(NavigableMap<Long, Long> side, List<ReferenceOrderBookLevel> levels) {
        for (ReferenceOrderBookLevel level : levels) {
            if (level.priceTicks() <= 0) {
                continue;
            }
            if (level.quantitySteps() <= 0) {
                side.remove(level.priceTicks());
            } else {
                side.put(level.priceTicks(), level.quantitySteps());
            }
        }
    }

    private List<ReferenceOrderBookLevel> topLevels(NavigableMap<Long, Long> side, int depthLevels) {
        List<ReferenceOrderBookLevel> levels = new ArrayList<>();
        for (Map.Entry<Long, Long> entry : side.entrySet()) {
            if (levels.size() >= depthLevels) {
                break;
            }
            levels.add(new ReferenceOrderBookLevel(entry.getKey(), entry.getValue()));
        }
        return levels;
    }

    record ParsedBook(List<ParsedLevel> bids, List<ParsedLevel> asks) {
    }

    record ParsedBookUpdate(List<ParsedLevel> bids, List<ParsedLevel> asks, boolean delta) {
        boolean empty() {
            return bids.isEmpty() && asks.isEmpty();
        }
    }

    record ParsedLevel(BigDecimal price, BigDecimal quantity) {
    }

    static final class TradeTape {
        private final ArrayDeque<ExternalTrade> trades = new ArrayDeque<>();
        private final ArrayDeque<String> seenOrder = new ArrayDeque<>();
        private final Set<String> seen = new HashSet<>();

        void add(ExternalTrade trade) {
            synchronized (this) {
                if (!seen.add(trade.tradeId())) return;
                seenOrder.addLast(trade.tradeId());
                while (seenOrder.size() > MAX_SEEN_TRADE_IDS) seen.remove(seenOrder.removeFirst());
                if (trades.size() >= MAX_QUEUED_PRINTS) trades.removeFirst();
                trades.addLast(trade);
            }
        }

        List<ExternalTrade> drain(int limit) {
            synchronized (this) {
                List<ExternalTrade> drained = new ArrayList<>();
                while (drained.size() < limit && !trades.isEmpty()) drained.add(trades.removeFirst());
                return List.copyOf(drained);
            }
        }
    }
}
