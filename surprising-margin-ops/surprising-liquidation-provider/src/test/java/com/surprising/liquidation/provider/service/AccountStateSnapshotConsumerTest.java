package com.surprising.liquidation.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.surprising.account.api.cache.PerpetualAccountStateSnapshotCache;
import com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent;
import com.surprising.liquidation.provider.config.LiquidationProperties;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.PositionMode;
import java.time.Instant;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AccountStateSnapshotConsumerTest {

    @Test
    void appliesSnapshotAndUsesDedicatedProductLineGroup() throws Exception {
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        LiquidationProperties properties = new LiquidationProperties();
        properties.getKafka().setProductTopicsEnabled(true);
        PerpetualAccountStateSnapshotCache cache = new PerpetualAccountStateSnapshotCache();
        PerpetualAccountStateUpdatedEvent event = event(1L);
        when(objectMapper.readValue("value", PerpetualAccountStateUpdatedEvent.class)).thenReturn(event);
        AccountStateSnapshotConsumer consumer = new AccountStateSnapshotConsumer(objectMapper, properties, cache);

        consumer.onAccountStateUpdated(List.of(new ConsumerRecord<>(
                consumer.topic(), 0, 1L, event.partitionKey(), "value")));

        assertThat(cache.isUserReady(event.userId())).isTrue();
        assertThat(consumer.topic()).isEqualTo("surprising.linear-perp.account.state.events.v1");
        assertThat(consumer.groupId()).isEqualTo("surprising-linear-perp-liquidation-account-state-v1");
    }

    @Test
    void rejectsWrongKey() throws Exception {
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        LiquidationProperties properties = new LiquidationProperties();
        PerpetualAccountStateSnapshotCache cache = new PerpetualAccountStateSnapshotCache();
        PerpetualAccountStateUpdatedEvent event = event(1L);
        when(objectMapper.readValue("value", PerpetualAccountStateUpdatedEvent.class)).thenReturn(event);
        AccountStateSnapshotConsumer consumer = new AccountStateSnapshotConsumer(objectMapper, properties, cache);

        assertThatThrownBy(() -> consumer.onAccountStateUpdated(List.of(new ConsumerRecord<>(
                consumer.topic(), 0, 1L, "wrong", "value"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("快照消费失败");
    }

    private PerpetualAccountStateUpdatedEvent event(long revision) {
        return new PerpetualAccountStateUpdatedEvent(
                PerpetualAccountStateUpdatedEvent.CURRENT_SCHEMA_VERSION,
                revision + 100L, revision, ProductLine.LINEAR_PERPETUAL, 1001L, "USDT_PERPETUAL",
                List.of(), List.of(), List.of(), List.of(), List.of(), PositionMode.ONE_WAY,
                Instant.parse("2026-07-01T00:00:00Z"), "trace");
    }
}
