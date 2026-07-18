package com.surprising.risk.provider.service;

import com.surprising.account.api.model.PositionUpdatedEvent;
import com.surprising.risk.provider.config.RiskProperties;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class PositionRiskTriggerConsumer {

    private static final Logger log = LoggerFactory.getLogger(PositionRiskTriggerConsumer.class);

    private final ObjectMapper objectMapper;
    private final RiskService riskService;
    private final RiskProperties properties;

    public PositionRiskTriggerConsumer(ObjectMapper objectMapper, RiskService riskService) {
        this(objectMapper, riskService, new RiskProperties());
    }

    @Autowired
    public PositionRiskTriggerConsumer(ObjectMapper objectMapper, RiskService riskService, RiskProperties properties) {
        this.objectMapper = objectMapper;
        this.riskService = riskService;
        this.properties = properties;
    }

    /**
     * Account position events are consumed in durable Kafka batches. RiskService coalesces every batch by risk group
     * and exact position before scanning, while Kafka retry plus the DB lease preserve at-least-once safety.
     */
    @KafkaListener(
            topics = "#{__listener.positionEventsTopic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "riskKafkaListenerContainerFactory")
    public void onPositionUpdated(List<ConsumerRecord<String, String>> records) {
        try {
            if (records == null || records.isEmpty()) {
                return;
            }
            List<PositionUpdatedEvent> events = new ArrayList<>(records.size());
            for (ConsumerRecord<String, String> record : records) {
                PositionUpdatedEvent event = objectMapper.readValue(record.value(), PositionUpdatedEvent.class);
                requireCurrentProductTopic(record.topic());
                requireUserPartitionKey(record.key(), event);
                events.add(event);
            }
            riskService.scanPositionUpdates(events);
        } catch (Exception ex) {
            int recordCount = records == null ? 0 : records.size();
            log.error("Failed to process position risk trigger batch records={}: {}",
                    recordCount, ex.getMessage(), ex);
            throw new IllegalStateException("failed to process position risk trigger batch", ex);
        }
    }

    public String positionEventsTopic() {
        return properties.getKafka().getPositionEventsTopic();
    }

    public String groupId() {
        return properties.getKafka().getGroupId();
    }

    private void requireCurrentProductTopic(String topic) {
        RiskProperties.Kafka kafka = properties.getKafka();
        if (!kafka.isProductTopicsEnabled()) {
            return;
        }
        String expectedTopic = kafka.getPositionEventsTopic();
        if (!expectedTopic.equals(topic)) {
            throw new ProductTopicMismatchException("position update topic must match current product line: expected="
                    + expectedTopic + " actual=" + topic);
        }
    }

    private void requireUserPartitionKey(String key, PositionUpdatedEvent event) {
        if (event.productLine() != properties.getKafka().getProductLine()) {
            throw new ProductTopicMismatchException("position update product line must match current risk provider");
        }
        if (!event.partitionKey().equals(key)) {
            throw new IllegalArgumentException("position update Kafka key must be " + event.partitionKey());
        }
    }

    private static final class ProductTopicMismatchException extends RuntimeException {
        private ProductTopicMismatchException(String message) {
            super(message);
        }
    }
}
