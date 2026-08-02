package com.surprising.trading.order.service;

import com.surprising.trading.api.model.LeverageSettingEvent;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.repository.LeverageSettingRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** 消费杠杆增量，先更新 JVM 快照，再异步投影数据库。 */
@Service
public class LeverageSettingSnapshotConsumer {

    private final ObjectMapper objectMapper;
    private final TradingOrderProperties properties;
    private final OrderMarginSnapshotCache cache;
    private final LeverageSettingRepository projectionRepository;

    public LeverageSettingSnapshotConsumer(ObjectMapper objectMapper,
                                           TradingOrderProperties properties,
                                           OrderMarginSnapshotCache cache,
                                           LeverageSettingRepository projectionRepository) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.cache = cache;
        this.projectionRepository = projectionRepository;
    }

    @KafkaListener(
            topics = "#{__listener.topic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "orderInstrumentLifecycleKafkaListenerContainerFactory")
    public void onLeverageSetting(ConsumerRecord<String, String> record) {
        if (!topic().equals(record.topic())) {
            throw new IllegalArgumentException("杠杆 Topic 不匹配: " + record.topic());
        }
        try {
            LeverageSettingEvent event = objectMapper.readValue(record.value(), LeverageSettingEvent.class);
            if (event.setting().productLine() != properties.getKafka().getProductLine()) {
                throw new IllegalArgumentException("杠杆事件产品线不匹配: " + event.setting().productLine());
            }
            String expectedKey = event.setting().productLine().name() + ":" + event.setting().userId();
            if (!expectedKey.equals(record.key())) {
                throw new IllegalArgumentException("杠杆事件 Kafka key 不匹配: " + expectedKey);
            }
            OrderMarginSnapshotCache.ApplyResult result = cache.applyLeverage(event);
            if (result == OrderMarginSnapshotCache.ApplyResult.CONFLICT) {
                throw new IllegalStateException("杠杆 JVM 快照同一事件编号出现不同事实");
            }
            projectionRepository.project(event.setting(), event.eventTime());
        } catch (Exception ex) {
            throw new IllegalArgumentException("杠杆快照事件解析失败", ex);
        }
    }

    public String topic() {
        return properties.getKafka().getLeverageSettingEventsTopic();
    }

    public String groupId() {
        return properties.getKafka().getLeverageSettingSnapshotGroupId();
    }
}
