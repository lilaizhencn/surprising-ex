package com.surprising.trading.order.service;

import com.surprising.account.api.model.OpenInterestShardSnapshot;
import com.surprising.account.api.model.OpenInterestShardUpdatedEvent;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * 订单模块的未平仓量 JVM 快照。
 *
 * <p>账户模块发布分片绝对值，订单模块按修订号幂等替换并在内存中聚合，
 * 下单保证金计算不再在线查询未平仓量视图。快照整体替换，读取线程不会看到半成品状态。</p>
 */
@Component
public class OpenInterestSnapshotCache {

    private final AtomicReference<State> state = new AtomicReference<>(
            new State(Map.of(), Map.of(), Set.of()));

    public void markNotReady(ProductLine productLine) {
        if (productLine == null) {
            return;
        }
        state.updateAndGet(previous -> {
            Map<ShardKey, ShardValue> shards = new HashMap<>(previous.shards());
            shards.keySet().removeIf(key -> key.productLine() == productLine);
            Map<SymbolKey, AggregateValue> aggregates = new HashMap<>(previous.aggregates());
            aggregates.keySet().removeIf(key -> key.productLine() == productLine);
            Set<ProductLine> readyLines = new HashSet<>(previous.readyLines());
            readyLines.remove(productLine);
            return immutableState(shards, aggregates, readyLines);
        });
    }

    /** 启动 RPC 返回后一次性重建当前产品线快照。 */
    public void replace(ProductLine productLine, List<OpenInterestShardSnapshot> snapshots) {
        if (productLine == null) {
            throw new IllegalArgumentException("未平仓量快照产品线不能为空");
        }
        state.updateAndGet(previous -> {
            Map<ShardKey, ShardValue> shards = new HashMap<>(previous.shards());
            shards.keySet().removeIf(key -> key.productLine() == productLine);
            Map<SymbolKey, AggregateValue> aggregates = new HashMap<>(previous.aggregates());
            aggregates.keySet().removeIf(key -> key.productLine() == productLine);
            if (snapshots != null) {
                for (OpenInterestShardSnapshot snapshot : snapshots) {
                    if (snapshot == null || snapshot.productLine() != productLine) {
                        throw new IllegalArgumentException("未平仓量快照产品线不匹配");
                    }
                    ShardKey key = new ShardKey(productLine, snapshot.symbol(), snapshot.shardId());
                    ShardValue value = new ShardValue(snapshot.longQuantitySteps(), snapshot.shortQuantitySteps(),
                            snapshot.revision(), snapshot.updatedAt());
                    shards.put(key, value);
                    addToAggregate(aggregates, new SymbolKey(productLine, snapshot.symbol()), null, value);
                }
            }
            Set<ProductLine> readyLines = new HashSet<>(previous.readyLines());
            readyLines.add(productLine);
            return immutableState(shards, aggregates, readyLines);
        });
    }

    /** Kafka 增量事件只允许覆盖更旧的分片修订。 */
    public void apply(OpenInterestShardUpdatedEvent event) {
        if (event == null) {
            return;
        }
        state.updateAndGet(previous -> {
            ShardKey key = new ShardKey(event.productLine(), event.symbol(), event.shardId());
            ShardValue oldValue = previous.shards().get(key);
            if (oldValue != null && event.revision() <= oldValue.revision()) {
                return previous;
            }
            ShardValue newValue = new ShardValue(event.longQuantitySteps(), event.shortQuantitySteps(),
                    event.revision(), event.eventTime());
            Map<ShardKey, ShardValue> shards = new HashMap<>(previous.shards());
            shards.put(key, newValue);
            Map<SymbolKey, AggregateValue> aggregates = new HashMap<>(previous.aggregates());
            addToAggregate(aggregates, new SymbolKey(event.productLine(), event.symbol()), oldValue, newValue);
            return immutableState(shards, aggregates, previous.readyLines());
        });
    }

    public boolean ready(ProductLine productLine) {
        return productLine != null && state.get().readyLines().contains(productLine);
    }

    public Optional<OpenInterestValue> lookup(ProductLine productLine, String symbol) {
        if (!ready(productLine) || symbol == null || symbol.isBlank()) {
            return Optional.empty();
        }
        AggregateValue value = state.get().aggregates().get(new SymbolKey(productLine, symbol));
        if (value == null) {
            return Optional.of(new OpenInterestValue(0L, 0L, 0L, 0L, null));
        }
        return Optional.of(new OpenInterestValue(value.longQuantitySteps(), value.shortQuantitySteps(),
                Math.max(value.longQuantitySteps(), value.shortQuantitySteps()), value.revision(), value.updatedAt()));
    }

    private static void addToAggregate(Map<SymbolKey, AggregateValue> aggregates,
                                       SymbolKey key,
                                       ShardValue previous,
                                       ShardValue next) {
        AggregateValue current = aggregates.get(key);
        long longQuantity = current == null ? 0L : current.longQuantitySteps();
        long shortQuantity = current == null ? 0L : current.shortQuantitySteps();
        if (previous != null) {
            longQuantity = Math.subtractExact(longQuantity, previous.longQuantitySteps());
            shortQuantity = Math.subtractExact(shortQuantity, previous.shortQuantitySteps());
        }
        longQuantity = Math.addExact(longQuantity, next.longQuantitySteps());
        shortQuantity = Math.addExact(shortQuantity, next.shortQuantitySteps());
        long revision = current == null ? next.revision() : Math.max(current.revision(), next.revision());
        Instant updatedAt = current == null || next.updatedAt().isAfter(current.updatedAt())
                ? next.updatedAt() : current.updatedAt();
        aggregates.put(key, new AggregateValue(longQuantity, shortQuantity, revision, updatedAt));
    }

    private static State immutableState(Map<ShardKey, ShardValue> shards,
                                        Map<SymbolKey, AggregateValue> aggregates,
                                        Set<ProductLine> readyLines) {
        return new State(Map.copyOf(shards), Map.copyOf(aggregates), Set.copyOf(readyLines));
    }

    public record OpenInterestValue(long longQuantitySteps,
                                    long shortQuantitySteps,
                                    long openQuantitySteps,
                                    long revision,
                                    Instant updatedAt) {
    }

    private record State(Map<ShardKey, ShardValue> shards,
                         Map<SymbolKey, AggregateValue> aggregates,
                         Set<ProductLine> readyLines) {
    }

    private record ShardKey(ProductLine productLine, String symbol, int shardId) {
        private ShardKey {
            symbol = normalize(symbol);
        }
    }

    private record SymbolKey(ProductLine productLine, String symbol) {
        private SymbolKey {
            symbol = normalize(symbol);
        }
    }

    private record ShardValue(long longQuantitySteps,
                              long shortQuantitySteps,
                              long revision,
                              Instant updatedAt) {
    }

    private record AggregateValue(long longQuantitySteps,
                                  long shortQuantitySteps,
                                  long revision,
                                  Instant updatedAt) {
    }

    private static String normalize(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase();
    }
}
