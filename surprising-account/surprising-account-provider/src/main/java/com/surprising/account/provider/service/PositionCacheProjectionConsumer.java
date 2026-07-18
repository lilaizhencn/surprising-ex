package com.surprising.account.provider.service;

import com.surprising.account.api.model.PositionUpdatedEvent;
import com.surprising.account.provider.config.AccountProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Durable recovery path for the Redis position read model.
 *
 * <p>The transaction-local accelerator normally wins the race. This consumer makes the projection lossless across
 * process exits, queue overflow, Redis outages, and rolling deployments. Revision CAS makes duplicate delivery cheap
 * and safe.</p>
 */
@Service
public class PositionCacheProjectionConsumer {

    private static final Logger log = LoggerFactory.getLogger(PositionCacheProjectionConsumer.class);

    private final ObjectMapper objectMapper;
    private final RedisPositionCache cache;
    private final AccountProperties properties;

    public PositionCacheProjectionConsumer(ObjectMapper objectMapper,
                                           RedisPositionCache cache,
                                           AccountProperties properties) {
        this.objectMapper = objectMapper;
        this.cache = cache;
        this.properties = properties;
    }

    @KafkaListener(
            topics = "#{__listener.positionEventsTopic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "accountPositionCacheKafkaListenerContainerFactory")
    public void onPositionUpdated(ConsumerRecord<String, String> record) {
        try {
            requireCurrentProductTopic(record.topic());
            PositionUpdatedEvent event = objectMapper.readValue(record.value(), PositionUpdatedEvent.class);
            if (event.productLine() != properties.getKafka().getProductLine()) {
                throw new IllegalArgumentException("position event product line does not match account provider");
            }
            if (!event.partitionKey().equals(record.key())) {
                throw new IllegalArgumentException("position event Kafka key must be " + event.partitionKey());
            }
            cache.apply(event.cacheEvent(), false);
        } catch (Exception ex) {
            cache.markNotReady(properties.getKafka().getProductLine());
            log.error("Failed to project durable position event topic={} partition={} offset={}: {}",
                    record.topic(), record.partition(), record.offset(), ex.getMessage(), ex);
            throw new IllegalStateException("failed to project durable position event", ex);
        }
    }

    public String positionEventsTopic() {
        return properties.getKafka().getPositionEventsTopic();
    }

    public String groupId() {
        return properties.getKafka().getPositionCacheGroupId();
    }

    private void requireCurrentProductTopic(String topic) {
        String expected = positionEventsTopic();
        if (!expected.equals(topic)) {
            throw new IllegalArgumentException(
                    "position event topic must match current product line: expected=" + expected + " actual=" + topic);
        }
    }
}
