package com.surprising.websocket.provider.consumer;

import com.surprising.websocket.provider.service.CandleUpdateCoalescer;
import com.surprising.websocket.provider.service.SubscriptionRegistry;

import com.surprising.websocket.provider.model.TimedPayload;

import lombok.extern.slf4j.Slf4j;
import com.surprising.candlestick.api.model.CandleUpdatedEvent;
import com.surprising.product.api.ProductLine;
import com.surprising.price.api.model.IndexPriceEvent;
import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.api.model.PerpFundingRateEvent;
import com.surprising.price.api.model.PriceEventType;
import com.surprising.price.api.model.PricePublishedEvent;
import com.surprising.price.api.model.PriceStatus;
import com.surprising.trading.api.KafkaSymbolKeyValidator;
import com.surprising.websocket.api.model.SubscriptionTopic;
import com.surprising.websocket.api.model.WsChannel;
import com.surprising.websocket.provider.config.WebSocketProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.time.Instant;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes domain events and pushes them only to clients connected to this node.
 *
 * <p>Each WebSocket node intentionally uses its own Kafka consumer group. Public market data must
 * reach every node for local fanout. Private state is delivered by RealtimeWebSocketBridge.</p>
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="surprising.realtime.enabled",havingValue="false",matchIfMissing=true)
@Slf4j
public class KafkaFanoutConsumer {

    private final ObjectMapper objectMapper;
    private final SubscriptionRegistry registry;
    private final CandleUpdateCoalescer candleUpdateCoalescer;
    private final WebSocketProperties properties;

    public KafkaFanoutConsumer(ObjectMapper objectMapper,
                               SubscriptionRegistry registry,
                               CandleUpdateCoalescer candleUpdateCoalescer) {
        this(objectMapper, registry, candleUpdateCoalescer, defaultTestProperties());
    }

    @Autowired
    public KafkaFanoutConsumer(ObjectMapper objectMapper,
                               SubscriptionRegistry registry,
                               CandleUpdateCoalescer candleUpdateCoalescer,
                               WebSocketProperties properties) {
        this.objectMapper = objectMapper;
        this.registry = registry;
        this.candleUpdateCoalescer = candleUpdateCoalescer;
        this.properties = properties;
    }

    private static WebSocketProperties defaultTestProperties() {
        WebSocketProperties properties = new WebSocketProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_PERPETUAL);
        return properties;
    }

    public void onCandle(ConsumerRecord<String, String> record) {
        try {
            requireCurrentProductTopic(record.topic(), candleTopic(), "candle update");
            CandleUpdatedEvent event = objectMapper.readValue(record.value(), CandleUpdatedEvent.class);
            KafkaSymbolKeyValidator.requireMatchingSymbol(record.key(), event.instrumentId(), "candle update");
            candleUpdateCoalescer.publish(event, fanoutProductLine());
        } catch (Exception ex) {
            log.error("Failed to fanout candle update: {}", ex.getMessage(), ex);
            throw new IllegalStateException("failed to fanout candle update", ex);
        }
    }

    @KafkaListener(
            topics = "#{__listener.candleTopic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "webSocketKafkaBatchListenerContainerFactory")
    public void onCandleBatch(List<ConsumerRecord<String, String>> records) {
        for (ConsumerRecord<String, String> record : records) {
            onCandle(record);
        }
    }

    public void onPriceEvent(ConsumerRecord<String, String> record) {
        try {
            Map<SubscriptionTopic, List<TimedPayload>> grouped = new LinkedHashMap<>();
            dispatchPriceEvent(record, grouped);
            publishBatches(grouped);
        } catch (Exception ex) {
            log.error("Failed to fanout price event: {}", ex.getMessage(), ex);
            throw new IllegalStateException("failed to fanout price event", ex);
        }
    }

    @KafkaListener(
            topics = "#{__listener.priceEventsTopic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "webSocketKafkaBatchListenerContainerFactory")
    public void onPriceEventBatch(List<ConsumerRecord<String, String>> records) {
        Map<SubscriptionTopic, List<TimedPayload>> grouped = new LinkedHashMap<>();
        for (ConsumerRecord<String, String> record : records) {
            try {
                dispatchPriceEvent(record, grouped);
            } catch (Exception ex) {
                log.warn("Dropped invalid price event topic={} partition={} offset={}: {}",
                        record.topic(), record.partition(), record.offset(), ex.getClass().getSimpleName());
            }
        }
        publishBatches(grouped);
    }

    private void dispatchPriceEvent(ConsumerRecord<String, String> record,
                                    Map<SubscriptionTopic, List<TimedPayload>> grouped)
            throws Exception {
        requireCurrentProductTopic(record.topic(), priceEventsTopic(), "price event");
        PricePublishedEvent publication = objectMapper.readValue(record.value(), PricePublishedEvent.class);
        KafkaSymbolKeyValidator.requireMatchingSymbol(record.key(), publication.instrumentId(), "price event");
        if (publication.eventType() == PriceEventType.INDEX_PRICE) {
            IndexPriceEvent event = publication.indexPrice();
            KafkaSymbolKeyValidator.requireMatchingSymbol(record.key(), event.instrumentId(), "index price");
            addBatch(grouped, topic(WsChannel.INDEX_PRICE, event.instrumentId(), null), event, event.eventTime());
            return;
        }
        if (publication.eventType() == PriceEventType.MARK_PRICE) {
            MarkPriceEvent event = publication.markPrice() == null ? null : publication.markPrice().result();
            if (event == null) {
                throw new IllegalArgumentException("mark price publication result is required");
            }
            KafkaSymbolKeyValidator.requireMatchingSymbol(record.key(), event.instrumentId(), "mark price");
            if (isFreshMarkPrice(event)) {
                addBatch(grouped, topic(WsChannel.MARK_PRICE, event.instrumentId(), null), event, event.eventTime());
            }
            return;
        }
        throw new IllegalArgumentException("unsupported price event type: " + publication.eventType());
    }

    private boolean isFreshMarkPrice(MarkPriceEvent event) {
        if (event == null || event.productLine() != properties.getKafka().getProductLine()
                || event.instrumentChangeId() <= 0 || event.markPriceUnits() <= 0 || event.markPriceTicks() <= 0
                || event.sequence() <= 0 || event.eventTime() == null || event.publishedAt() == null
                || event.status() == null || event.status() == PriceStatus.STALE
                || event.status() == PriceStatus.INSUFFICIENT_SOURCES
                || event.publishedAt().isBefore(event.eventTime())) {
            return false;
        }
        Instant now = Instant.now();
        Duration maxAge = properties.getFanout().getMarkPriceMaxAge();
        Duration futureSkew = properties.getFanout().getMarkPriceAllowedFutureSkew();
        return maxAge != null && !maxAge.isNegative() && !maxAge.isZero()
                && futureSkew != null && !futureSkew.isNegative()
                && !event.eventTime().isBefore(now.minus(maxAge))
                && !event.eventTime().isAfter(now.plus(futureSkew))
                && !event.publishedAt().isAfter(now.plus(futureSkew));
    }

    public void onFundingRate(ConsumerRecord<String, String> record) {
        try {
            requireCurrentProductTopic(record.topic(), fundingRateTopic(), "funding rate");
            PerpFundingRateEvent event = objectMapper.readValue(record.value(), PerpFundingRateEvent.class);
            KafkaSymbolKeyValidator.requireMatchingSymbol(record.key(), event.instrumentId(), "funding rate");
            registry.publish(topic(WsChannel.FUNDING_RATE, event.instrumentId(), null), event, event.eventTime());
        } catch (Exception ex) {
            log.error("Failed to fanout funding rate: {}", ex.getMessage(), ex);
            throw new IllegalStateException("failed to fanout funding rate", ex);
        }
    }

    @KafkaListener(
            topics = "#{__listener.fundingRateTopic()}",
            groupId = "#{__listener.groupId()}",
            autoStartup = "#{__listener.fundingRateListenerEnabled()}",
            containerFactory = "webSocketKafkaBatchListenerContainerFactory")
    public void onFundingRateBatch(List<ConsumerRecord<String, String>> records) {
        try {
            Map<SubscriptionTopic, List<TimedPayload>> grouped = new LinkedHashMap<>();
            for (ConsumerRecord<String, String> record : records) {

                requireCurrentProductTopic(record.topic(), fundingRateTopic(), "funding rate");
                PerpFundingRateEvent event = objectMapper.readValue(record.value(), PerpFundingRateEvent.class);
                KafkaSymbolKeyValidator.requireMatchingSymbol(record.key(), event.instrumentId(), "funding rate");
                addBatch(grouped, topic(WsChannel.FUNDING_RATE, event.instrumentId(), null), event, event.eventTime());

            }
            publishBatches(grouped);
        } catch (Exception ex) {
            log.error("Failed to batch fanout funding rate: {}", ex.getMessage(), ex);
            throw new IllegalStateException("failed to batch fanout funding rate", ex);
        }
    }

    public String groupId() {
        return properties.getKafka().getGroupId();
    }

    public String candleTopic() {
        return properties.getKafka().getCandleTopic();
    }

    public String priceEventsTopic() {
        return properties.getKafka().getPriceEventsTopic();
    }

    public String fundingRateTopic() {
        return properties.getKafka().getFundingRateTopic();
    }

    public boolean fundingRateListenerEnabled() {
        return properties.getKafka().isFundingRateTopicEnabled();
    }

    private void requireCurrentProductTopic(String topic, String expectedTopic, String streamName) {
        if (!expectedTopic.equals(topic)) {
            throw new ProductTopicMismatchException(streamName + " topic must match current product line: expected="
                    + expectedTopic + " actual=" + topic);
        }
    }

    private SubscriptionTopic topic(WsChannel channel, String instrumentId, Long userId) {
        return new SubscriptionTopic(channel, instrumentId, null, userId, fanoutProductLine());
    }

    private ProductLine fanoutProductLine() {
        return properties.getKafka().getProductLine();
    }

    static final class ProductTopicMismatchException extends RuntimeException {
        private ProductTopicMismatchException(String message) {
            super(message);
        }
    }

    private void addBatch(Map<SubscriptionTopic, List<TimedPayload>> grouped,
                          SubscriptionTopic topic,
                          Object payload,
                          Instant eventTime) {
        grouped.computeIfAbsent(topic, ignored -> new ArrayList<>())
                .add(new TimedPayload(payload, eventTime));
    }

    private void publishBatches(Map<SubscriptionTopic, List<TimedPayload>> grouped) {
        grouped.forEach(registry::publishTimedBatch);
    }
}
