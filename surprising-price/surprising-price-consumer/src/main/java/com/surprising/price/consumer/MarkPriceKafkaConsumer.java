package com.surprising.price.consumer;

import com.surprising.price.api.model.MarkPriceEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class MarkPriceKafkaConsumer {

    private static final Logger log = LoggerFactory.getLogger(MarkPriceKafkaConsumer.class);

    private final ObjectMapper objectMapper;
    private final LatestMarkPriceCache cache;
    private final MarkPriceConsumerProperties properties;

    public MarkPriceKafkaConsumer(ObjectMapper objectMapper,
                                  LatestMarkPriceCache cache,
                                  MarkPriceConsumerProperties properties) {
        this.objectMapper = objectMapper;
        this.cache = cache;
        this.properties = properties;
    }

    @KafkaListener(
            topics = "#{@markPriceConsumerProperties.resolvedTopic()}",
            groupId = "#{@markPriceConsumerProperties.groupId}",
            containerFactory = "markPriceCacheKafkaListenerContainerFactory")
    public void onMarkPrice(ConsumerRecord<String, String> record) {
        try {
            MarkPriceEvent event = objectMapper.readValue(record.value(), MarkPriceEvent.class);
            if (record.key() == null || !record.key().equals(event.symbol())) {
                throw new IllegalArgumentException("mark price Kafka key must match payload symbol");
            }
            cache.update(event);
        } catch (Exception ex) {
            log.error("Failed to cache mark price topic={} partition={} offset={}: {}",
                    record.topic(), record.partition(), record.offset(), ex.getMessage(), ex);
            throw new IllegalStateException("failed to cache mark price", ex);
        }
    }

    public String topic() {
        return properties.resolvedTopic();
    }
}
