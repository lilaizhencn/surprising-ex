package com.surprising.liquidation.provider.service;

import com.surprising.liquidation.provider.config.LiquidationProperties;
import com.surprising.trading.api.KafkaSymbolKeyValidator;
import com.surprising.trading.api.model.MatchResultEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class LiquidationMatchResultConsumer {

    private static final Logger log = LoggerFactory.getLogger(LiquidationMatchResultConsumer.class);

    private final ObjectMapper objectMapper;
    private final LiquidationService liquidationService;
    private final LiquidationProperties properties;

    public LiquidationMatchResultConsumer(ObjectMapper objectMapper, LiquidationService liquidationService) {
        this(objectMapper, liquidationService, new LiquidationProperties());
    }

    @Autowired
    public LiquidationMatchResultConsumer(ObjectMapper objectMapper,
                                          LiquidationService liquidationService,
                                          LiquidationProperties properties) {
        this.objectMapper = objectMapper;
        this.liquidationService = liquidationService;
        this.properties = properties;
    }

    @KafkaListener(
            topics = "#{__listener.matchResultsTopic()}",
            groupId = "#{__listener.groupId()}",
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

    public String matchResultsTopic() {
        return properties.getKafka().getMatchResultsTopic();
    }

    public String groupId() {
        return properties.getKafka().getGroupId();
    }
}
