package com.surprising.account.provider.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.account.api.model.PositionCacheEvent;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PositionCacheEventConsumerTest {

    @Test
    void appliesOnlyMatchingProductTopicAndKey() throws Exception {
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        RedisPositionCache cache = mock(RedisPositionCache.class);
        AccountProperties properties = new AccountProperties();
        PositionCacheEvent event = event(ProductLine.LINEAR_PERPETUAL);
        when(objectMapper.readValue("{}", PositionCacheEvent.class)).thenReturn(event);
        PositionCacheEventConsumer consumer = new PositionCacheEventConsumer(objectMapper, cache, properties);

        consumer.onEvent(new ConsumerRecord<>(
                properties.getKafka().getPositionCacheEventsTopic(), 0, 1L,
                "LINEAR_PERPETUAL:1001", "{}"));

        verify(cache).apply(event, false);
    }

    @Test
    void rejectsCrossProductEventBeforeRedisWrite() throws Exception {
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        RedisPositionCache cache = mock(RedisPositionCache.class);
        AccountProperties properties = new AccountProperties();
        PositionCacheEvent event = event(ProductLine.INVERSE_PERPETUAL);
        when(objectMapper.readValue("{}", PositionCacheEvent.class)).thenReturn(event);
        PositionCacheEventConsumer consumer = new PositionCacheEventConsumer(objectMapper, cache, properties);

        assertThatThrownBy(() -> consumer.onEvent(new ConsumerRecord<>(
                properties.getKafka().getPositionCacheEventsTopic(), 0, 1L,
                "INVERSE_PERPETUAL:1001", "{}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed to apply");

        verify(cache, never()).apply(event, false);
        verify(cache).markNotReady(ProductLine.INVERSE_PERPETUAL);
    }

    private PositionCacheEvent event(ProductLine productLine) {
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        return new PositionCacheEvent(
                9L, productLine, 1001L, "BTC-USDT", 7L, MarginMode.CROSS, PositionSide.NET,
                3L, 60_000L, 180_000L, 0L, "USDT", 20_000L, now, now, 9L);
    }
}
