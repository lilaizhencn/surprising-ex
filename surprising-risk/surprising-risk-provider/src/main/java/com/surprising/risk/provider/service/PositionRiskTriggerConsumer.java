package com.surprising.risk.provider.service;

import com.surprising.account.api.model.PositionUpdatedEvent;
import com.surprising.trading.api.KafkaSymbolKeyValidator;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class PositionRiskTriggerConsumer {

    private static final Logger log = LoggerFactory.getLogger(PositionRiskTriggerConsumer.class);

    private final ObjectMapper objectMapper;
    private final RiskService riskService;

    public PositionRiskTriggerConsumer(ObjectMapper objectMapper, RiskService riskService) {
        this.objectMapper = objectMapper;
        this.riskService = riskService;
    }

    /**
     * Account position events are the low-latency trigger for risk scans. Kafka retry plus the DB scan lease make the
     * handler safe for at-least-once delivery and multi-node risk deployments.
     */
    @KafkaListener(
            topics = "${surprising.risk.kafka.position-events-topic}",
            groupId = "${surprising.risk.kafka.group-id}",
            containerFactory = "riskKafkaListenerContainerFactory")
    public void onPositionUpdated(ConsumerRecord<String, String> record) {
        try {
            PositionUpdatedEvent event = objectMapper.readValue(record.value(), PositionUpdatedEvent.class);
            KafkaSymbolKeyValidator.requireMatchingSymbol(record.key(), event.symbol(), "position update");
            riskService.scanPositionUpdate(event.userId(), event.symbol(), event.instrumentVersion());
        } catch (Exception ex) {
            log.error("Failed to process position risk trigger: {}", ex.getMessage(), ex);
            throw new IllegalStateException("failed to process position risk trigger", ex);
        }
    }
}
