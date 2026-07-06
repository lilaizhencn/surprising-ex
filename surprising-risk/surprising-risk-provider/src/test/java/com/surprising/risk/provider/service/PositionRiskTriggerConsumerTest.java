package com.surprising.risk.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.surprising.product.api.ProductLine;
import com.surprising.risk.provider.config.RiskProperties;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PositionRiskTriggerConsumerTest {

    @Test
    void consumesPositionEventAndTriggersRiskScan() {
        RiskService riskService = mock(RiskService.class);
        PositionRiskTriggerConsumer consumer = new PositionRiskTriggerConsumer(new ObjectMapper(), riskService);

        consumer.onPositionUpdated(record("BTC-USDT", positionPayload("BTC-USDT")));

        verify(riskService).scanPositionUpdate(1001L, "BTC-USDT", MarginMode.CROSS, PositionSide.NET,
                7L, "trace-1");
    }

    @Test
    void rejectsPositionEventWhenKafkaKeyDoesNotMatchSymbol() {
        RiskService riskService = mock(RiskService.class);
        PositionRiskTriggerConsumer consumer = new PositionRiskTriggerConsumer(new ObjectMapper(), riskService);

        assertThatThrownBy(() -> consumer.onPositionUpdated(record("ETH-USDT", positionPayload("BTC-USDT"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed to process position risk trigger");
        verifyNoInteractions(riskService);
    }

    @Test
    void rejectsInvalidPayloadForKafkaRetry() {
        RiskService riskService = mock(RiskService.class);
        PositionRiskTriggerConsumer consumer = new PositionRiskTriggerConsumer(new ObjectMapper(), riskService);

        assertThatThrownBy(() -> consumer.onPositionUpdated(record("BTC-USDT", "not-json")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed to process position risk trigger");
        verifyNoInteractions(riskService);
    }

    @Test
    void resolvesPositionTopicAndGroupFromProductLine() {
        RiskProperties properties = new RiskProperties();
        properties.getKafka().setProductLine(ProductLine.OPTION);
        properties.getKafka().setProductTopicsEnabled(true);
        PositionRiskTriggerConsumer consumer = new PositionRiskTriggerConsumer(new ObjectMapper(),
                mock(RiskService.class), properties);

        assertThat(consumer.positionEventsTopic())
                .isEqualTo("surprising.option.account.position.events.v1");
        assertThat(consumer.groupId()).isEqualTo("surprising-option-risk-v1");
    }

    private ConsumerRecord<String, String> record(String key, String value) {
        return new ConsumerRecord<>("surprising.account.position.events.v1", 0, 1L, key, value);
    }

    private String positionPayload(String symbol) {
        return """
                {
                  "eventId": 11,
                  "tradeId": 22,
                  "userId": 1001,
                  "symbol": "%s",
                  "instrumentVersion": 7,
                  "signedQuantitySteps": 10,
                  "entryPriceTicks": 65000,
                  "realizedPnlUnits": 0,
                  "eventTime": "2026-07-01T00:00:00Z",
                  "traceId": "trace-1"
                }
                """.formatted(symbol);
    }
}
