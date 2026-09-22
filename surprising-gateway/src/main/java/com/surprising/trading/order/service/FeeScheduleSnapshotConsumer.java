package com.surprising.trading.order.service;

import com.surprising.trading.api.cache.FeeScheduleSnapshotCache;
import com.surprising.trading.api.model.FeeScheduleEvent;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.repository.OrderFeeRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** 消费费率增量通知并更新本地 JVM 快照；Kafka 不承担费率查询职责。 */
@Service
public class FeeScheduleSnapshotConsumer {

    private final ObjectMapper objectMapper;
    private final TradingOrderProperties properties;
    private final FeeScheduleSnapshotCache cache;
    private final OrderFeeRepository projectionRepository;

    @Autowired
    public FeeScheduleSnapshotConsumer(ObjectMapper objectMapper,
                                       TradingOrderProperties properties,
                                       FeeScheduleSnapshotCache cache,
                                       OrderFeeRepository projectionRepository) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.cache = cache;
        this.projectionRepository = projectionRepository;
    }

    /** 测试或无数据库投影场景使用；生产配置必须注入异步投影仓储。 */
    public FeeScheduleSnapshotConsumer(ObjectMapper objectMapper,
                                       TradingOrderProperties properties,
                                       FeeScheduleSnapshotCache cache) {
        this(objectMapper, properties, cache, null);
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
            FeeScheduleSnapshotCache.ApplyResult result = cache.apply(event);
            if (result == FeeScheduleSnapshotCache.ApplyResult.CONFLICT) {
                throw new IllegalStateException("费率 JVM 快照同一修订号出现不同状态，暂停产品线消费");
            }
            if (projectionRepository != null) {
                projectionRepository.project(event.schedule());
            }
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
