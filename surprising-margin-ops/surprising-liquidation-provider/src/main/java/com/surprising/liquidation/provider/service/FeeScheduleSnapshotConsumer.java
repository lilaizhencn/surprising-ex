package com.surprising.liquidation.provider.service;

import com.surprising.product.api.ProductTopicNames;
import com.surprising.trading.api.cache.FeeScheduleSnapshotCache;
import com.surprising.trading.api.model.FeeScheduleEvent;
import com.surprising.liquidation.provider.config.LiquidationProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** 强平消费费率变更通知；Kafka 只负责通知，不承担费率查询。 */
@Service
public class FeeScheduleSnapshotConsumer {

    private final ObjectMapper objectMapper;
    private final LiquidationProperties properties;
    private final FeeScheduleSnapshotCache cache;

    public FeeScheduleSnapshotConsumer(ObjectMapper objectMapper,
                                       LiquidationProperties properties,
                                       FeeScheduleSnapshotCache cache) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.cache = cache;
    }

    @KafkaListener(
            topics = "#{__listener.topic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "liquidationFeeScheduleSnapshotKafkaListenerContainerFactory")
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
        return properties.getKafka().isProductTopicsEnabled()
                ? ProductTopicNames.of(properties.getKafka().getProductLine()).feeScheduleEventsTopic()
                : properties.getKafka().getFeeScheduleEventsTopic();
    }

    public String groupId() {
        return properties.getKafka().getFeeScheduleSnapshotGroupId();
    }
}
