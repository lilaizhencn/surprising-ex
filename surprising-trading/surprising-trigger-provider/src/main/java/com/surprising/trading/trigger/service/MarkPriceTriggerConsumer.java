package com.surprising.trading.trigger.service;

import com.surprising.trading.api.KafkaSymbolKeyValidator;
import com.surprising.trading.trigger.config.TriggerProperties;
import com.surprising.trading.trigger.model.MarkTrigger;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

/**
 * Keeps the newest mark-price event per symbol for the fixed-rate trigger scheduler.
 *
 * <p>The Kafka key must match the payload symbol; otherwise one symbol's trigger stream could lose
 * ordering guarantees by landing on the wrong partition.</p>
 */
@Service
public class MarkPriceTriggerConsumer {

    private static final Logger log = LoggerFactory.getLogger(MarkPriceTriggerConsumer.class);

    private final MarkPriceTriggerParser parser;
    private final MarkPriceTriggerScheduler triggerScheduler;
    private final TriggerProperties properties;

    public MarkPriceTriggerConsumer(MarkPriceTriggerParser parser, MarkPriceTriggerScheduler triggerScheduler) {
        this(parser, triggerScheduler, new TriggerProperties());
    }

    @Autowired
    public MarkPriceTriggerConsumer(MarkPriceTriggerParser parser,
                                    MarkPriceTriggerScheduler triggerScheduler,
                                    TriggerProperties properties) {
        this.parser = parser;
        this.triggerScheduler = triggerScheduler;
        this.properties = properties;
    }

    @KafkaListener(
            topics = "#{__listener.markPriceTopic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "triggerKafkaListenerContainerFactory")
    public void onMarkPrice(ConsumerRecord<String, String> record) {
        try {
            TriggerTopicGuard.requireCurrentProductTopic(properties, record.topic(), markPriceTopic(), "mark price");
            MarkTrigger trigger = parser.parse(record.value());
            KafkaSymbolKeyValidator.requireMatchingSymbol(record.key(), trigger.symbol(), "mark price");
            triggerScheduler.updateLatest(trigger);
        } catch (Exception ex) {
            log.error("Failed to process mark price trigger: {}", ex.getMessage(), ex);
            throw new IllegalStateException("failed to process mark price trigger", ex);
        }
    }

    public String markPriceTopic() {
        return properties.getKafka().getMarkPriceTopic();
    }

    public String groupId() {
        return properties.getKafka().getGroupId();
    }
}
