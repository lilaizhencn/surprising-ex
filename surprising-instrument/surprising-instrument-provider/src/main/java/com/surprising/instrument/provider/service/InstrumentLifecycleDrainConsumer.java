package com.surprising.instrument.provider.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.surprising.instrument.api.model.InstrumentLifecycleDrainEvent;
import com.surprising.instrument.provider.config.InstrumentProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
public class InstrumentLifecycleDrainConsumer {

    private final ObjectMapper objectMapper;
    private final InstrumentProperties properties;
    private final InstrumentLifecycleReadinessService readinessService;

    public InstrumentLifecycleDrainConsumer(ObjectMapper objectMapper,
                                            InstrumentProperties properties,
                                            InstrumentLifecycleReadinessService readinessService) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.readinessService = readinessService;
    }

    @KafkaListener(
            topics = "#{__listener.topic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "instrumentLifecycleKafkaListenerContainerFactory")
    public void onDrainReady(ConsumerRecord<String, String> record) {
        try {
            if (!topic().equals(record.topic())) {
                throw new IllegalArgumentException("生命周期清理 Topic 不匹配: " + record.topic());
            }
            InstrumentLifecycleDrainEvent event =
                    objectMapper.readValue(record.value(), InstrumentLifecycleDrainEvent.class);
            if (!event.symbol().equals(record.key())) {
                throw new IllegalArgumentException("生命周期清理事件必须使用 symbol 作为 Kafka key");
            }
            readinessService.acknowledge(event);
        } catch (Exception ex) {
            throw new IllegalStateException("生命周期清理确认消费失败", ex);
        }
    }

    public String topic() {
        return properties.getKafka().getLifecycleDrainTopic();
    }

    public String groupId() {
        return properties.getKafka().getLifecycleDrainGroupId();
    }
}
