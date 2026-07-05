package com.surprising.trading.trigger.service;

import com.surprising.trading.api.KafkaSymbolKeyValidator;
import com.surprising.trading.trigger.model.MarkTrigger;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

/**
 * Consumes index-price ticks for trigger orders using INDEX_PRICE as their trigger source.
 */
@Service
public class IndexPriceTriggerConsumer {

    private static final Logger log = LoggerFactory.getLogger(IndexPriceTriggerConsumer.class);

    private final IndexPriceTriggerParser parser;
    private final TriggerOrderService triggerOrderService;

    public IndexPriceTriggerConsumer(IndexPriceTriggerParser parser, TriggerOrderService triggerOrderService) {
        this.parser = parser;
        this.triggerOrderService = triggerOrderService;
    }

    @KafkaListener(
            topics = "${surprising.trading.trigger.kafka.index-price-topic}",
            groupId = "${surprising.trading.trigger.kafka.group-id}",
            containerFactory = "triggerKafkaListenerContainerFactory")
    public void onIndexPrice(ConsumerRecord<String, String> record) {
        try {
            MarkTrigger trigger = parser.parse(record.value());
            KafkaSymbolKeyValidator.requireMatchingSymbol(record.key(), trigger.symbol(), "index price");
            triggerOrderService.onIndexPrice(trigger);
        } catch (Exception ex) {
            log.error("Failed to process index price trigger: {}", ex.getMessage(), ex);
            throw new IllegalStateException("failed to process index price trigger", ex);
        }
    }
}
