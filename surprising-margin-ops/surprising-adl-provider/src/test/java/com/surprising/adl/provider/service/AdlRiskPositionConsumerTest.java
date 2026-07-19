package com.surprising.adl.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.surprising.adl.provider.config.AdlProperties;
import com.surprising.product.api.ProductLine;
import com.surprising.risk.api.model.RiskPositionUpdatedEvent;
import com.surprising.risk.api.model.RiskStatus;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AdlRiskPositionConsumerTest {

    @Test
    void synchronizesOnlyRiskEventsForItsConfiguredProductLine() throws Exception {
        AdlProperties properties = new AdlProperties();
        properties.getKafka().setProductLine(ProductLine.INVERSE_PERPETUAL);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        RedisAdlCandidateIndex index = mock(RedisAdlCandidateIndex.class);
        AdlRiskPositionConsumer consumer = new AdlRiskPositionConsumer(objectMapper, index, properties);
        ConsumerRecord<String, String> record = new ConsumerRecord<>(consumer.topic(), 0, 0L, "BTC-USDT", "{}");
        RiskPositionUpdatedEvent event = event(ProductLine.INVERSE_PERPETUAL);
        when(objectMapper.readValue(anyString(), eq(RiskPositionUpdatedEvent.class))).thenReturn(event);

        consumer.onRiskPosition(List.of(record));

        verify(index).synchronize(event);
    }

    @Test
    void ignoresOtherProductLineOnTheSharedRiskTopic() throws Exception {
        AdlProperties properties = new AdlProperties();
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        RedisAdlCandidateIndex index = mock(RedisAdlCandidateIndex.class);
        AdlRiskPositionConsumer consumer = new AdlRiskPositionConsumer(objectMapper, index, properties);
        ConsumerRecord<String, String> record = new ConsumerRecord<>(consumer.topic(), 0, 0L, "BTC-USDT", "{}");
        when(objectMapper.readValue(anyString(), eq(RiskPositionUpdatedEvent.class)))
                .thenReturn(event(ProductLine.INVERSE_PERPETUAL));

        consumer.onRiskPosition(List.of(record));

        verifyNoInteractions(index);
    }

    @Test
    void discardsMalformedRiskEventWithoutBlockingThePartition() throws Exception {
        AdlProperties properties = new AdlProperties();
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        RedisAdlCandidateIndex index = mock(RedisAdlCandidateIndex.class);
        AdlRiskPositionConsumer consumer = new AdlRiskPositionConsumer(objectMapper, index, properties);
        ConsumerRecord<String, String> record = new ConsumerRecord<>(consumer.topic(), 3, 91L, "BTC-USDT", "{bad");
        ConsumerRecord<String, String> validRecord =
                new ConsumerRecord<>(consumer.topic(), 3, 92L, "BTC-USDT", "{}");
        RiskPositionUpdatedEvent event = event(ProductLine.LINEAR_PERPETUAL);
        when(objectMapper.readValue(anyString(), eq(RiskPositionUpdatedEvent.class)))
                .thenThrow(new IllegalArgumentException("invalid risk publication"))
                .thenReturn(event);

        consumer.onRiskPosition(List.of(record, validRecord));

        verify(index).synchronize(event);
    }

    @Test
    void rejectsRecordsFromAnUnexpectedTopic() {
        AdlProperties properties = new AdlProperties();
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        RedisAdlCandidateIndex index = mock(RedisAdlCandidateIndex.class);
        AdlRiskPositionConsumer consumer = new AdlRiskPositionConsumer(objectMapper, index, properties);

        assertThatThrownBy(() -> consumer.onRiskPosition(List.of(
                new ConsumerRecord<>("surprising.other.risk.position.events.v1", 0, 0L, "BTC-USDT", "{}"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unexpected ADL risk event topic");
        verifyNoInteractions(objectMapper, index);
    }

    @Test
    void usesProductScopedTopicAndGroupWhenConfigured() {
        AdlProperties properties = new AdlProperties();
        properties.getKafka().setProductLine(ProductLine.INVERSE_PERPETUAL);
        properties.getKafka().setProductTopicsEnabled(true);
        AdlRiskPositionConsumer consumer = new AdlRiskPositionConsumer(mock(ObjectMapper.class),
                mock(RedisAdlCandidateIndex.class), properties);

        assertThat(consumer.topic()).isEqualTo("surprising.inverse-perp.risk.position.events.v1");
        assertThat(consumer.groupId()).isEqualTo("surprising-inverse-perp-adl-risk-index-v1");
    }

    private RiskPositionUpdatedEvent event(ProductLine productLine) {
        return new RiskPositionUpdatedEvent(1L, productLine, 2L, 1001L, "BTC-USDT", MarginMode.CROSS,
                PositionSide.NET, 3L, "USDT", 10L, 100L, 110L, 1_100L, 100L, 20L, 220L,
                100_000L, RiskStatus.NORMAL, Instant.parse("2026-07-18T00:00:00Z"), "trace");
    }
}
