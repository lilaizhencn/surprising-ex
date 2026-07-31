package com.surprising.account.provider.service;

import com.surprising.account.provider.config.AccountProperties;
import com.surprising.instrument.api.model.InstrumentLifecycleDrainEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class AccountInstrumentDrainConsumer {

    private final ObjectMapper objectMapper;
    private final AccountProperties properties;
    private final AccountInstrumentDrainService drainService;

    public AccountInstrumentDrainConsumer(ObjectMapper objectMapper,
                                          AccountProperties properties,
                                          AccountInstrumentDrainService drainService) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.drainService = drainService;
    }

    @KafkaListener(
            topics = "#{__listener.topic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "accountInstrumentLifecycleKafkaListenerContainerFactory")
    public void onOrderDrainReady(ConsumerRecord<String, String> record) {
        try {
            if (!topic().equals(record.topic())) {
                throw new IllegalArgumentException("生命周期清理 Topic 不匹配: " + record.topic());
            }
            InstrumentLifecycleDrainEvent event =
                    objectMapper.readValue(record.value(), InstrumentLifecycleDrainEvent.class);
            if (!event.symbol().equals(record.key())) {
                throw new IllegalArgumentException("生命周期清理事件必须使用 symbol 作为 Kafka key");
            }
            drainService.confirmReleased(event);
        } catch (Exception ex) {
            throw new IllegalStateException("账户到期冻结资金核对失败", ex);
        }
    }

    public String topic() {
        return properties.getKafka().getInstrumentLifecycleDrainTopic();
    }

    public String groupId() {
        return properties.getKafka().getInstrumentLifecycleGroupId();
    }
}
