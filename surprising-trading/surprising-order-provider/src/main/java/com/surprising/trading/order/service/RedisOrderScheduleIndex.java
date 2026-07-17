package com.surprising.trading.order.service;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.model.AlgoOrderRecord;
import com.surprising.trading.order.model.CancelAllAfterTimer;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Redis ZSET implementation. Scores are epoch milliseconds, exactly representable as Redis doubles. */
@Component
public class RedisOrderScheduleIndex implements OrderScheduleIndex {
    private final StringRedisTemplate redis;
    private final TradingOrderProperties properties;

    public RedisOrderScheduleIndex(StringRedisTemplate redis, TradingOrderProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    @Override
    public void synchronizeTimer(ProductLine line, CancelAllAfterTimer timer) {
        if (!"ACTIVE".equals(timer.status()) || timer.triggerAt() == null) {
            removeTimer(line, timer.userId(), timer.symbolScope());
            return;
        }
        write(timerKey(line), timerMember(timer.userId(), timer.symbolScope()), timer.triggerAt());
    }

    @Override
    public void removeTimer(ProductLine line, long userId, String symbolScope) {
        remove(timerKey(line), timerMember(userId, symbolScope), line);
    }

    @Override
    public void synchronizeAlgo(AlgoOrderRecord order) {
        if ((order.status().name().equals("PENDING") || order.status().name().equals("RUNNING"))
                && order.nextSliceAt() != null) {
            write(algoKey(order.productLine()), Long.toString(order.algoOrderId()), order.nextSliceAt());
        } else {
            removeAlgo(order.productLine(), order.algoOrderId());
        }
    }

    @Override
    public void removeAlgo(ProductLine line, long algoOrderId) {
        remove(algoKey(line), Long.toString(algoOrderId), line);
    }

    @Override
    public Optional<List<TimerMember>> dueTimers(ProductLine line, Instant now, int limit) {
        try {
            if (!ready(line)) return Optional.empty();
            return Optional.of(redis.opsForZSet().rangeByScore(timerKey(line), Double.NEGATIVE_INFINITY,
                    now.toEpochMilli(), 0, bounded(limit)).stream().map(this::parseTimerMember).toList());
        } catch (RuntimeException ex) {
            markNotReady(line);
            return Optional.empty();
        }
    }

    @Override
    public Optional<List<Long>> dueAlgos(ProductLine line, Instant now, int limit) {
        try {
            if (!ready(line)) return Optional.empty();
            return Optional.of(redis.opsForZSet().rangeByScore(algoKey(line), Double.NEGATIVE_INFINITY,
                    now.toEpochMilli(), 0, bounded(limit)).stream().map(Long::parseLong).toList());
        } catch (RuntimeException ex) {
            markNotReady(line);
            return Optional.empty();
        }
    }

    @Override
    public boolean ready(ProductLine line) {
        try { return Boolean.TRUE.equals(redis.hasKey(readyKey(line))); }
        catch (RuntimeException ex) { return false; }
    }

    @Override
    public void markReady(ProductLine line) {
        redis.opsForValue().set(readyKey(line), "1", properties.getRedisIndex().getReadyTtl());
    }

    @Override
    public void markNotReady(ProductLine line) {
        try { redis.delete(readyKey(line)); } catch (RuntimeException ignored) { }
    }

    @Override
    public void clear(ProductLine line) {
        try { redis.delete(List.of(timerKey(line), algoKey(line))); }
        catch (RuntimeException ex) { throw new IllegalStateException("failed to clear Redis schedule index", ex); }
    }

    private void write(String key, String member, Instant at) {
        try {
            Boolean result = redis.opsForZSet().add(key, member, at.toEpochMilli());
            if (result == null) throw new IllegalStateException("Redis ZADD returned null");
        } catch (RuntimeException ex) {
            throw new IllegalStateException("failed to synchronize Redis schedule index", ex);
        }
    }

    private void remove(String key, String member, ProductLine line) {
        try { redis.opsForZSet().remove(key, member); }
        catch (RuntimeException ex) { markNotReady(line); }
    }

    private TimerMember parseTimerMember(String member) {
        int separator = member.indexOf('|');
        if (separator <= 0 || separator == member.length() - 1) throw new IllegalArgumentException("invalid timer member");
        return new TimerMember(Long.parseLong(member.substring(0, separator)), member.substring(separator + 1));
    }

    private String timerMember(long userId, String scope) { return userId + "|" + scope; }
    private int bounded(int limit) { return Math.max(1, Math.min(limit, 2_000)); }
    private String timerKey(ProductLine line) { return prefix() + ":schedule:timer:{" + line.name() + "}"; }
    private String algoKey(ProductLine line) { return prefix() + ":schedule:algo:{" + line.name() + "}"; }
    private String readyKey(ProductLine line) { return prefix() + ":schedule:ready:" + line.name(); }
    private String prefix() { String value = properties.getRedisIndex().getKeyPrefix(); return value == null || value.isBlank() ? "surprising:order:v1" : value.trim(); }
}
