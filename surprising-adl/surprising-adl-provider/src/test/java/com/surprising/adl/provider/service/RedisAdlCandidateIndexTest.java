package com.surprising.adl.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.adl.provider.config.AdlProperties;
import com.surprising.product.api.ProductLine;
import com.surprising.risk.api.model.RiskPositionUpdatedEvent;
import com.surprising.risk.api.model.RiskStatus;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

class RedisAdlCandidateIndexTest {

    @Test
    void keepsSameSettlementAssetCandidatesInSeparateProductLineKeys() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        RedisAdlCandidateIndex index = new RedisAdlCandidateIndex(redis, new AdlProperties());

        index.synchronize(event(ProductLine.LINEAR_PERPETUAL, 1001L));
        index.synchronize(event(ProductLine.INVERSE_PERPETUAL, 1002L));

        verify(zset).add(eq("surprising:adl:v1:queue:{LINEAR_PERPETUAL:USDT}"),
                eq("1001|BTC-USDT|CROSS|NET"), anyDouble());
        verify(zset).add(eq("surprising:adl:v1:queue:{INVERSE_PERPETUAL:USDT}"),
                eq("1002|BTC-USDT|CROSS|NET"), anyDouble());
    }

    @Test
    void readsOnlyTheRequestedProductLineForTheSameSettlementAsset() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        RedisAdlCandidateIndex index = new RedisAdlCandidateIndex(redis, new AdlProperties());
        String linearReady = index.readyKey(ProductLine.LINEAR_PERPETUAL, "USDT");
        String linearQueue = index.key(ProductLine.LINEAR_PERPETUAL, "USDT");
        when(redis.hasKey(linearReady)).thenReturn(true);
        when(zset.range(linearQueue, 0, 9)).thenReturn(Set.of("1001|BTC-USDT|CROSS|NET"));

        var result = index.candidates(ProductLine.LINEAR_PERPETUAL, "USDT", 10);

        assertThat(result).hasValueSatisfying(members -> assertThat(members)
                .containsExactly(new RedisAdlCandidateIndex.Member(1001L, "BTC-USDT", "CROSS", "NET")));
        verify(redis).hasKey(linearReady);
        verify(zset).range(linearQueue, 0, 9);
    }

    private RiskPositionUpdatedEvent event(ProductLine productLine, long userId) {
        return new RiskPositionUpdatedEvent(1L, productLine, 2L, userId, "BTC-USDT", MarginMode.CROSS,
                PositionSide.NET, 3L, "USDT", 10L, 100L, 110L, 1_100L, 100L, 20L, 220L,
                100_000L, RiskStatus.NORMAL, Instant.parse("2026-07-18T00:00:00Z"), "trace");
    }
}
