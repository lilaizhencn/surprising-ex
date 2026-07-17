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
import com.surprising.trading.trigger.model.LastPriceTrigger;
import com.surprising.trading.trigger.model.MarkTrigger;
import java.time.Instant;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class TriggerConsumerTopicTest {

    @Test
    void consumersResolveTopicsAndGroupFromProductLine() {
        TriggerProperties properties = new TriggerProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        properties.getKafka().setProductTopicsEnabled(true);
        TriggerOrderService triggerOrderService = mock(TriggerOrderService.class);

        MarkPriceTriggerConsumer markConsumer = new MarkPriceTriggerConsumer(
                mock(MarkPriceTriggerParser.class), triggerOrderService, properties);
        IndexPriceTriggerConsumer indexConsumer = new IndexPriceTriggerConsumer(
                mock(IndexPriceTriggerParser.class), triggerOrderService, properties);
        LastPriceTriggerConsumer lastConsumer = new LastPriceTriggerConsumer(
                mock(LastPriceTriggerParser.class), triggerOrderService, properties);
        PositionClosedTriggerConsumer positionConsumer = new PositionClosedTriggerConsumer(
                mock(ObjectMapper.class), triggerOrderService, properties);

        assertThat(markConsumer.markPriceTopic()).isEqualTo("surprising.linear-delivery.mark.price.v1");
        assertThat(indexConsumer.indexPriceTopic()).isEqualTo("surprising.linear-delivery.index.price.v1");
        assertThat(lastConsumer.lastPriceTopic()).isEqualTo("surprising.linear-delivery.match.trades.v1");
        assertThat(positionConsumer.positionEventsTopic())
                .isEqualTo("surprising.linear-delivery.account.position.events.v1");
        assertThat(properties.getKafka().getTriggerOrderEventsTopic())
                .isEqualTo("surprising.linear-delivery.trigger-order.events.v1");
        assertThat(markConsumer.groupId()).isEqualTo("surprising-linear-delivery-trigger-v1");
        assertThat(indexConsumer.groupId()).isEqualTo("surprising-linear-delivery-trigger-v1");
        assertThat(lastConsumer.groupId()).isEqualTo("surprising-linear-delivery-trigger-v1");
        assertThat(positionConsumer.groupId()).isEqualTo("surprising-linear-delivery-trigger-v1");
    }

    @Test
    void markPriceConsumerRejectsOtherProductTopicBeforeParsing() {
        TriggerProperties properties = productProperties(ProductLine.LINEAR_DELIVERY);
        MarkPriceTriggerParser parser = mock(MarkPriceTriggerParser.class);
        TriggerOrderService triggerOrderService = mock(TriggerOrderService.class);
        MarkPriceTriggerConsumer consumer = new MarkPriceTriggerConsumer(parser, triggerOrderService, properties);

        assertThatThrownBy(() -> consumer.onMarkPrice(new ConsumerRecord<>(
                "surprising.inverse-delivery.mark.price.v1", 0, 1L, "BTC-USDT-260925", "{}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed to process mark price trigger")
                .hasRootCauseMessage("mark price topic must match current product line: expected="
                        + "surprising.linear-delivery.mark.price.v1 actual="
                        + "surprising.inverse-delivery.mark.price.v1");

        verify(parser, never()).parse("{}");
        verify(triggerOrderService, never()).onMarkPrice(
                new MarkTrigger("BTC-USDT-260925", 1L, Instant.parse("2026-07-01T00:00:00Z")));
    }

    @Test
    void indexPriceConsumerRejectsOtherProductTopicBeforeParsing() {
        TriggerProperties properties = productProperties(ProductLine.OPTION);
        IndexPriceTriggerParser parser = mock(IndexPriceTriggerParser.class);
        TriggerOrderService triggerOrderService = mock(TriggerOrderService.class);
        IndexPriceTriggerConsumer consumer = new IndexPriceTriggerConsumer(parser, triggerOrderService, properties);

        assertThatThrownBy(() -> consumer.onIndexPrice(new ConsumerRecord<>(
                "surprising.linear-delivery.index.price.v1", 0, 1L, "BTC-USDT-260925-70000-C", "{}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed to process index price trigger")
                .hasRootCauseMessage("index price topic must match current product line: expected="
                        + "surprising.option.index.price.v1 actual=surprising.linear-delivery.index.price.v1");

        verify(parser, never()).parse("{}");
        verify(triggerOrderService, never()).onIndexPrice(
                new MarkTrigger("BTC-USDT-260925-70000-C", 1L, Instant.parse("2026-07-01T00:00:00Z")));
    }

    @Test
    void lastPriceConsumerAllowsCurrentProductTopic() {
        TriggerProperties properties = productProperties(ProductLine.INVERSE_PERPETUAL);
        LastPriceTriggerParser parser = mock(LastPriceTriggerParser.class);
        TriggerOrderService triggerOrderService = mock(TriggerOrderService.class);
        LastPriceTrigger trigger = new LastPriceTrigger("BTC-USD", 101L, 60_000L,
                Instant.parse("2026-07-01T00:00:00Z"));
        when(parser.parse("{}")).thenReturn(trigger);
        LastPriceTriggerConsumer consumer = new LastPriceTriggerConsumer(parser, triggerOrderService, properties);

        consumer.onLastPrice(new ConsumerRecord<>("surprising.inverse-perp.match.trades.v1", 0, 1L,
                "BTC-USD", "{}"));

        verify(triggerOrderService).onLastPrice(trigger);
    }

    @Test
    void closedPositionConsumerCleansTriggersOnlyAfterPositionReachesZero() throws Exception {
        TriggerProperties properties = productProperties(ProductLine.INVERSE_PERPETUAL);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        TriggerOrderService triggerOrderService = mock(TriggerOrderService.class);
        PositionUpdatedEvent closed = new PositionUpdatedEvent(701L, 801L, 1001L, "BTC-USD", 9L,
                MarginMode.ISOLATED, PositionSide.SHORT, 0L, 0L, 123L,
                Instant.parse("2026-07-01T00:00:00Z"), "trace-close");
        when(objectMapper.readValue("{}", PositionUpdatedEvent.class)).thenReturn(closed);
        PositionClosedTriggerConsumer consumer = new PositionClosedTriggerConsumer(
                objectMapper, triggerOrderService, properties);

        consumer.onPositionUpdated(new ConsumerRecord<>(
                "surprising.inverse-perp.account.position.events.v1", 0, 1L, "BTC-USD", "{}"));

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
