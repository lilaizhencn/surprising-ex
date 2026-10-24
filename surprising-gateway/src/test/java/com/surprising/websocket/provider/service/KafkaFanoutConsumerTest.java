package com.surprising.websocket.provider.service;

import com.surprising.websocket.provider.consumer.KafkaFanoutConsumer;

import com.surprising.websocket.provider.model.TimedPayload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import com.surprising.product.api.ProductLine;
import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.api.model.MarkPricePublishedEvent;
import com.surprising.price.api.model.IndexPriceEvent;
import com.surprising.price.api.model.PricePublishedEvent;
import com.surprising.price.api.model.PerpFundingRateEvent;
import com.surprising.price.api.model.PriceStatus;
import com.surprising.websocket.api.model.SubscriptionTopic;
import com.surprising.websocket.api.model.WsChannel;
import com.surprising.websocket.provider.config.WebSocketProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
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

    private final WebSocketProperties properties = new WebSocketProperties();

    @BeforeEach
    void configureProduct() {
        properties.getKafka().setProductLine(ProductLine.LINEAR_PERPETUAL);
    }

    @Test
    void productTopicFanoutRejectsOtherProductPriceTopicBeforePublishing() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        properties.getKafka().setProductLine(ProductLine.OPTION);
        KafkaFanoutConsumer consumer = new KafkaFanoutConsumer(objectMapper, registry, candleUpdateCoalescer,
                properties);
        Instant eventTime = Instant.parse("2026-07-01T00:00:00Z");
        var event = PricePublishedEvent.mark(markPricePublication(markPriceEvent(eventTime)));

        assertThatThrownBy(() -> consumer.onPriceEvent(new ConsumerRecord<>(
                "surprising.linear-delivery.price.events.v1", 0, 0L,
                "1", objectMapper.writeValueAsString(event))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed to fanout price event")
                .hasRootCauseMessage("price event topic must match current product line: expected="
                        + "surprising.option.price.events.v1 actual=surprising.linear-delivery.price.events.v1");

        verifyNoInteractions(registry);
    }

    @Test
    void fansOutFundingRateDecimalEventBySymbol() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        KafkaFanoutConsumer consumer = new KafkaFanoutConsumer(objectMapper, registry, candleUpdateCoalescer, properties);
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
        KafkaFanoutConsumer consumer = new KafkaFanoutConsumer(objectMapper, registry, candleUpdateCoalescer, properties);
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
        KafkaFanoutConsumer consumer = new KafkaFanoutConsumer(objectMapper, registry, candleUpdateCoalescer, properties);
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
        KafkaFanoutConsumer consumer = new KafkaFanoutConsumer(objectMapper, registry, candleUpdateCoalescer, properties);
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

}
