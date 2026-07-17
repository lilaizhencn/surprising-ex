package com.surprising.trading.trigger.service;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.TriggerCondition;
import com.surprising.trading.api.model.TriggerOrderStatus;
import com.surprising.trading.api.model.TriggerOrderType;
import com.surprising.trading.api.model.TriggerPriceType;
import com.surprising.trading.trigger.config.TriggerProperties;
import com.surprising.trading.trigger.model.TriggerOrderRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis sorted-set range index backed by Spring Data Redis and Lettuce.
 *
 * <p>Prices up to 2^53-1 are exact IEEE-754 integers and therefore exact Redis scores. If a product ever produces
 * a larger tick value, readiness is removed and the service deliberately falls back to PostgreSQL instead of
 * accepting an imprecise score that could miss a trigger.</p>
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

    public RedisTriggerOrderIndex(StringRedisTemplate redisTemplate, TriggerProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        validateConfiguration();
    }

    @Override
    public boolean enabled() {
        return properties.getRedisIndex().isEnabled();
    }

    @Override
    public void indexPlaced(TriggerOrderRecord order) {
        if (!enabled() || !isStaticOpenOrder(order)) {
            return;
        }
        try {
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
        if (!enabled() || !isStaticTrigger(order)) {
            return;
        }
        if (isOpen(order.status())) {
            put(order);
        } else {
            removeStrict(order.productLine(), order.symbol(), order.triggerPriceType(), order.triggerOrderId());
        }
    }

    @Override
    public void remove(TriggerOrderRecord order) {
        if (order == null || !enabled() || !isStaticTrigger(order)) {
            return;
        }
        remove(order.productLine(), order.symbol(), order.triggerPriceType(), order.triggerOrderId());
    }

    @Override
    public void remove(ProductLine productLine,
                       String symbol,
                       TriggerPriceType triggerPriceType,
                       long triggerOrderId) {
        if (!enabled()) {
            return;
        }
        try {
            removeStrict(productLine, symbol, triggerPriceType, triggerOrderId);
        } catch (RuntimeException ex) {
            // A stale member is safe: PostgreSQL will reject it during the exact conditional claim.
            log.warn("Failed to remove stale Redis trigger member id={}: {}", triggerOrderId, ex.getMessage());
        }
    }

    @Override
    public Optional<List<Long>> dueCandidates(ProductLine productLine,
                                              String symbol,
                                              TriggerPriceType triggerPriceType,
                                              long priceTicks,
                                              int limit) {
        if (!enabled()) {
            return Optional.empty();
        }
        try {
            if (!ready(productLine)) {
                return Optional.empty();
            }
            long score = exactScore(priceTicks);
            int normalizedLimit = Math.max(1, Math.min(limit, 2_000));
            @SuppressWarnings("unchecked")
            List<Object> values = redisTemplate.execute(
                    DUE_CANDIDATES,
                    List.of(rangeKey(productLine, symbol, triggerPriceType, "ge"),
                            rangeKey(productLine, symbol, triggerPriceType, "le")),
                    Long.toString(score), Integer.toString(normalizedLimit));
            if (values == null || values.isEmpty()) {
                return Optional.of(List.of());
            }
            List<Long> ids = new ArrayList<>(values.size());
            for (Object value : values) {
                ids.add(Long.parseLong(value.toString()));
            }
            return Optional.of(List.copyOf(ids));
        } catch (RuntimeException ex) {
            markNotReady(productLine);
            log.warn("Redis trigger candidate lookup failed; using PostgreSQL fallback line={} symbol={} type={}: {}",
                    productLine, symbol, triggerPriceType, ex.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public boolean ready(ProductLine productLine) {
        if (!enabled()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(readyKey(productLine)));
        } catch (RuntimeException ex) {
            return false;
        }
    }

    @Override
    public void markReady(ProductLine productLine) {
        if (!enabled()) {
            return;
        }
        redisTemplate.opsForValue().set(readyKey(productLine), "1", properties.getRedisIndex().getReadyTtl());
    }

    @Override
    public void markNotReady(ProductLine productLine) {
        if (!enabled()) {
            return;
        }
        try {
            redisTemplate.delete(readyKey(productLine));
        } catch (RuntimeException ignored) {
            // The next candidate lookup still falls back when Redis itself is unavailable.
        }
    }

    String rangeKey(ProductLine productLine,
                    String symbol,
                    TriggerPriceType triggerPriceType,
                    String condition) {
        String scope = productLine.name() + ":" + symbol + ":" + triggerPriceType.name();
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
                rangeKey(order.productLine(), order.symbol(), order.triggerPriceType(), condition),
                Long.toString(order.triggerOrderId()),
                exactScore(order.triggerPriceTicks()));
        if (indexed == null) {
            throw new IllegalStateException("Redis returned no result for trigger index write");
        }
    }

    private void removeStrict(ProductLine productLine,
                              String symbol,
                              TriggerPriceType triggerPriceType,
                              long triggerOrderId) {
        redisTemplate.execute(
                REMOVE_FROM_RANGE,
                List.of(rangeKey(productLine, symbol, triggerPriceType, "ge"),
                        rangeKey(productLine, symbol, triggerPriceType, "le")),
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
}
