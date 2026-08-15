package com.surprising.account.api.cache;

import com.surprising.account.api.model.PositionUpdatedEvent;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 按产品线隔离的本地持仓快照。
 *
 * <p>该类只负责消费已经提交的持仓事件，不负责资金计算和数据库写入。相同用户的事件由 Kafka
 * 用户键保证顺序；这里按用户串行更新并按精确持仓键校验 revision，防止重放过期消息回退状态。
 * 数量为零的仓位会保留，避免延迟消息把已经平掉的仓位重新打开。</p>
 */
public final class PositionSnapshotCache {

    private final ProductLine productLine;
    private final ConcurrentHashMap<PositionKey, PositionUpdatedEvent> positions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> userRevisions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Object> userLocks = new ConcurrentHashMap<>();
    private final AtomicBoolean ready = new AtomicBoolean();

    public PositionSnapshotCache(ProductLine productLine) {
        if (productLine == null) {
            throw new IllegalArgumentException("productLine is required");
        }
        this.productLine = productLine;
    }

    public ApplyResult apply(PositionUpdatedEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("position event is required");
        }
        if (event.productLine() != productLine) {
            return ApplyResult.PRODUCT_LINE_MISMATCH;
        }
        PositionKey key = PositionKey.from(event);
        Object lock = userLocks.computeIfAbsent(event.userId(), ignored -> new Object());
        synchronized (lock) {
            long lastUserRevision = userRevisions.getOrDefault(event.userId(), 0L);
            PositionUpdatedEvent previous = positions.get(key);
            long previousRevision = previous == null ? 0L : previous.revision();
            if (event.revision() < lastUserRevision) {
                return ApplyResult.STALE;
            }
            if (event.revision() < previousRevision) {
                return ApplyResult.STALE;
            }
            if (event.revision() == previousRevision && previous != null) {
                return sameState(previous, event) ? ApplyResult.STALE : ApplyResult.CONFLICT;
            }
            positions.put(key, event);
            if (event.revision() > lastUserRevision) {
                userRevisions.put(event.userId(), event.revision());
            }
            return ApplyResult.APPLIED;
        }
    }

    public Optional<PositionUpdatedEvent> position(long userId,
                                                   String symbol,
                                                   MarginMode marginMode,
                                                   PositionSide positionSide) {
        if (userId <= 0L) {
            throw new IllegalArgumentException("userId must be positive");
        }
        PositionKey key = new PositionKey(userId, normalizeSymbol(symbol),
                MarginMode.defaultIfNull(marginMode), PositionSide.defaultIfNull(positionSide));
        return Optional.ofNullable(positions.get(key));
    }

    /** 返回用户当前非零仓位；结果是独立列表，调用方不能修改缓存内部状态。 */
    public List<PositionUpdatedEvent> openPositions(long userId) {
        if (userId <= 0L) {
            throw new IllegalArgumentException("userId must be positive");
        }
        List<PositionUpdatedEvent> result = new ArrayList<>();
        positions.forEach((key, event) -> {
            if (key.userId() == userId && event.signedQuantitySteps() != 0L) {
                result.add(event);
            }
        });
        result.sort(Comparator.comparing(PositionUpdatedEvent::symbol)
                .thenComparing(event -> event.marginMode().name())
                .thenComparing(event -> event.positionSide().name()));
        return List.copyOf(result);
    }

    public OptionalLong userRevision(long userId) {
        if (userId <= 0L) {
            throw new IllegalArgumentException("userId must be positive");
        }
        Long revision = userRevisions.get(userId);
        return revision == null ? OptionalLong.empty() : OptionalLong.of(revision);
    }

    public Map<PositionKey, PositionUpdatedEvent> snapshot() {
        return Map.copyOf(positions);
    }

    public ProductLine productLine() {
        return productLine;
    }

    public boolean ready() {
        return ready.get();
    }

    public void markReady() {
        ready.set(true);
    }

    public void markNotReady() {
        ready.set(false);
    }

    public void clear() {
        positions.clear();
        userRevisions.clear();
        userLocks.clear();
        ready.set(false);
    }

    private static String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol is required");
        }
        return symbol.trim().toUpperCase();
    }

    public enum ApplyResult {
        APPLIED,
        STALE,
        PRODUCT_LINE_MISMATCH,
        CONFLICT
    }

    private boolean sameState(PositionUpdatedEvent left, PositionUpdatedEvent right) {
        return left.productLine() == right.productLine()
                && left.userId() == right.userId()
                && left.symbol().equalsIgnoreCase(right.symbol())
                && left.instrumentVersion() == right.instrumentVersion()
                && left.marginMode() == right.marginMode()
                && left.positionSide() == right.positionSide()
                && left.signedQuantitySteps() == right.signedQuantitySteps()
                && left.entryPriceTicks() == right.entryPriceTicks()
                && left.entryValueTicks() == right.entryValueTicks()
                && left.realizedPnlUnits() == right.realizedPnlUnits()
                && Objects.equals(left.marginAsset(), right.marginAsset())
                && left.marginUnits() == right.marginUnits()
                && Objects.equals(left.positionUpdatedAt(), right.positionUpdatedAt())
                && Objects.equals(left.marginUpdatedAt(), right.marginUpdatedAt());
    }

    public record PositionKey(long userId, String symbol, MarginMode marginMode, PositionSide positionSide) {
        public PositionKey {
            if (userId <= 0L) {
                throw new IllegalArgumentException("userId must be positive");
            }
            symbol = normalizeSymbol(symbol);
            marginMode = MarginMode.defaultIfNull(marginMode);
            positionSide = PositionSide.defaultIfNull(positionSide);
        }

        private static PositionKey from(PositionUpdatedEvent event) {
            return new PositionKey(event.userId(), event.symbol(), event.marginMode(), event.positionSide());
        }
    }

}
