package com.surprising.trading.trigger.service;

import com.surprising.account.api.model.PositionUpdatedEvent;
import com.surprising.trading.trigger.config.TriggerProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Reconciles the Redis trigger index after account commits a fully closed position. */
@Service
public class PositionClosedTriggerConsumer {

    private static final Logger log = LoggerFactory.getLogger(PositionClosedTriggerConsumer.class);

    private final ObjectMapper objectMapper;
    private final TriggerOrderService triggerOrderService;
    private final TriggerProperties properties;

    public PositionClosedTriggerConsumer(ObjectMapper objectMapper,
                                         TriggerOrderService triggerOrderService,
                                         TriggerProperties properties) {
        this.objectMapper = objectMapper;
        this.triggerOrderService = triggerOrderService;
        this.properties = properties;
    }

    @KafkaListener(
            topics = "#{__listener.positionEventsTopic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "triggerKafkaListenerContainerFactory")
    public void onPositionUpdated(ConsumerRecord<String, String> record) {
        try {
            TriggerTopicGuard.requireCurrentProductTopic(
                    properties, record.topic(), positionEventsTopic(), "position update");
            PositionUpdatedEvent event = objectMapper.readValue(record.value(), PositionUpdatedEvent.class);
            requireUserPartitionKey(record.key(), event);
            if (event.signedQuantitySteps() == 0L) {
                triggerOrderService.onPositionClosed(event);
            }
        } catch (Exception ex) {
            log.error("Failed to process closed position trigger cleanup: {}", ex.getMessage(), ex);
            throw new IllegalStateException("failed to process closed position trigger cleanup", ex);
        }
    }

    public String positionEventsTopic() {
        return properties.getKafka().getPositionEventsTopic();
    }

    public String groupId() {
        return properties.getKafka().getGroupId();
    }

    private void requireUserPartitionKey(String key, PositionUpdatedEvent event) {
        if (event.productLine() != properties.getKafka().getProductLine()) {
            throw new IllegalArgumentException("position update product line must match current trigger provider");
        }
        if (!event.partitionKey().equals(key)) {
            throw new IllegalArgumentException("position update Kafka key must be " + event.partitionKey());
        }
    }
}
