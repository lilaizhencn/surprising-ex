package com.surprising.trading.trigger.service;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.TriggerCondition;
import com.surprising.trading.api.model.TriggerOrderStatus;
import com.surprising.trading.api.model.TriggerOrderType;
import com.surprising.trading.trigger.config.TriggerProperties;
import com.surprising.trading.trigger.model.TriggerOrderRecord;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentSkipListSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 基于 Spring Data Redis 和 Lettuce 的 Redis 有序集合区间索引。
 *
 * <p>不超过 2^53-1 的价格可以由 IEEE-754 精确表示，因此可作为精确的 Redis 分值。
 * 如果产品产生更大的价格刻度，服务会撤销索引就绪状态并回退到 PostgreSQL，
 * 避免因分值精度不足而漏触发。</p>
 */
@Component
public class RedisTriggerOrderIndex implements TriggerOrderIndex {

    private static final Logger log = LoggerFactory.getLogger(RedisTriggerOrderIndex.class);
    static final long MAX_EXACT_REDIS_SCORE = 9_007_199_254_740_991L;

    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> DUE_CANDIDATES = new DefaultRedisScript<>("""
            local result = {}
            local seen = {}
            local ge = redis.call('ZREVRANGEBYSCORE', KEYS[1], ARGV[1], '-inf', 'LIMIT', 0, ARGV[2])
            local le = redis.call('ZRANGEBYSCORE', KEYS[2], ARGV[1], '+inf', 'LIMIT', 0, ARGV[2])
            local max_results = tonumber(ARGV[2])
            local cursor = 1
            while #result < max_results and (ge[cursor] or le[cursor]) do
                local ge_member = ge[cursor]
                if ge_member and not seen[ge_member] then
                    table.insert(result, ge_member)
                    seen[ge_member] = true
                end
                local le_member = le[cursor]
                if #result < max_results and le_member and not seen[le_member] then
                    table.insert(result, le_member)
                    seen[le_member] = true
                end
                cursor = cursor + 1
            end
            return result
            """, List.class);

    private static final DefaultRedisScript<Long> REMOVE_FROM_RANGE = new DefaultRedisScript<>("""
            return redis.call('ZREM', KEYS[1], ARGV[1]) + redis.call('ZREM', KEYS[2], ARGV[1])
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final TriggerProperties properties;
    /** 本地一级索引减少同节点重复序列化和候选扫描；Redis 仍负责跨节点完整性。 */
    private final ConcurrentMap<LocalKey, ConcurrentMap<Long, TriggerOrderRecord>> localOrders =
            new ConcurrentHashMap<>();
    /** 按触发价维护有序桶，查询时只扫描命中价格区间，不再遍历全部订单。 */
    private final ConcurrentMap<LocalKey, LocalPriceIndex> localPriceIndexes = new ConcurrentHashMap<>();
    private final ConcurrentMap<ProductLine, Boolean> localReady = new ConcurrentHashMap<>();

    public RedisTriggerOrderIndex(StringRedisTemplate redisTemplate, TriggerProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        validateConfiguration();
    }

    @Override
    public void indexPlaced(TriggerOrderRecord order) {
        if (!isStaticOpenOrder(order)) {
            return;
        }
        try {
            putLocal(order);
            put(order);
        } catch (RuntimeException ex) {
            markNotReady(order.productLine());
            log.warn("Redis trigger index write failed; rejecting static trigger placement id={}: {}",
                    order.triggerOrderId(), ex.getMessage());
            throw new IllegalStateException("Redis trigger index write failed", ex);
        }
    }

    @Override
    public void synchronize(TriggerOrderRecord order) {
        if (!isStaticTrigger(order)) {
            return;
        }
        if (isOpen(order.status())) {
            putLocal(order);
            put(order);
        } else {
            removeLocal(order.productLine(), order.symbol(), order.triggerOrderId());
            removeStrict(order.productLine(), order.symbol(), order.triggerOrderId());
        }
    }

    @Override
    public void remove(TriggerOrderRecord order) {
        if (order == null || !isStaticTrigger(order)) {
            return;
        }
        remove(order.productLine(), order.symbol(), order.triggerOrderId());
    }

    @Override
    public void remove(ProductLine productLine,
                       String symbol,
                       long triggerOrderId) {
        removeLocal(productLine, symbol, triggerOrderId);
        try {
            removeStrict(productLine, symbol, triggerOrderId);
        } catch (RuntimeException ex) {
            // 陈旧索引成员是安全的：PostgreSQL 会在精确条件抢占时拒绝它。
            log.warn("Failed to remove stale Redis trigger member id={}: {}", triggerOrderId, ex.getMessage());
        }
    }

    @Override
    public Optional<List<Long>> dueCandidates(ProductLine productLine,
                                              String symbol,
                                              long priceTicks,
                                              int limit) {
        try {
            if (!ready(productLine)) {
                return Optional.empty();
            }
            long score = exactScore(priceTicks);
            int normalizedLimit = Math.max(1, Math.min(limit, 2_000));
            @SuppressWarnings("unchecked")
            List<Object> values = redisTemplate.execute(
                    DUE_CANDIDATES,
                    List.of(rangeKey(productLine, symbol, "ge"),
                            rangeKey(productLine, symbol, "le")),
                    Long.toString(score), Integer.toString(normalizedLimit));
            Set<Long> ids = new LinkedHashSet<>();
            if (values != null) {
                for (Object value : values) {
                    if (ids.size() >= normalizedLimit) {
                        break;
                    }
                    ids.add(Long.parseLong(value.toString()));
                }
            }
            // Redis 先提供跨节点完整候选，本地索引只补齐本节点尚未同步到 Redis 的候选。
            if (Boolean.TRUE.equals(localReady.get(productLine)) && ids.size() < normalizedLimit) {
                ids.addAll(localDueCandidates(productLine, symbol, priceTicks, normalizedLimit - ids.size()));
            }
            return Optional.of(List.copyOf(ids));
        } catch (RuntimeException ex) {
            markNotReady(productLine);
            log.warn("Redis trigger candidate lookup failed; using PostgreSQL fallback line={} symbol={}: {}",
                    productLine, symbol, ex.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public boolean ready(ProductLine productLine) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(readyKey(productLine)));
        } catch (RuntimeException ex) {
            return false;
        }
    }

    @Override
    public void markReady(ProductLine productLine) {
        localReady.put(productLine, Boolean.TRUE);
        redisTemplate.opsForValue().set(readyKey(productLine), "1", properties.getRedisIndex().getReadyTtl());
    }

    @Override
    public void markNotReady(ProductLine productLine) {
        localReady.remove(productLine);
        try {
            redisTemplate.delete(readyKey(productLine));
        } catch (RuntimeException ignored) {
            // Redis 本身不可用时，下一次候选查询仍会回退到 PostgreSQL。
        }
    }

    String rangeKey(ProductLine productLine,
                    String symbol,
                    String condition) {
        String scope = productLine.name() + ":" + symbol;
        return keyPrefix() + ":range:{" + scope + "}:" + condition;
    }

    String readyKey(ProductLine productLine) {
        return keyPrefix() + ":ready:" + productLine.name();
    }

    long exactScore(long priceTicks) {
        if (priceTicks < 0 || priceTicks > MAX_EXACT_REDIS_SCORE) {
            throw new IllegalArgumentException(
                    "priceTicks cannot be represented exactly as a Redis score: " + priceTicks);
        }
        return priceTicks;
    }

    private void put(TriggerOrderRecord order) {
        String condition = order.triggerCondition() == TriggerCondition.GREATER_OR_EQUAL ? "ge" : "le";
        Boolean indexed = redisTemplate.opsForZSet().add(
                rangeKey(order.productLine(), order.symbol(), condition),
                Long.toString(order.triggerOrderId()),
                exactScore(order.triggerPriceTicks()));
        if (indexed == null) {
            throw new IllegalStateException("Redis returned no result for trigger index write");
        }
    }

    private void putLocal(TriggerOrderRecord order) {
        LocalKey key = new LocalKey(order.productLine(), order.symbol());
        ConcurrentMap<Long, TriggerOrderRecord> orders = localOrders.computeIfAbsent(
                key, ignored -> new ConcurrentHashMap<>());
        LocalPriceIndex priceIndex = localPriceIndexes.computeIfAbsent(key, ignored -> new LocalPriceIndex());
        orders.compute(order.triggerOrderId(), (ignored, previous) -> {
            if (previous != null) {
                priceIndex.remove(previous);
            }
            priceIndex.put(order);
            return order;
        });
    }

    private void removeLocal(ProductLine productLine, String symbol, long triggerOrderId) {
        LocalKey key = new LocalKey(productLine, symbol);
        ConcurrentMap<Long, TriggerOrderRecord> orders = localOrders.get(key);
        if (orders == null) {
            return;
        }
        TriggerOrderRecord previous = orders.remove(triggerOrderId);
        if (previous != null) {
            LocalPriceIndex priceIndex = localPriceIndexes.get(key);
            if (priceIndex != null) {
                priceIndex.remove(previous);
                if (priceIndex.isEmpty()) {
                    localPriceIndexes.remove(key, priceIndex);
                }
            }
        }
        if (orders.isEmpty()) {
            localOrders.remove(key, orders);
        }
    }

    private List<Long> localDueCandidates(ProductLine productLine,
                                          String symbol,
                                          long priceTicks,
                                          int limit) {
        ConcurrentMap<Long, TriggerOrderRecord> orders = localOrders.get(new LocalKey(productLine, symbol));
        if (orders == null || orders.isEmpty()) {
            return List.of();
        }
        LocalPriceIndex priceIndex = localPriceIndexes.get(new LocalKey(productLine, symbol));
        if (priceIndex == null) {
            return List.of();
        }
        int scanLimit = Math.max(limit, Math.min(limit * 4, 8_000));
        return priceIndex.dueIds(priceTicks, scanLimit).stream()
                .map(orders::get)
                .filter(order -> order != null && isOpen(order.status()))
                .sorted((left, right) -> Long.compare(left.triggerOrderId(), right.triggerOrderId()))
                .limit(limit)
                .map(TriggerOrderRecord::triggerOrderId)
                .toList();
    }

    private void removeStrict(ProductLine productLine,
                              String symbol,
                              long triggerOrderId) {
        redisTemplate.execute(
                REMOVE_FROM_RANGE,
                List.of(rangeKey(productLine, symbol, "ge"),
                        rangeKey(productLine, symbol, "le")),
                Long.toString(triggerOrderId));
    }

    private boolean isStaticOpenOrder(TriggerOrderRecord order) {
        return isStaticTrigger(order) && isOpen(order.status());
    }

    private boolean isStaticTrigger(TriggerOrderRecord order) {
        return order.triggerType() == TriggerOrderType.TAKE_PROFIT
                || order.triggerType() == TriggerOrderType.STOP_LOSS;
    }

    private boolean isOpen(TriggerOrderStatus status) {
        return status == TriggerOrderStatus.PENDING || status == TriggerOrderStatus.TRIGGERING;
    }

    private String keyPrefix() {
        String configured = properties.getRedisIndex().getKeyPrefix();
        return configured == null || configured.isBlank() ? "surprising:trigger:v1" : configured.trim();
    }

    private void validateConfiguration() {
        if (properties.getRedisIndex().getReadyTtl() == null
                || properties.getRedisIndex().getReadyTtl().isZero()
                || properties.getRedisIndex().getReadyTtl().isNegative()) {
            throw new IllegalArgumentException("trigger Redis readyTtl must be positive");
        }
        if (properties.getRedisIndex().getLockTtl() == null
                || properties.getRedisIndex().getLockTtl().isZero()
                || properties.getRedisIndex().getLockTtl().isNegative()) {
            throw new IllegalArgumentException("trigger Redis lockTtl must be positive");
        }
    }

    private record LocalKey(ProductLine productLine, String symbol) {
    }

    /** 单个产品线和交易对的触发价有序索引。 */
    private static final class LocalPriceIndex {

        private final ConcurrentSkipListMap<Long, Set<Long>> greaterOrEqual = new ConcurrentSkipListMap<>();
        private final ConcurrentSkipListMap<Long, Set<Long>> lessOrEqual = new ConcurrentSkipListMap<>();

        private void put(TriggerOrderRecord order) {
            ConcurrentSkipListMap<Long, Set<Long>> index = index(order.triggerCondition());
            index.computeIfAbsent(order.triggerPriceTicks(), ignored -> new ConcurrentSkipListSet<>())
                    .add(order.triggerOrderId());
        }

        private void remove(TriggerOrderRecord order) {
            ConcurrentSkipListMap<Long, Set<Long>> index = index(order.triggerCondition());
            Set<Long> ids = index.get(order.triggerPriceTicks());
            if (ids != null && ids.remove(order.triggerOrderId()) && ids.isEmpty()) {
                index.remove(order.triggerPriceTicks(), ids);
            }
        }

        private List<Long> dueIds(long priceTicks, int limit) {
            Set<Long> result = new LinkedHashSet<>();
            addRange(result, greaterOrEqual.headMap(priceTicks, true).descendingMap(), limit);
            if (result.size() < limit) {
                addRange(result, lessOrEqual.tailMap(priceTicks, true), limit);
            }
            return new ArrayList<>(result);
        }

        private void addRange(Set<Long> result,
                              Map<Long, Set<Long>> buckets,
                              int limit) {
            for (Set<Long> ids : buckets.values()) {
                for (Long id : ids) {
                    result.add(id);
                    if (result.size() >= limit) {
                        return;
                    }
                }
            }
        }

        private boolean isEmpty() {
            return greaterOrEqual.isEmpty() && lessOrEqual.isEmpty();
        }

        private ConcurrentSkipListMap<Long, Set<Long>> index(TriggerCondition condition) {
            return condition == TriggerCondition.GREATER_OR_EQUAL ? greaterOrEqual : lessOrEqual;
        }
    }
}
