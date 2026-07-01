package com.surprising.websocket.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.surprising.trading.api.model.OrderEvent;
import com.surprising.trading.api.model.OrderEventType;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderBookDepthEvent;
import com.surprising.trading.api.model.OrderBookDepthUpdateType;
import com.surprising.trading.api.model.OrderBookLevel;
import com.surprising.price.api.model.PerpFundingRateEvent;
import com.surprising.websocket.api.model.SubscriptionTopic;
import com.surprising.websocket.api.model.WsChannel;
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
    void fansOutOrderBookDepthBySymbol() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        KafkaFanoutConsumer consumer = new KafkaFanoutConsumer(objectMapper, registry, candleUpdateCoalescer);
        Instant eventTime = Instant.parse("2026-07-01T00:00:00Z");
        OrderBookDepthEvent event = new OrderBookDepthEvent("BTC-USDT", 7L, 6L,
                OrderBookDepthUpdateType.DELTA, 50,
                List.of(new OrderBookLevel(99L, 5L, 1L)),
                List.of(new OrderBookLevel(101L, 8L, 2L)), eventTime);

        consumer.onOrderBookDepth(new ConsumerRecord<>("surprising.perp.orderbook.depth.v1", 0, 0L,
                "BTC-USDT", objectMapper.writeValueAsString(event)));

        ArgumentCaptor<SubscriptionTopic> topic = ArgumentCaptor.forClass(SubscriptionTopic.class);
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(registry).publish(topic.capture(), payload.capture(), eq(eventTime));
        assertThat(topic.getValue().channel()).isEqualTo(WsChannel.ORDER_BOOK_DEPTH);
        assertThat(topic.getValue().symbol()).isEqualTo("BTC-USDT");
        assertThat(payload.getValue()).isEqualTo(event);
    }

    @Test
    void fansOutFundingRateDecimalEventBySymbol() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        KafkaFanoutConsumer consumer = new KafkaFanoutConsumer(objectMapper, registry, candleUpdateCoalescer);
        Instant eventTime = Instant.parse("2026-07-01T00:00:00Z");
        PerpFundingRateEvent event = new PerpFundingRateEvent("BTC-USDT", new BigDecimal("0.000100"),
                eventTime.plusSeconds(3600), 8, 9L, eventTime);

        consumer.onFundingRate(new ConsumerRecord<>("surprising.perp.funding.rate.v1", 0, 0L,
                "BTC-USDT", objectMapper.writeValueAsString(event)));

        ArgumentCaptor<SubscriptionTopic> topic = ArgumentCaptor.forClass(SubscriptionTopic.class);
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(registry).publish(topic.capture(), payload.capture(), eq(eventTime));
        assertThat(topic.getValue().channel()).isEqualTo(WsChannel.FUNDING_RATE);
        assertThat(topic.getValue().symbol()).isEqualTo("BTC-USDT");
        assertThat(payload.getValue()).isEqualTo(event);
    }

    @Test
    void fansOutWildcardPrivateOrderEvent() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        KafkaFanoutConsumer consumer = new KafkaFanoutConsumer(objectMapper, registry, candleUpdateCoalescer);
        Instant eventTime = Instant.parse("2026-07-01T00:00:00Z");
        OrderEvent event = new OrderEvent(1L, 11L, 1001L, SubscriptionTopic.WILDCARD,
                OrderEventType.CANCEL_REQUESTED, OrderStatus.CANCEL_REQUESTED, "reduce-only-pruned", eventTime,
                "trace-1");

        consumer.onOrderEvent(new ConsumerRecord<>("surprising.perp.order.events.v1", 0, 0L,
                SubscriptionTopic.WILDCARD, objectMapper.writeValueAsString(event)));

        ArgumentCaptor<SubscriptionTopic> topic = ArgumentCaptor.forClass(SubscriptionTopic.class);
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(registry).publish(topic.capture(), payload.capture(), eq(eventTime));
        assertThat(topic.getValue().channel()).isEqualTo(WsChannel.ORDERS);
        assertThat(topic.getValue().symbol()).isEqualTo(SubscriptionTopic.WILDCARD);
        assertThat(topic.getValue().userId()).isEqualTo(1001L);
        assertThat(payload.getValue()).isEqualTo(event);
    }
}
