package com.surprising.liquidation.provider.service;

import com.surprising.liquidation.provider.config.LiquidationProperties;
import com.surprising.risk.api.model.LiquidationCandidateEvent;
import com.surprising.trading.api.KafkaSymbolKeyValidator;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class LiquidationCandidateConsumer {

    private static final Logger log = LoggerFactory.getLogger(LiquidationCandidateConsumer.class);

    private final ObjectMapper objectMapper;
    private final LiquidationCandidateQueueProcessor queueProcessor;
    private final LiquidationProperties properties;

    public LiquidationCandidateConsumer(ObjectMapper objectMapper,
                                        LiquidationCandidateQueueProcessor queueProcessor,
                                        LiquidationProperties properties) {
        this.objectMapper = objectMapper;
        this.queueProcessor = queueProcessor;
        this.properties = properties;
    }

    @KafkaListener(
            topics = "#{__listener.liquidationCandidatesTopic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "liquidationCandidateKafkaListenerContainerFactory")
    public void onCandidates(List<ConsumerRecord<String, String>> records) {
        try {
            List<LiquidationCandidateEvent> events = new ArrayList<>(records.size());
            for (ConsumerRecord<String, String> record : records) {
                LiquidationCandidateEvent event = objectMapper.readValue(record.value(), LiquidationCandidateEvent.class);
                KafkaSymbolKeyValidator.requireMatchingSymbol(record.key(), event.symbol(), "liquidation candidate");
                requireCurrentProductTopic(record.topic());
                events.add(event);
            }
            queueProcessor.enqueue(events);
        } catch (Exception ex) {
            log.error("Failed to enqueue liquidation candidate batch: {}", ex.getMessage(), ex);
            throw new IllegalStateException("failed to enqueue liquidation candidate batch", ex);
        }
    }

    public String liquidationCandidatesTopic() {
        return properties.getKafka().getLiquidationCandidatesTopic();
    }

    public String groupId() {
        return properties.getKafka().getGroupId();
    }

    private void requireCurrentProductTopic(String topic) {
        LiquidationProperties.Kafka kafka = properties.getKafka();
        if (!kafka.isProductTopicsEnabled()) {
            return;
        }
        String expectedTopic = kafka.getLiquidationCandidatesTopic();
        if (!expectedTopic.equals(topic)) {
            throw new ProductTopicMismatchException("liquidation candidate topic must match current product line: expected="
                    + expectedTopic + " actual=" + topic);
        }
    }

    private static final class ProductTopicMismatchException extends RuntimeException {
        private ProductTopicMismatchException(String message) {
            super(message);
        }
    }
}
