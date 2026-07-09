package com.surprising.risk.provider.service;

import com.surprising.account.api.model.PositionUpdatedEvent;
import com.surprising.risk.provider.config.RiskProperties;
import com.surprising.trading.api.KafkaSymbolKeyValidator;
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
     * Account position events are the low-latency trigger for risk scans. Kafka retry plus the DB scan lease make the
     * handler safe for at-least-once delivery and multi-node risk deployments.
     */
    @KafkaListener(
            topics = "#{__listener.positionEventsTopic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "riskKafkaListenerContainerFactory")
    public void onPositionUpdated(ConsumerRecord<String, String> record) {
        try {
            PositionUpdatedEvent event = objectMapper.readValue(record.value(), PositionUpdatedEvent.class);
            KafkaSymbolKeyValidator.requireMatchingSymbol(record.key(), event.symbol(), "position update");
            requireCurrentProductTopic(record.topic());
            riskService.scanPositionUpdate(event.userId(), event.symbol(), event.marginMode(), event.positionSide(),
                    event.instrumentVersion(), event.traceId());
        } catch (Exception ex) {
            log.error("Failed to process position risk trigger: {}", ex.getMessage(), ex);
            throw new IllegalStateException("failed to process position risk trigger", ex);
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

    private static final class ProductTopicMismatchException extends RuntimeException {
        private ProductTopicMismatchException(String message) {
            super(message);
        }
    }
}
