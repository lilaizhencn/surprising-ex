package com.surprising.trading.trigger.service;

import com.surprising.trading.api.KafkaSymbolKeyValidator;
import com.surprising.trading.trigger.config.TriggerProperties;
import com.surprising.trading.trigger.model.LastPriceTrigger;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final TriggerProperties properties;

    public LastPriceTriggerConsumer(LastPriceTriggerParser parser, TriggerOrderService triggerOrderService) {
        this(parser, triggerOrderService, new TriggerProperties());
    }

    @Autowired
    public LastPriceTriggerConsumer(LastPriceTriggerParser parser,
                                    TriggerOrderService triggerOrderService,
                                    TriggerProperties properties) {
        this.parser = parser;
        this.triggerOrderService = triggerOrderService;
        this.properties = properties;
    }

    @KafkaListener(
            topics = "#{__listener.lastPriceTopic()}",
            groupId = "#{__listener.groupId()}",
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

    public String lastPriceTopic() {
        return properties.getKafka().getLastPriceTopic();
    }

    public String groupId() {
        return properties.getKafka().getGroupId();
    }
}
