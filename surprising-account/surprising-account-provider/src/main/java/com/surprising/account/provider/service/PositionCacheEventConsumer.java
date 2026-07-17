package com.surprising.account.provider.service;

import com.surprising.account.api.model.PositionCacheEvent;
import com.surprising.account.provider.config.AccountProperties;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class PositionCacheEventConsumer {

    private final ObjectMapper objectMapper;
    private final RedisPositionCache cache;
    private final AccountProperties properties;

    public PositionCacheEventConsumer(ObjectMapper objectMapper,
                                      RedisPositionCache cache,
                                      AccountProperties properties) {
        this.objectMapper = objectMapper;
        this.cache = cache;
        this.properties = properties;
    }

    @KafkaListener(
            topics = "#{__listener.topic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "accountKafkaListenerContainerFactory")
    public void onEvents(List<ConsumerRecord<String, String>> records) {
        for (ConsumerRecord<String, String> record : records) {
            onEvent(record);
        }
    }

    public void onEvent(ConsumerRecord<String, String> record) {
        PositionCacheEvent event = null;
        try {
            requireTopic(record.topic());
            event = objectMapper.readValue(record.value(), PositionCacheEvent.class);
            requireScope(record.key(), event);
            cache.apply(event, false);
        } catch (Exception ex) {
            if (event != null) {
                cache.markNotReady(event.productLine());
            } else {
                cache.markNotReady(properties.getKafka().getProductLine());
            }
            throw new IllegalStateException("failed to apply position cache event", ex);
        }
    }

    public String topic() {
        return properties.getKafka().getPositionCacheEventsTopic();
    }

    public String groupId() {
        return properties.getKafka().getPositionCacheGroupId();
    }

    private void requireTopic(String topic) {
        if (!topic().equals(topic)) {
            throw new IllegalArgumentException(
                    "position cache event topic mismatch: expected=" + topic() + " actual=" + topic);
        }
    }

    private void requireScope(String key, PositionCacheEvent event) {
        if (event.productLine() != properties.getKafka().getProductLine()) {
            throw new IllegalArgumentException(
                    "position cache event product line mismatch: expected="
                            + properties.getKafka().getProductLine() + " actual=" + event.productLine());
        }
        String expectedKey = event.productLine().name() + ":" + event.userId();
        if (!expectedKey.equals(key)) {
            throw new IllegalArgumentException(
                    "position cache event key mismatch: expected=" + expectedKey + " actual=" + key);
        }
    }
}
