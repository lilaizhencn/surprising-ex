package com.surprising.account.provider.service;

import com.surprising.account.api.model.PositionUpdatedEvent;
import com.surprising.account.api.cache.PositionSnapshotCache;
import com.surprising.account.provider.config.AccountProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final PositionSnapshotCache snapshotCache;
    private final AccountProperties properties;

    /** Spring 使用带本地快照的构造器；旧测试构造器保留，避免改变投影测试的边界。 */
    @Autowired
    public PositionCacheProjectionConsumer(ObjectMapper objectMapper,
                                           RedisPositionCache cache,
                                           AccountProperties properties,
                                           PositionSnapshotCache snapshotCache) {
        this.objectMapper = objectMapper;
        this.cache = cache;
        this.snapshotCache = snapshotCache;
        this.properties = properties;
    }

    public PositionCacheProjectionConsumer(ObjectMapper objectMapper,
                                           RedisPositionCache cache,
                                           AccountProperties properties) {
        this(objectMapper, cache, properties,
                new PositionSnapshotCache(properties.getKafka().getProductLine()));
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
            PositionSnapshotCache.ApplyResult result = snapshotCache.apply(event);
            if (result == PositionSnapshotCache.ApplyResult.PRODUCT_LINE_MISMATCH) {
                throw new IllegalArgumentException("position event does not match local snapshot product line");
            }
            if (result == PositionSnapshotCache.ApplyResult.CONFLICT) {
                throw new IllegalStateException("position event has conflicting same-revision state");
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
