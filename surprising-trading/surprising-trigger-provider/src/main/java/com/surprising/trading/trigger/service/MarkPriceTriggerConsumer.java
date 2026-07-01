package com.surprising.trading.trigger.service;

import com.surprising.trading.api.KafkaSymbolKeyValidator;
import com.surprising.trading.trigger.model.MarkTrigger;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

/**
 * Consumes mark-price ticks as the sole trigger clock for TP/SL orders.
 *
 * <p>The Kafka key must match the payload symbol; otherwise one symbol's trigger stream could lose
 * ordering guarantees by landing on the wrong partition.</p>
 */
@Service
public class MarkPriceTriggerConsumer {

    private static final Logger log = LoggerFactory.getLogger(MarkPriceTriggerConsumer.class);

    private final MarkPriceTriggerParser parser;
    private final TriggerOrderService triggerOrderService;

    public MarkPriceTriggerConsumer(MarkPriceTriggerParser parser, TriggerOrderService triggerOrderService) {
        this.parser = parser;
        this.triggerOrderService = triggerOrderService;
    }

    @KafkaListener(
            topics = "${surprising.trading.trigger.kafka.mark-price-topic}",
            groupId = "${surprising.trading.trigger.kafka.group-id}",
            containerFactory = "triggerKafkaListenerContainerFactory")
    public void onMarkPrice(ConsumerRecord<String, String> record) {
        try {
            MarkTrigger trigger = parser.parse(record.value());
            KafkaSymbolKeyValidator.requireMatchingSymbol(record.key(), trigger.symbol(), "mark price");
            triggerOrderService.onMarkPrice(trigger);
        } catch (Exception ex) {
            log.error("Failed to process mark price trigger: {}", ex.getMessage(), ex);
            throw new IllegalStateException("failed to process mark price trigger", ex);
        }
    }
}
