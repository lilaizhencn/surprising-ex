package com.surprising.price.mark.consumer;

import com.surprising.price.api.model.PerpBookTickerEvent;
import com.surprising.price.api.model.PerpFundingRateEvent;
import com.surprising.price.mark.config.MarkPriceProperties;
import com.surprising.price.mark.exception.ProductTopicMismatchException;
import com.surprising.price.mark.service.MarkPriceService;
import com.surprising.price.mark.service.PublicTradeEventMapper;
import com.surprising.trading.api.KafkaSymbolKeyValidator;
import com.surprising.trading.api.model.PublicTradeEvent;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 标记价格的Kafka接入：绑定产品topic/消息/key后，交由唯一Service更新业务状态。 */
@Component
@Slf4j
public class MarkPriceConsumer {
    private final ObjectMapper objectMapper;
    private final MarkPriceProperties properties;
    private final PublicTradeEventMapper publicTradeEventMapper;
    private final MarkPriceService service;

    public MarkPriceConsumer(ObjectMapper objectMapper, MarkPriceProperties properties,
            PublicTradeEventMapper publicTradeEventMapper, MarkPriceService service) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.publicTradeEventMapper = publicTradeEventMapper;
        this.service = service;
    }

    @KafkaListener(topics = "#{__listener.bookTickerTopic()}", groupId = "#{__listener.groupId()}",
            autoStartup = "#{!${surprising.realtime.enabled:false}}")
    public void onBookTicker(ConsumerRecord<String, String> record) {
        requireCurrentProductTopic(record.topic(), bookTickerTopic(), "book ticker");
        parse(record.value(), PerpBookTickerEvent.class, "book ticker", service::acceptBookTicker);
    }

    @KafkaListener(topics = "#{__listener.matchTradesTopic()}", groupId = "#{__listener.groupId()}",
            autoStartup = "#{!${surprising.realtime.enabled:false}}")
    public void onTrade(ConsumerRecord<String, String> record) {
        requireCurrentProductTopic(record.topic(), matchTradesTopic(), "match trade");
        try {
            PublicTradeEvent event = objectMapper.readValue(record.value(), PublicTradeEvent.class);
            KafkaSymbolKeyValidator.requireMatchingSymbol(record.key(), event.instrumentId(), "match trade");
            service.acceptTrade(publicTradeEventMapper.toPerpTradeEvent(event));
        } catch (Exception ex) {
            log.warn("Dropped invalid match trade payload: {}", ex.getMessage());
        }
    }

    @KafkaListener(topics = "#{__listener.fundingRateTopic()}",
            groupId = "#{__listener.groupId()}",
            autoStartup = "#{__listener.fundingRateListenerEnabled()}")
    public void onFundingRate(ConsumerRecord<String, String> record) {
        requireCurrentProductTopic(record.topic(), fundingRateTopic(), "funding rate");
        parse(record.value(), PerpFundingRateEvent.class, "funding rate", service::acceptFundingRate);
    }

    public String bookTickerTopic() {
        return properties.bookTickerTopic();
    }

    public String matchTradesTopic() {
        return properties.matchTradesTopic();
    }

    public String fundingRateTopic() {
        return properties.fundingRateTopic();
    }

    public boolean fundingRateListenerEnabled() {
        return properties.isFundingRateExpected();
    }

    public String groupId() {
        return properties.getKafka().getGroupId();
    }

    private void requireCurrentProductTopic(String topic, String expectedTopic, String streamName) {
        if (!expectedTopic.equals(topic)) {
            throw new ProductTopicMismatchException(streamName + " topic must match current product line: expected="
                    + expectedTopic + " actual=" + topic);
        }
    }

    private <T> void parse(String payload, Class<T> type, String name, java.util.function.Consumer<T> consumer) {
        try {
            consumer.accept(objectMapper.readValue(payload, type));
        } catch (Exception ex) {
            log.warn("Dropped invalid {} payload: {}", name, ex.getMessage());
        }
    }

}
