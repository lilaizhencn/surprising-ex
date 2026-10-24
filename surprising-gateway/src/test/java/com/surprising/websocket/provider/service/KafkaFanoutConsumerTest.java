package com.surprising.websocket.provider.service;

import com.surprising.websocket.provider.consumer.KafkaFanoutConsumer;

import com.surprising.websocket.provider.model.TimedPayload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import com.surprising.account.api.model.PositionUpdatedEvent;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.OrderEvent;
import com.surprising.trading.api.model.OrderEventType;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.api.model.MarkPricePublishedEvent;
import com.surprising.price.api.model.IndexPriceEvent;
import com.surprising.price.api.model.PricePublishedEvent;
import com.surprising.price.api.model.PerpFundingRateEvent;
import com.surprising.price.api.model.PriceStatus;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.api.model.TriggerCondition;
import com.surprising.trading.api.model.TriggerOrderResponse;
import com.surprising.trading.api.model.TriggerOrderStatus;
import com.surprising.trading.api.model.TriggerOrderType;
import com.surprising.trading.api.model.TriggerOrderUpdatedEvent;
import com.surprising.websocket.api.model.ExecutionReportEvent;
import com.surprising.websocket.api.model.SubscriptionTopic;
import com.surprising.websocket.api.model.WsChannel;
import com.surprising.websocket.provider.config.WebSocketProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class KafkaFanoutConsumerTest {

    @Mock
    private SubscriptionRegistry registry;

    @Mock
    private CandleUpdateCoalescer candleUpdateCoalescer;

    @Test
    void productTopicFanoutRejectsOtherProductPrivateOrderTopicBeforePublishing() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        WebSocketProperties properties = new WebSocketProperties();
        properties.getKafka().setProductLine(ProductLine.OPTION);
        KafkaFanoutConsumer consumer = new KafkaFanoutConsumer(objectMapper, registry, candleUpdateCoalescer,
                properties);
        Instant eventTime = Instant.parse("2026-07-01T00:00:00Z");
        OrderEvent event = new OrderEvent(1L, 11L, 1001L, "45",
                OrderEventType.ACCEPTED, OrderStatus.ACCEPTED, null, eventTime);

        assertThatThrownBy(() -> consumer.onOrderEvent(new ConsumerRecord<>(
                "surprising.linear-delivery.order.events.v1", 0, 0L,
                "45", objectMapper.writeValueAsString(event))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed to fanout order event")
                .hasRootCauseMessage("order event topic must match current product line: expected="
                        + "surprising.option.order.events.v1 actual=surprising.linear-delivery.order.events.v1");

        verifyNoInteractions(registry);
    }

    @Test
    void fansOutFundingRateDecimalEventBySymbol() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        KafkaFanoutConsumer consumer = new KafkaFanoutConsumer(objectMapper, registry, candleUpdateCoalescer);
        Instant eventTime = Instant.parse("2026-07-01T00:00:00Z");
        PerpFundingRateEvent event = new PerpFundingRateEvent("1", new BigDecimal("0.000100"),
                eventTime.plusSeconds(3600), 8, 9L, eventTime);

        consumer.onFundingRate(new ConsumerRecord<>("surprising.linear-perp.funding.rate.v1", 0, 0L,
                "1", objectMapper.writeValueAsString(event)));

        ArgumentCaptor<SubscriptionTopic> topic = ArgumentCaptor.forClass(SubscriptionTopic.class);
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(registry).publish(topic.capture(), payload.capture(), eq(eventTime));
        assertThat(topic.getValue().channel()).isEqualTo(WsChannel.FUNDING_RATE);
        assertThat(topic.getValue().instrumentId()).isEqualTo("1");
        assertThat(payload.getValue()).isEqualTo(event);
    }

    @Test
    void fansOutFreshMarkPriceBySymbol() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        KafkaFanoutConsumer consumer = new KafkaFanoutConsumer(objectMapper, registry, candleUpdateCoalescer);
        MarkPriceEvent event = markPriceEvent(Instant.now());

        consumer.onPriceEvent(new ConsumerRecord<>("surprising.linear-perp.price.events.v1", 0, 0L,
                "1", objectMapper.writeValueAsString(PricePublishedEvent.mark(markPricePublication(event)))));

        ArgumentCaptor<List<TimedPayload>> events = ArgumentCaptor.forClass(List.class);
        verify(registry).publishTimedBatch(eq(new SubscriptionTopic(WsChannel.MARK_PRICE, "1", null, null,
                        ProductLine.LINEAR_PERPETUAL)),
                events.capture());
        assertThat(events.getValue()).containsExactly(new TimedPayload(event, event.eventTime()));
    }

    @Test
    void fansOutIndexBranchFromUnifiedPriceTopic() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        KafkaFanoutConsumer consumer = new KafkaFanoutConsumer(objectMapper, registry, candleUpdateCoalescer);
        Instant eventTime = Instant.parse("2026-07-01T00:00:00Z");
        IndexPriceEvent event = new IndexPriceEvent("1", new BigDecimal("50000"), 9L,
                PriceStatus.HEALTHY, 2, 2, BigDecimal.valueOf(2), eventTime, List.of());

        consumer.onPriceEvent(new ConsumerRecord<>("surprising.linear-perp.price.events.v1", 0, 0L,
                "1", objectMapper.writeValueAsString(PricePublishedEvent.index(event))));

        ArgumentCaptor<List<TimedPayload>> events = ArgumentCaptor.forClass(List.class);
        verify(registry).publishTimedBatch(eq(new SubscriptionTopic(WsChannel.INDEX_PRICE, "1", null, null,
                        ProductLine.LINEAR_PERPETUAL)),
                events.capture());
        assertThat(events.getValue()).containsExactly(new TimedPayload(event, event.eventTime()));
    }

    @Test
    void dropsStaleMarkPriceWithoutFanoutOrRetryFailure() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        KafkaFanoutConsumer consumer = new KafkaFanoutConsumer(objectMapper, registry, candleUpdateCoalescer);
        MarkPriceEvent event = markPriceEvent(Instant.now().minusSeconds(4));

        consumer.onPriceEvent(new ConsumerRecord<>("surprising.linear-perp.price.events.v1", 0, 0L,
                "1", objectMapper.writeValueAsString(PricePublishedEvent.mark(markPricePublication(event)))));

        verifyNoInteractions(registry);
    }

    private MarkPriceEvent markPriceEvent(Instant eventTime) {
        BigDecimal price = new BigDecimal("50000");
        return new MarkPriceEvent(ProductLine.LINEAR_PERPETUAL, "1", 1L, 5_000_000L, 50_000L,
                price, price, null, price, price, price, new BigDecimal("49990"), new BigDecimal("50010"),
                BigDecimal.ZERO, eventTime.plusSeconds(3600), 3600L, BigDecimal.ZERO, 60L,
                new BigDecimal("49000"), new BigDecimal("51000"), 1L, PriceStatus.HEALTHY,
                eventTime, eventTime);
    }

    private MarkPricePublishedEvent markPricePublication(MarkPriceEvent event) {
        IndexPriceEvent indexInput = new IndexPriceEvent(event.instrumentId(), event.indexPrice(), event.sequence(),
                PriceStatus.HEALTHY, 0, 0, BigDecimal.ZERO, event.eventTime(), List.of());
        return new MarkPricePublishedEvent(event, indexInput, null, null, null,
                event.basisAverage(), event.basisWindowSeconds(), event.eventTime());
    }

    @Test
    void fansOutWildcardPrivateOrderEvent() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        KafkaFanoutConsumer consumer = new KafkaFanoutConsumer(objectMapper, registry, candleUpdateCoalescer);
        Instant eventTime = Instant.parse("2026-07-01T00:00:00Z");
        OrderEvent event = new OrderEvent(1L, 11L, 1001L, SubscriptionTopic.WILDCARD,
                OrderEventType.CANCEL_REQUESTED, OrderStatus.CANCEL_REQUESTED, "reduce-only-pruned", eventTime);

        consumer.onOrderEvent(new ConsumerRecord<>("surprising.linear-perp.order.events.v1", 0, 0L,
                SubscriptionTopic.WILDCARD, objectMapper.writeValueAsString(event)));

        ArgumentCaptor<SubscriptionTopic> topic = ArgumentCaptor.forClass(SubscriptionTopic.class);
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(registry, org.mockito.Mockito.times(2)).publish(topic.capture(), payload.capture(), eq(eventTime));
        assertThat(topic.getAllValues().get(0).channel()).isEqualTo(WsChannel.ORDERS);
        assertThat(topic.getAllValues().get(0).instrumentId()).isEqualTo(SubscriptionTopic.WILDCARD);
        assertThat(topic.getAllValues().get(0).userId()).isEqualTo(1001L);
        assertThat(payload.getAllValues().get(0)).isEqualTo(event);
        assertThat(topic.getAllValues().get(1).channel()).isEqualTo(WsChannel.EXECUTION_REPORTS);
        assertThat(topic.getAllValues().get(1).instrumentId()).isEqualTo(SubscriptionTopic.WILDCARD);
        assertThat(topic.getAllValues().get(1).userId()).isEqualTo(1001L);
        ExecutionReportEvent report = (ExecutionReportEvent) payload.getAllValues().get(1);
        assertThat(report.reportType()).isEqualTo("ORDER_EVENT");
        assertThat(report.orderId()).isEqualTo(11L);
        assertThat(report.orderEventType()).isEqualTo("CANCEL_REQUESTED");
        assertThat(report.orderStatus()).isEqualTo("CANCEL_REQUESTED");
        assertThat(report.reason()).isEqualTo("reduce-only-pruned");
    }

    @Test
    void fansOutTriggerOrderStatusSnapshotToTheOwningUser() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        KafkaFanoutConsumer consumer = new KafkaFanoutConsumer(objectMapper, registry, candleUpdateCoalescer);
        Instant eventTime = Instant.parse("2026-07-01T00:00:00Z");
        TriggerOrderResponse order = new TriggerOrderResponse(
                501L, 1001L, "sl-1", null, "1", OrderSide.SELL,
                TriggerOrderType.STOP_LOSS, TriggerCondition.LESS_OR_EQUAL,
                60_000L, OrderType.MARKET, TimeInForce.IOC, 0L, 10L, MarginMode.CROSS,
                PositionSide.NET, TriggerOrderStatus.CANCELED, null, null, null,
                "POSITION_CLOSED", null, null, eventTime.minusSeconds(60), eventTime);
        TriggerOrderUpdatedEvent event = new TriggerOrderUpdatedEvent(
                701L, ProductLine.LINEAR_PERPETUAL, order, eventTime);

        consumer.onTriggerOrderEvent(new ConsumerRecord<>("surprising.linear-perp.trigger-order.events.v1", 0, 0L,
                "1", objectMapper.writeValueAsString(event)));

        ArgumentCaptor<SubscriptionTopic> topic = ArgumentCaptor.forClass(SubscriptionTopic.class);
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(registry).publish(topic.capture(), payload.capture(), eq(eventTime));
        assertThat(topic.getValue().channel()).isEqualTo(WsChannel.TRIGGER_ORDERS);
        assertThat(topic.getValue().instrumentId()).isEqualTo("1");
        assertThat(topic.getValue().userId()).isEqualTo(1001L);
        assertThat(payload.getValue()).isEqualTo(event);
    }

    @Test
    void fansOutPrivatePositionBySymbolWithPositionSide() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        KafkaFanoutConsumer consumer = new KafkaFanoutConsumer(objectMapper, registry, candleUpdateCoalescer);
        Instant eventTime = Instant.parse("2026-07-01T00:00:00Z");
        PositionUpdatedEvent event = new PositionUpdatedEvent(
                PositionUpdatedEvent.CURRENT_SCHEMA_VERSION, 91L, 2L, ProductLine.LINEAR_PERPETUAL,
                7L, 1001L, "1", 1L, MarginMode.CROSS, PositionSide.LONG,
                10L, 65_000L, 650_000L, 0L, "USDT", 100_000L,
                eventTime, eventTime, eventTime);

        consumer.onPosition(new ConsumerRecord<>("surprising.linear-perp.account.position.events.v1", 0, 0L,
                event.partitionKey(), objectMapper.writeValueAsString(event)));

        ArgumentCaptor<SubscriptionTopic> topic = ArgumentCaptor.forClass(SubscriptionTopic.class);
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(registry).publish(topic.capture(), payload.capture(), eq(eventTime));
        assertThat(topic.getValue().channel()).isEqualTo(WsChannel.POSITIONS);
        assertThat(topic.getValue().instrumentId()).isEqualTo("1");
        assertThat(topic.getValue().userId()).isEqualTo(1001L);
        assertThat(payload.getValue()).isEqualTo(event);
        assertThat(((PositionUpdatedEvent) payload.getValue()).positionSide()).isEqualTo(PositionSide.LONG);
    }

}
