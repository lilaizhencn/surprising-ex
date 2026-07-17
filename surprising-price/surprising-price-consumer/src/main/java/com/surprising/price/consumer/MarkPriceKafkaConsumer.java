package com.surprising.price.consumer;

import com.surprising.price.api.model.MarkPricePublishedEvent;
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
            MarkPricePublishedEvent publication = objectMapper.readValue(
                    record.value(), MarkPricePublishedEvent.class);
            if (publication.result() == null) {
                throw new IllegalArgumentException("mark price publication result is required");
            }
            if (record.key() == null || !record.key().equals(publication.result().symbol())) {
                throw new IllegalArgumentException("mark price Kafka key must match payload symbol");
            }
            cache.update(publication.result());
        } catch (Exception ex) {
            // A malformed upstream message can never succeed on retry.  Consume
            // it after logging so it cannot stall the symbol's live-price
            // partition; valid later publications remain usable immediately.
            log.warn("Discarding invalid mark price topic={} partition={} offset={}: {}",
                    record.topic(), record.partition(), record.offset(), ex.getMessage(), ex);
        }
    }

    public String topic() {
        return properties.resolvedTopic();
    }
}
