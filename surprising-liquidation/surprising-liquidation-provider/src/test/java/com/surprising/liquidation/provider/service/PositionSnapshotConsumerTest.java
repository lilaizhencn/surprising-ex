package com.surprising.liquidation.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.surprising.account.api.cache.PositionSnapshotCache;
import com.surprising.account.api.model.PositionUpdatedEvent;
import com.surprising.liquidation.provider.config.LiquidationProperties;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PositionSnapshotConsumerTest {

    @Test
    void appliesPositionEventToLocalSnapshot() throws Exception {
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        LiquidationProperties properties = properties();
        PositionSnapshotCache cache = new PositionSnapshotCache(ProductLine.LINEAR_PERPETUAL);
        PositionUpdatedEvent event = event(9L, 5L);
        when(objectMapper.readValue("{}", PositionUpdatedEvent.class)).thenReturn(event);
        PositionSnapshotConsumer consumer = new PositionSnapshotConsumer(objectMapper, properties, cache);

        consumer.onPositionEvents(List.of(record(event.partitionKey(), "{}")));

        assertThat(cache.position(event.userId(), event.symbol(), event.marginMode(), event.positionSide()))
                .contains(event);
    }

    @Test
    void rejectsWrongPartitionKeyBeforeUpdatingSnapshot() throws Exception {
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        LiquidationProperties properties = properties();
        PositionSnapshotCache cache = new PositionSnapshotCache(ProductLine.LINEAR_PERPETUAL);
        PositionUpdatedEvent event = event(9L, 5L);
        when(objectMapper.readValue("{}", PositionUpdatedEvent.class)).thenReturn(event);
        PositionSnapshotConsumer consumer = new PositionSnapshotConsumer(objectMapper, properties, cache);

        assertThatThrownBy(() -> consumer.onPositionEvents(List.of(record("wrong-key", "{}"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("消费失败");
        assertThat(cache.position(event.userId(), event.symbol(), event.marginMode(), event.positionSide()))
                .isEmpty();
    }

    @Test
    void resolvesDedicatedPerpetualTopicAndGroup() {
        LiquidationProperties properties = properties();
        properties.getKafka().setProductTopicsEnabled(true);
        PositionSnapshotConsumer consumer = new PositionSnapshotConsumer(
                mock(ObjectMapper.class), properties, new PositionSnapshotCache(ProductLine.LINEAR_PERPETUAL));

        assertThat(consumer.topic()).isEqualTo("surprising.linear-perp.account.position.events.v1");
        assertThat(consumer.groupId()).isEqualTo("surprising-linear-perp-liquidation-position-snapshot-v1");
    }

    private LiquidationProperties properties() {
        LiquidationProperties properties = new LiquidationProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_PERPETUAL);
        return properties;
    }

    private ConsumerRecord<String, String> record(String key, String value) {
        return new ConsumerRecord<>("surprising.account.position.events.v1", 0, 1L, key, value);
    }

    private PositionUpdatedEvent event(long revision, long quantity) {
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        return new PositionUpdatedEvent(PositionUpdatedEvent.CURRENT_SCHEMA_VERSION, revision, 91L,
                ProductLine.LINEAR_PERPETUAL, revision, 1001L, "BTC-USDT", 7L, MarginMode.CROSS,
                PositionSide.NET, quantity, 60_000L, 180_000L, 0L, "USDT", 20_000L,
                now, now, now, "trace");
    }
}
