package com.surprising.account.provider.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.account.api.model.PositionUpdatedEvent;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PositionCacheProjectionConsumerTest {

    @Test
    void appliesCompleteDurableEventUsingRevisionCas() throws Exception {
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        RedisPositionCache cache = mock(RedisPositionCache.class);
        AccountProperties properties = properties();
        PositionUpdatedEvent event = event();
        when(objectMapper.readValue("{}", PositionUpdatedEvent.class)).thenReturn(event);
        PositionCacheProjectionConsumer consumer =
                new PositionCacheProjectionConsumer(objectMapper, cache, properties);

        consumer.onPositionUpdated(record(event.partitionKey(), "{}"));

        verify(cache).apply(event.cacheEvent(), false);
        verify(cache, never()).markNotReady(ProductLine.LINEAR_PERPETUAL);
    }

    @Test
    void rejectsNonUserPartitionKeyAndFailsCacheClosed() throws Exception {
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        RedisPositionCache cache = mock(RedisPositionCache.class);
        AccountProperties properties = properties();
        PositionUpdatedEvent event = event();
        when(objectMapper.readValue("{}", PositionUpdatedEvent.class)).thenReturn(event);
        PositionCacheProjectionConsumer consumer =
                new PositionCacheProjectionConsumer(objectMapper, cache, properties);

        assertThatThrownBy(() -> consumer.onPositionUpdated(record("BTC-USDT", "{}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed to project durable position event");

        verify(cache).markNotReady(ProductLine.LINEAR_PERPETUAL);
        verify(cache, never()).apply(event.cacheEvent(), false);
    }

    @Test
    void resolvesDedicatedProductLineConsumerGroup() {
        AccountProperties properties = properties();
        properties.getKafka().setProductTopicsEnabled(true);
        PositionCacheProjectionConsumer consumer =
                new PositionCacheProjectionConsumer(mock(ObjectMapper.class), mock(RedisPositionCache.class),
                        properties);

        org.assertj.core.api.Assertions.assertThat(consumer.groupId())
                .isEqualTo("surprising-linear-perp-account-position-cache-v1");
    }

    private AccountProperties properties() {
        AccountProperties properties = new AccountProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_PERPETUAL);
        return properties;
    }

    private ConsumerRecord<String, String> record(String key, String value) {
        return new ConsumerRecord<>("surprising.account.position.events.v1", 0, 1L, key, value);
    }

    private PositionUpdatedEvent event() {
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        return new PositionUpdatedEvent(
                PositionUpdatedEvent.CURRENT_SCHEMA_VERSION,
                101L,
                91L,
                ProductLine.LINEAR_PERPETUAL,
                77L,
                1001L,
                "BTC-USDT",
                7L,
                MarginMode.CROSS,
                PositionSide.NET,
                3L,
                60_000L,
                180_000L,
                0L,
                "USDT",
                20_000L,
                now,
                now,
                now,
                "trace-1");
    }
}
