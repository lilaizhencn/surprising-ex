package com.surprising.trading.trigger.service;

import com.surprising.instrument.api.model.InstrumentEvent;
import com.surprising.instrument.api.InstrumentEventKeys;
import com.surprising.trading.trigger.config.TriggerProperties;
import com.surprising.product.api.ProductTopicNames;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class InstrumentTriggerDrainConsumer {

    private final ObjectMapper objectMapper;
    private final TriggerProperties properties;
    private final InstrumentTriggerDrainService drainService;

    public InstrumentTriggerDrainConsumer(ObjectMapper objectMapper,
                                          TriggerProperties properties,
                                          InstrumentTriggerDrainService drainService) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.drainService = drainService;
    }

    @KafkaListener(
            topics = "#{__listener.topic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "triggerInstrumentLifecycleKafkaListenerContainerFactory")
    public void onInstrumentEvent(ConsumerRecord<String, String> record) {
        try {
            if (!topic().equals(record.topic())) {
                throw new IllegalArgumentException("instrument Topic 不匹配: " + record.topic());
            }
            InstrumentEvent event = objectMapper.readValue(record.value(), InstrumentEvent.class);
            if (!InstrumentEventKeys.matches(record.key(), event)) {
                throw new IllegalArgumentException("instrument 事件必须使用 symbol 作为 Kafka key");
            }
            drainService.drain(event);
        } catch (Exception ex) {
            throw new IllegalStateException("触发单到期清理失败", ex);
        }
    }

    public String topic() {
        return ProductTopicNames.INSTRUMENT_EVENTS_TOPIC;
    }

    public String groupId() {
        return properties.getKafka().getInstrumentLifecycleGroupId();
    }
}
