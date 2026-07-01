package com.surprising.liquidation.provider.service;

import com.surprising.trading.api.KafkaSymbolKeyValidator;
import com.surprising.trading.api.model.MatchResultEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class LiquidationMatchResultConsumer {

    private static final Logger log = LoggerFactory.getLogger(LiquidationMatchResultConsumer.class);

    private final ObjectMapper objectMapper;
    private final LiquidationService liquidationService;

    public LiquidationMatchResultConsumer(ObjectMapper objectMapper, LiquidationService liquidationService) {
        this.objectMapper = objectMapper;
        this.liquidationService = liquidationService;
    }

    @KafkaListener(
            topics = "${surprising.liquidation.kafka.match-results-topic}",
            groupId = "${surprising.liquidation.kafka.group-id}",
            containerFactory = "liquidationKafkaListenerContainerFactory")
    public void onMatchResult(ConsumerRecord<String, String> record) {
        try {
            MatchResultEvent event = objectMapper.readValue(record.value(), MatchResultEvent.class);
            KafkaSymbolKeyValidator.requireMatchingSymbol(record.key(), event.symbol(), "liquidation match result");
            liquidationService.processMatchResult(event);
        } catch (Exception ex) {
            log.error("Failed to process liquidation match result: {}", ex.getMessage(), ex);
            throw new IllegalStateException("failed to process liquidation match result", ex);
        }
    }
}
