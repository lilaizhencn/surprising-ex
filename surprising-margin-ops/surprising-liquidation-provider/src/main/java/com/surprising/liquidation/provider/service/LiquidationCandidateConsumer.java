package com.surprising.liquidation.provider.service;

import com.surprising.liquidation.provider.config.LiquidationProperties;
import com.surprising.risk.api.model.LiquidationCandidateEvent;
import com.surprising.trading.api.KafkaSymbolKeyValidator;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class LiquidationCandidateConsumer {

    private static final Logger log = LoggerFactory.getLogger(LiquidationCandidateConsumer.class);

    private final ObjectMapper objectMapper;
    private final LiquidationService liquidationService;
    private final LiquidationCandidateQueueProcessor queueProcessor;
    private final LiquidationProperties properties;

    public LiquidationCandidateConsumer(ObjectMapper objectMapper, LiquidationService liquidationService) {
        this(objectMapper, liquidationService, null, new LiquidationProperties());
    }

    public LiquidationCandidateConsumer(ObjectMapper objectMapper,
                                        LiquidationService liquidationService,
                                        LiquidationProperties properties) {
        this(objectMapper, liquidationService, null, properties);
    }

    @Autowired
    public LiquidationCandidateConsumer(ObjectMapper objectMapper,
                                        LiquidationService liquidationService,
                                        LiquidationCandidateQueueProcessor queueProcessor,
                                        LiquidationProperties properties) {
        this.objectMapper = objectMapper;
        this.liquidationService = liquidationService;
        this.queueProcessor = queueProcessor;
        this.properties = properties;
    }

    @KafkaListener(
            topics = "#{__listener.liquidationCandidatesTopic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "liquidationKafkaListenerContainerFactory")
    public void onCandidate(ConsumerRecord<String, String> record) {
        try {
            LiquidationCandidateEvent event = objectMapper.readValue(record.value(), LiquidationCandidateEvent.class);
            KafkaSymbolKeyValidator.requireMatchingSymbol(record.key(), event.symbol(), "liquidation candidate");
            requireCurrentProductTopic(record.topic());
            if (queueProcessor == null) liquidationService.processCandidate(event); else queueProcessor.enqueueAndDrain(event);
        } catch (Exception ex) {
            log.error("Failed to process liquidation candidate: {}", ex.getMessage(), ex);
            throw new IllegalStateException("failed to process liquidation candidate", ex);
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
