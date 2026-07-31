package com.surprising.trading.order.service;

import com.surprising.instrument.api.model.InstrumentEvent;
import com.surprising.trading.order.config.TradingOrderProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class InstrumentOrderDrainConsumer {

    private final ObjectMapper objectMapper;
    private final TradingOrderProperties properties;
    private final InstrumentOrderDrainService drainService;

    public InstrumentOrderDrainConsumer(ObjectMapper objectMapper,
                                        TradingOrderProperties properties,
                                        InstrumentOrderDrainService drainService) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.drainService = drainService;
    }

    @KafkaListener(
            topics = "#{__listener.topic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "orderInstrumentLifecycleKafkaListenerContainerFactory")
    public void onInstrumentEvent(ConsumerRecord<String, String> record) {
        try {
            if (!topic().equals(record.topic())) {
                throw new IllegalArgumentException("instrument Topic 不匹配: " + record.topic());
            }
            InstrumentEvent event = objectMapper.readValue(record.value(), InstrumentEvent.class);
            if (!event.symbol().equals(record.key())) {
                throw new IllegalArgumentException("instrument 事件必须使用 symbol 作为 Kafka key");
            }
            drainService.drain(event);
        } catch (Exception ex) {
            throw new IllegalStateException("订单到期清理失败", ex);
        }
    }

    public String topic() {
        return properties.getKafka().getInstrumentEventsTopic();
    }

    public String groupId() {
        return properties.getKafka().getInstrumentLifecycleGroupId();
    }
}
