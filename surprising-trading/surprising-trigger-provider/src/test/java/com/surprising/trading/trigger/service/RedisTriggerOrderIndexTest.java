package com.surprising.trading.trigger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.api.model.TriggerCondition;
import com.surprising.trading.api.model.TriggerOrderStatus;
import com.surprising.trading.api.model.TriggerOrderType;
import com.surprising.trading.trigger.config.TriggerProperties;
import com.surprising.trading.trigger.model.TriggerOrderRecord;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;

class RedisTriggerOrderIndexTest {

    @Test
    @SuppressWarnings("unchecked")
    void indexesStaticOrderByExactIntegerScore() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> zSetOperations = mock(ZSetOperations.class);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.add(any(), any(), any(Double.class))).thenReturn(true);
        RedisTriggerOrderIndex index = new RedisTriggerOrderIndex(redisTemplate, properties());

        index.indexPlaced(order(501L, TriggerCondition.GREATER_OR_EQUAL, TriggerOrderStatus.PENDING));

        verify(zSetOperations).add(
                "surprising:trigger:v1:range:{LINEAR_PERPETUAL:BTC-USDT}:ge",
                "501", 70_000D);
        assertThat((double) index.exactScore(RedisTriggerOrderIndex.MAX_EXACT_REDIS_SCORE))
                .isEqualTo(RedisTriggerOrderIndex.MAX_EXACT_REDIS_SCORE);
        assertThatThrownBy(() -> index.exactScore(Long.MAX_VALUE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be represented exactly");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void readsBothDirectionsWithOneAtomicLuaCandidateLookup() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.hasKey("surprising:trigger:v1:ready:LINEAR_PERPETUAL")).thenReturn(true);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenReturn(List.of("501", "502"));
        RedisTriggerOrderIndex index = new RedisTriggerOrderIndex(redisTemplate, properties());

        var candidates = index.dueCandidates(ProductLine.LINEAR_PERPETUAL, "BTC-USDT", 70_000L, 400);

        assertThat(candidates).contains(List.of(501L, 502L));
        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of(
                        "surprising:trigger:v1:range:{LINEAR_PERPETUAL:BTC-USDT}:ge",
                        "surprising:trigger:v1:range:{LINEAR_PERPETUAL:BTC-USDT}:le")),
                eq("70000"), eq("400"));
    }

    private TriggerProperties properties() {
        return new TriggerProperties();
    }

    private TriggerOrderRecord order(long triggerOrderId,
                                     TriggerCondition condition,
                                     TriggerOrderStatus status) {
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        return new TriggerOrderRecord(triggerOrderId, 1001L, "tp-" + triggerOrderId, null, "BTC-USDT",
                OrderSide.SELL, TriggerOrderType.TAKE_PROFIT, condition, 70_000L,
                OrderType.MARKET, TimeInForce.IOC, 0L, 2L, MarginMode.CROSS, PositionSide.NET, status,
                null, null, null, null, "trace-" + triggerOrderId, null, null, now, now);
    }
}
