package com.surprising.trading.order.service;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.order.model.OrderRecord;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Redis read model for a user's active orders.  PostgreSQL remains authoritative: an absent or
 * incomplete projection always makes callers fall back to PostgreSQL rather than return a partial list.
 */
@Component
public class RedisOpenOrderView {
    private static final Duration ENTRY_TTL = Duration.ofDays(1);
    private static final DefaultRedisScript<Long> SYNCHRONIZE = new DefaultRedisScript<>("""
            local current = tonumber(redis.call('GET', KEYS[4]) or '-1')
            if tonumber(ARGV[5]) < current then return 0 end
            redis.call('SET', KEYS[4], ARGV[5], 'PX', ARGV[6])
            if ARGV[1] == '1' then
              redis.call('SET', KEYS[3], ARGV[2], 'PX', ARGV[6])
              redis.call('ZADD', KEYS[1], ARGV[3], ARGV[4])
              redis.call('PEXPIRE', KEYS[1], ARGV[6])
              redis.call('ZADD', KEYS[2], ARGV[3], ARGV[4])
              redis.call('PEXPIRE', KEYS[2], ARGV[6])
            else
              redis.call('ZREM', KEYS[1], ARGV[4])
              redis.call('ZREM', KEYS[2], ARGV[4])
              redis.call('DEL', KEYS[3])
            end
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public RedisOpenOrderView(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    public void startRebuild(ProductLine line) {
        redis.delete(readyKey(line));
        redis.opsForValue().set(generationKey(line), UUID.randomUUID().toString());
    }

    public void synchronize(OrderRecord order) {
        try {
            String generation = redis.opsForValue().get(generationKey(order.productLine()));
            if (generation == null || generation.isBlank()) {
                return;
            }
            String orderId = Long.toString(order.orderId());
            redis.execute(SYNCHRONIZE,
                    List.of(allIndexKey(order.productLine(), order.userId(), generation),
                            symbolIndexKey(order.productLine(), order.userId(), generation, order.symbol()),
                            valueKey(order.productLine(), order.userId(), generation, order.orderId()),
                            versionKey(order.productLine(), order.userId(), generation, order.orderId())),
                    open(order) ? "1" : "0",
                    open(order) ? mapper.writeValueAsString(order) : "",
                    Long.toString(order.createdAt().toEpochMilli()), orderId,
                    Long.toString(order.updatedAt().toEpochMilli()), Long.toString(ENTRY_TTL.toMillis()));
        } catch (Exception ignored) {
            // Redis is an optional read path; callers safely fall back to PostgreSQL.
        }
    }

    public Optional<List<OrderRecord>> orders(ProductLine line, long userId, String symbol, int limit) {
        try {
            String generation = redis.opsForValue().get(generationKey(line));
            String readyGeneration = redis.opsForValue().get(readyKey(line));
            if (generation == null || !generation.equals(readyGeneration)) {
                return Optional.empty();
            }
            String index = symbol == null ? allIndexKey(line, userId, generation)
                    : symbolIndexKey(line, userId, generation, symbol);
            var ids = redis.opsForZSet().reverseRange(index, 0, limit - 1L);
            if (ids == null || ids.isEmpty()) {
                return Optional.of(List.of());
            }
            List<String> values = ids.stream()
                    .map(id -> redis.opsForValue().get(valueKey(line, userId, generation, Long.parseLong(id))))
                    .toList();
            if (values.stream().anyMatch(java.util.Objects::isNull)) {
                return Optional.empty();
            }
            List<OrderRecord> rows = values.stream().map(this::read).toList();
            if (rows.stream().anyMatch(java.util.Objects::isNull)) {
                return Optional.empty();
            }
            return Optional.of(rows);
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    public boolean ready(ProductLine line) {
        try {
            String generation = redis.opsForValue().get(generationKey(line));
            return generation != null && generation.equals(redis.opsForValue().get(readyKey(line)));
        } catch (RuntimeException ex) {
            return false;
        }
    }

    public void markReady(ProductLine line, Duration ttl) {
        String generation = redis.opsForValue().get(generationKey(line));
        if (generation == null || generation.isBlank()) {
            throw new IllegalStateException("open order view has no rebuild generation");
        }
        redis.opsForValue().set(readyKey(line), generation, ttl);
    }

    public void markNotReady(ProductLine line) {
        redis.delete(readyKey(line));
    }

    private OrderRecord read(String value) {
        try {
            return mapper.readValue(value, OrderRecord.class);
        } catch (Exception ex) {
            return null;
        }
    }

    private boolean open(OrderRecord order) {
        return order.status().name().equals("ACCEPTED")
                || order.status().name().equals("PARTIALLY_FILLED")
                || order.status().name().equals("CANCEL_REQUESTED");
    }

    private String tag(ProductLine line, long userId, String generation) {
        return "{" + line.name() + ":" + userId + ":" + generation + "}";
    }

    private String allIndexKey(ProductLine line, long userId, String generation) {
        return "surprising:order:v1:open:" + tag(line, userId, generation) + ":all";
    }

    private String symbolIndexKey(ProductLine line, long userId, String generation, String symbol) {
        return "surprising:order:v1:open:" + tag(line, userId, generation) + ":symbol:" + symbol;
    }

    private String valueKey(ProductLine line, long userId, String generation, long orderId) {
        return "surprising:order:v1:open:" + tag(line, userId, generation) + ":order:" + orderId;
    }

    private String versionKey(ProductLine line, long userId, String generation, long orderId) {
        return "surprising:order:v1:open:" + tag(line, userId, generation) + ":version:" + orderId;
    }

    private String generationKey(ProductLine line) {
        return "surprising:order:v1:open:generation:" + line.name();
    }

    private String readyKey(ProductLine line) {
        return "surprising:order:v1:open:ready:" + line.name();
    }
}
