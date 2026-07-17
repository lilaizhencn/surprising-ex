package com.surprising.account.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.account.api.model.PositionCacheEvent;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.account.provider.model.CachedPosition;
import com.surprising.account.provider.model.CachedPositionMargin;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class RedisPositionCacheTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void atomicallyAppliesAllHashesInsideOneProductUserSlot() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        PositionCacheMetrics metrics = mock(PositionCacheMetrics.class);
        when(objectMapper.writeValueAsString(any(CachedPosition.class))).thenReturn("position-json");
        when(objectMapper.writeValueAsString(any(CachedPositionMargin.class))).thenReturn("margin-json");
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any())).thenReturn(1L);
        RedisPositionCache cache = new RedisPositionCache(
                redisTemplate, objectMapper, new AccountProperties(), metrics);

        boolean applied = cache.apply(event(9L), false);

        assertThat(applied).isTrue();
        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of(
                        "surprising:position:v1:{LINEAR_PERPETUAL:1001}:state",
                        "surprising:position:v1:{LINEAR_PERPETUAL:1001}:margin",
                        "surprising:position:v1:{LINEAR_PERPETUAL:1001}:revision")),
                eq("BTC-USDT|ISOLATED|LONG"), eq("9"), eq("position-json"), eq("margin-json"));
        verify(metrics).recordApplied(false);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void rejectsOlderRevisionWithoutOverwritingState() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        PositionCacheMetrics metrics = mock(PositionCacheMetrics.class);
        when(objectMapper.writeValueAsString(any(CachedPosition.class))).thenReturn("position-json");
        when(objectMapper.writeValueAsString(any(CachedPositionMargin.class))).thenReturn("margin-json");
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any())).thenReturn(0L);
        RedisPositionCache cache = new RedisPositionCache(
                redisTemplate, objectMapper, new AccountProperties(), metrics);

        assertThat(cache.apply(event(8L), false)).isFalse();

        verify(metrics).recordStale();
    }

    @Test
    @SuppressWarnings("unchecked")
    void userReadsComeFromRedisAndFilterClosedPositions() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        when(redisTemplate.hasKey("surprising:position:v1:ready:LINEAR_PERPETUAL")).thenReturn(true);
        when(redisTemplate.opsForHash()).thenReturn((HashOperations) hash);
        when(hash.values("surprising:position:v1:{LINEAR_PERPETUAL:1001}:state"))
                .thenReturn(List.of("open", "closed"));
        when(objectMapper.readValue("open", CachedPosition.class)).thenReturn(cachedPosition(3L));
        when(objectMapper.readValue("closed", CachedPosition.class)).thenReturn(cachedPosition(0L));
        RedisPositionCache cache = new RedisPositionCache(
                redisTemplate, objectMapper, new AccountProperties(), mock(PositionCacheMetrics.class));

        var positions = cache.positions(ProductLine.LINEAR_PERPETUAL, 1001L, PositionSide.LONG);

        assertThat(positions).hasSize(1);
        assertThat(positions.getFirst().signedQuantitySteps()).isEqualTo(3L);
    }

    @Test
    void failsClosedWhenReadinessMarkerIsMissing() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.hasKey("surprising:position:v1:ready:LINEAR_PERPETUAL")).thenReturn(false);
        RedisPositionCache cache = new RedisPositionCache(
                redisTemplate, mock(ObjectMapper.class), new AccountProperties(), mock(PositionCacheMetrics.class));

        assertThatThrownBy(() -> cache.position(
                ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", MarginMode.CROSS, PositionSide.NET))
                .isInstanceOf(PositionCacheUnavailableException.class)
                .hasMessageContaining("not ready");
    }

    @Test
    void roundTripsCachePayloadWithRealJacksonMapper() throws Exception {
        ObjectMapper mapper = JsonMapper.builder().build();
        CachedPosition source = cachedPosition(3L);

        CachedPosition decoded = mapper.readValue(mapper.writeValueAsString(source), CachedPosition.class);

        assertThat(decoded).isEqualTo(source);
    }

    private PositionCacheEvent event(long revision) {
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        return new PositionCacheEvent(
                revision, ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", 7L,
                MarginMode.ISOLATED, PositionSide.LONG, 3L, 60_000L, 180_000L, 100L,
                "USDT", 20_000L, now, now, revision);
    }

    private CachedPosition cachedPosition(long quantity) {
        return new CachedPosition(
                ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", quantity == 0L ? null : 7L,
                MarginMode.ISOLATED, PositionSide.LONG, quantity, quantity == 0L ? 0L : 60_000L,
                quantity == 0L ? 0L : 180_000L, 100L, Instant.parse("2026-07-01T00:00:00Z"), 9L);
    }
}
