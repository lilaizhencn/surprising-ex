package com.surprising.price.index.service;

import com.surprising.price.api.model.IndexPriceEvent;
import com.surprising.price.index.config.IndexPriceProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Maintains the local latest-index cache from the same business topic used by mark price. */
@Component
public class IndexPriceKafkaConsumer {

    private static final Logger log = LoggerFactory.getLogger(IndexPriceKafkaConsumer.class);

    private final ObjectMapper objectMapper;
    private final LatestIndexPriceCache cache;
    private final IndexPriceProperties properties;

    public IndexPriceKafkaConsumer(ObjectMapper objectMapper,
                                   LatestIndexPriceCache cache,
                                   IndexPriceProperties properties) {
        this.objectMapper = objectMapper;
        this.cache = cache;
        this.properties = properties;
    }

    @KafkaListener(
            topics = "#{__listener.indexPriceTopic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "indexPriceCacheKafkaListenerContainerFactory")
    public void onIndexPrice(ConsumerRecord<String, String> record) {
        try {
            IndexPriceEvent event = objectMapper.readValue(record.value(), IndexPriceEvent.class);
            if (record.key() == null || !record.key().equals(event.symbol())) {
                throw new IllegalArgumentException("index price Kafka key must match payload symbol");
            }
            cache.update(event);
        } catch (Exception ex) {
            log.error("Failed to cache index price topic={} partition={} offset={}: {}",
                    record.topic(), record.partition(), record.offset(), ex.getMessage(), ex);
            throw new IllegalStateException("failed to cache index price", ex);
        }
    }

    public String indexPriceTopic() {
        return properties.getKafka().getIndexPriceTopic();
    }

    public String groupId() {
        return properties.getKafka().getCacheGroupId();
    }
}
