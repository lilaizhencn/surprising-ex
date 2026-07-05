package com.surprising.trading.trigger.service;

import com.surprising.trading.api.KafkaSymbolKeyValidator;
import com.surprising.trading.trigger.model.LastPriceTrigger;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

/**
 * Consumes match trades for trigger orders using LAST_PRICE as their trigger source.
 */
@Service
public class LastPriceTriggerConsumer {

    private static final Logger log = LoggerFactory.getLogger(LastPriceTriggerConsumer.class);

    private final LastPriceTriggerParser parser;
    private final TriggerOrderService triggerOrderService;

    public LastPriceTriggerConsumer(LastPriceTriggerParser parser, TriggerOrderService triggerOrderService) {
        this.parser = parser;
        this.triggerOrderService = triggerOrderService;
    }

    @KafkaListener(
            topics = "${surprising.trading.trigger.kafka.last-price-topic}",
            groupId = "${surprising.trading.trigger.kafka.group-id}",
            containerFactory = "triggerKafkaListenerContainerFactory")
    public void onLastPrice(ConsumerRecord<String, String> record) {
        try {
            LastPriceTrigger trigger = parser.parse(record.value());
            KafkaSymbolKeyValidator.requireMatchingSymbol(record.key(), trigger.symbol(), "last price");
            triggerOrderService.onLastPrice(trigger);
        } catch (Exception ex) {
            log.error("Failed to process last price trigger: {}", ex.getMessage(), ex);
            throw new IllegalStateException("failed to process last price trigger", ex);
        }
    }
}
