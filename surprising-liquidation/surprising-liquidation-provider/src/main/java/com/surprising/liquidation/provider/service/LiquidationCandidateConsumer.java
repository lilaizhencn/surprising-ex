package com.surprising.liquidation.provider.service;

import com.surprising.risk.api.model.LiquidationCandidateEvent;
import com.surprising.trading.api.KafkaSymbolKeyValidator;
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
    private final LiquidationService liquidationService;

    public LiquidationCandidateConsumer(ObjectMapper objectMapper, LiquidationService liquidationService) {
        this.objectMapper = objectMapper;
        this.liquidationService = liquidationService;
    }

    @KafkaListener(
            topics = "${surprising.liquidation.kafka.liquidation-candidates-topic}",
            groupId = "${surprising.liquidation.kafka.group-id}",
            containerFactory = "liquidationKafkaListenerContainerFactory")
    public void onCandidate(ConsumerRecord<String, String> record) {
        try {
            LiquidationCandidateEvent event = objectMapper.readValue(record.value(), LiquidationCandidateEvent.class);
            KafkaSymbolKeyValidator.requireMatchingSymbol(record.key(), event.symbol(), "liquidation candidate");
            liquidationService.processCandidate(event);
        } catch (Exception ex) {
            log.error("Failed to process liquidation candidate: {}", ex.getMessage(), ex);
            throw new IllegalStateException("failed to process liquidation candidate", ex);
        }
    }
}
