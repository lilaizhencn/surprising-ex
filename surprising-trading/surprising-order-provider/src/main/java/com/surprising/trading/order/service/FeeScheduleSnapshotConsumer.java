package com.surprising.trading.order.service;

import com.surprising.trading.api.cache.FeeScheduleSnapshotCache;
import com.surprising.trading.api.model.FeeScheduleEvent;
import com.surprising.trading.order.config.TradingOrderProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** 消费费率增量通知并更新本地 JVM 快照；Kafka 不承担费率查询职责。 */
@Service
public class FeeScheduleSnapshotConsumer {

    private final ObjectMapper objectMapper;
    private final TradingOrderProperties properties;
    private final FeeScheduleSnapshotCache cache;

    public FeeScheduleSnapshotConsumer(ObjectMapper objectMapper,
                                       TradingOrderProperties properties,
                                       FeeScheduleSnapshotCache cache) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.cache = cache;
    }

    @KafkaListener(
            topics = "#{__listener.topic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "orderInstrumentLifecycleKafkaListenerContainerFactory")
    public void onFeeScheduleEvent(ConsumerRecord<String, String> record) {
        if (!topic().equals(record.topic())) {
            throw new IllegalArgumentException("费率 Topic 不匹配: " + record.topic());
        }
        try {
            FeeScheduleEvent event = objectMapper.readValue(record.value(), FeeScheduleEvent.class);
            if (event.productLine() != properties.getKafka().getProductLine()) {
                throw new IllegalArgumentException("费率事件产品线不匹配: " + event.productLine());
            }
            cache.apply(event);
        } catch (Exception ex) {
            throw new IllegalArgumentException("费率快照事件解析失败", ex);
        }
    }

    public String topic() {
        return properties.getKafka().getFeeScheduleEventsTopic();
    }

    public String groupId() {
        return properties.getKafka().getFeeScheduleSnapshotGroupId();
    }
}
