package com.surprising.trading.trigger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.account.api.model.PositionUpdatedEvent;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.trigger.config.TriggerProperties;
import java.time.Instant;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class TriggerConsumerTopicTest {

    @Test
    void positionConsumerResolvesTopicsAndGroupFromProductLine() {
        TriggerProperties properties = new TriggerProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        properties.getKafka().setProductTopicsEnabled(true);
        TriggerOrderService triggerOrderService = mock(TriggerOrderService.class);
        PositionClosedTriggerConsumer positionConsumer = new PositionClosedTriggerConsumer(
                mock(ObjectMapper.class), triggerOrderService, properties);

        assertThat(positionConsumer.positionEventsTopic())
                .isEqualTo("surprising.linear-delivery.account.position.events.v1");
        assertThat(properties.getKafka().getTriggerOrderEventsTopic())
                .isEqualTo("surprising.linear-delivery.trigger-order.events.v1");
        assertThat(positionConsumer.groupId()).isEqualTo("surprising-linear-delivery-trigger-v1");
    }

    @Test
    void closedPositionConsumerCleansTriggersOnlyAfterPositionReachesZero() throws Exception {
        TriggerProperties properties = productProperties(ProductLine.INVERSE_PERPETUAL);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        TriggerOrderService triggerOrderService = mock(TriggerOrderService.class);
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        PositionUpdatedEvent closed = new PositionUpdatedEvent(
                PositionUpdatedEvent.CURRENT_SCHEMA_VERSION, 701L, 801L, ProductLine.INVERSE_PERPETUAL,
                702L, 1001L, "BTC-USD", 9L, MarginMode.ISOLATED, PositionSide.SHORT,
                0L, 0L, 0L, 123L, "BTC", 0L, now, now, now, "trace-close");
        when(objectMapper.readValue("{}", PositionUpdatedEvent.class)).thenReturn(closed);
        PositionClosedTriggerConsumer consumer = new PositionClosedTriggerConsumer(
                objectMapper, triggerOrderService, properties);

        consumer.onPositionUpdated(new ConsumerRecord<>(
                "surprising.inverse-perp.account.position.events.v1", 0, 1L, closed.partitionKey(), "{}"));

        verify(triggerOrderService).onPositionClosed(closed);
    }

    @Test
    void closedPositionConsumerRejectsOtherProductTopicBeforeParsing() throws Exception {
        TriggerProperties properties = productProperties(ProductLine.LINEAR_DELIVERY);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        TriggerOrderService triggerOrderService = mock(TriggerOrderService.class);
        PositionClosedTriggerConsumer consumer = new PositionClosedTriggerConsumer(
                objectMapper, triggerOrderService, properties);

        assertThatThrownBy(() -> consumer.onPositionUpdated(new ConsumerRecord<>(
                "surprising.option.account.position.events.v1", 0, 1L, "BTC-USDT-260925", "{}")))
                .isInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("position update topic must match current product line: expected="
                        + "surprising.linear-delivery.account.position.events.v1 actual="
                        + "surprising.option.account.position.events.v1");

        verify(objectMapper, never()).readValue("{}", PositionUpdatedEvent.class);
        verify(triggerOrderService, never()).onPositionClosed(any(PositionUpdatedEvent.class));
    }

    private TriggerProperties productProperties(ProductLine productLine) {
        TriggerProperties properties = new TriggerProperties();
        properties.getKafka().setProductLine(productLine);
        properties.getKafka().setProductTopicsEnabled(true);
        return properties;
    }
}
