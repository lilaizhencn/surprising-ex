package com.surprising.risk.provider.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.surprising.account.api.cache.PerpetualAccountStateSnapshotCache;
import com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent;
import com.surprising.product.api.ProductLine;
import com.surprising.risk.provider.config.RiskProperties;
import com.surprising.trading.api.model.PositionMode;
import java.time.Instant;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AccountStateSnapshotConsumerTest {

    @Test
    void appliesValidatedSnapshotAndRejectsRevisionGap() throws Exception {
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        RiskProperties properties = new RiskProperties();
        PerpetualAccountStateSnapshotCache cache = new PerpetualAccountStateSnapshotCache();
        PerpetualAccountStateUpdatedEvent first = event(1L);
        PerpetualAccountStateUpdatedEvent gap = event(3L);
        when(objectMapper.readValue("first", PerpetualAccountStateUpdatedEvent.class)).thenReturn(first);
        when(objectMapper.readValue("gap", PerpetualAccountStateUpdatedEvent.class)).thenReturn(gap);
        AccountStateSnapshotConsumer consumer = new AccountStateSnapshotConsumer(objectMapper, properties, cache);

        consumer.onAccountStateUpdated(List.of(record(first, "first")));
        assertThat(cache.isUserReady(first.userId())).isTrue();
        assertThatThrownBy(() -> consumer.onAccountStateUpdated(List.of(record(gap, "gap"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("快照消费失败");
    }

    @Test
    void rejectsWrongPartitionKey() throws Exception {
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        RiskProperties properties = new RiskProperties();
        PerpetualAccountStateSnapshotCache cache = new PerpetualAccountStateSnapshotCache();
        PerpetualAccountStateUpdatedEvent event = event(1L);
        when(objectMapper.readValue("value", PerpetualAccountStateUpdatedEvent.class)).thenReturn(event);
        AccountStateSnapshotConsumer consumer = new AccountStateSnapshotConsumer(objectMapper, properties, cache);

        assertThatThrownBy(() -> consumer.onAccountStateUpdated(List.of(new ConsumerRecord<>(
                properties.getKafka().getAccountStateEventsTopic(), 0, 1L, "wrong", "value"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("快照消费失败");
    }

    private ConsumerRecord<String, String> record(PerpetualAccountStateUpdatedEvent event, String value) {
        return new ConsumerRecord<>("surprising.account.state.events.v1", 0, 1L,
                event.partitionKey(), value);
    }

    private PerpetualAccountStateUpdatedEvent event(long revision) {
        return new PerpetualAccountStateUpdatedEvent(
                PerpetualAccountStateUpdatedEvent.CURRENT_SCHEMA_VERSION,
                revision + 100L, revision, ProductLine.LINEAR_PERPETUAL, 1001L, "USDT_PERPETUAL",
                List.of(), List.of(), List.of(), List.of(), List.of(), PositionMode.ONE_WAY,
                Instant.parse("2026-07-01T00:00:00Z"), "trace");
    }
}
