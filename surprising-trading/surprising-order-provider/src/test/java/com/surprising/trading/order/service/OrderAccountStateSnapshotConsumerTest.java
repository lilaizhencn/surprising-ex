package com.surprising.trading.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.surprising.account.api.cache.PerpetualAccountStateSnapshotCache;
import com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.PositionMode;
import com.surprising.trading.order.config.TradingOrderProperties;
import java.time.Instant;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class OrderAccountStateSnapshotConsumerTest {

    @Test
    void appliesPerpetualStateAndExposesDedicatedGroup() throws Exception {
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        TradingOrderProperties properties = new TradingOrderProperties();
        properties.getKafka().setProductTopicsEnabled(true);
        PerpetualAccountStateSnapshotCache cache = new PerpetualAccountStateSnapshotCache();
        PerpetualAccountStateUpdatedEvent event = event(1L);
        when(objectMapper.readValue("value", PerpetualAccountStateUpdatedEvent.class)).thenReturn(event);
        OrderAccountStateSnapshotConsumer consumer = new OrderAccountStateSnapshotConsumer(
                objectMapper, properties, cache, new OrderMarginSnapshotCache());

        consumer.onAccountStateUpdated(List.of(new ConsumerRecord<>(
                consumer.topic(), 0, 1L, event.partitionKey(), "value")));

        // 没有 Kafka consumer 位点时，测试调用不会擅自宣布全局追赶完成。
        assertThat(cache.isUserReady(event.userId())).isFalse();
        cache.markReady();
        assertThat(cache.isUserReady(event.userId())).isTrue();
        assertThat(consumer.topic()).isEqualTo("surprising.linear-perp.account.state.events.v1");
        assertThat(consumer.groupId()).isEqualTo("surprising-linear-perp-order-account-state-v1");
    }

    @Test
    void rejectsWrongKeyBeforeUpdatingCache() throws Exception {
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        TradingOrderProperties properties = new TradingOrderProperties();
        PerpetualAccountStateSnapshotCache cache = new PerpetualAccountStateSnapshotCache();
        PerpetualAccountStateUpdatedEvent event = event(1L);
        when(objectMapper.readValue("value", PerpetualAccountStateUpdatedEvent.class)).thenReturn(event);
        OrderAccountStateSnapshotConsumer consumer = new OrderAccountStateSnapshotConsumer(
                objectMapper, properties, cache, new OrderMarginSnapshotCache());

        assertThatThrownBy(() -> consumer.onAccountStateUpdated(List.of(new ConsumerRecord<>(
                consumer.topic(), 0, 1L, "wrong", "value"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("快照消费失败");
        assertThat(cache.isUserReady(event.userId())).isFalse();
    }

    private PerpetualAccountStateUpdatedEvent event(long revision) {
        return new PerpetualAccountStateUpdatedEvent(
                PerpetualAccountStateUpdatedEvent.CURRENT_SCHEMA_VERSION,
                revision + 100L, revision, ProductLine.LINEAR_PERPETUAL, 1001L, "USDT_PERPETUAL",
                List.of(), List.of(), List.of(), List.of(), List.of(), PositionMode.ONE_WAY,
                Instant.parse("2026-07-01T00:00:00Z"), "trace");
    }
}
