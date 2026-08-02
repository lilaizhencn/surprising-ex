package com.surprising.trading.order.service;

import com.surprising.trading.api.model.LeverageSettingEvent;
import com.surprising.trading.api.model.LeverageSettingRequest;
import com.surprising.trading.order.config.TradingOrderProperties;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** 发布用户杠杆事实；数据库只由 Kafka 投影消费者异步写入。 */
@Service
public class LeverageSettingEventPublisher {

    private final ObjectMapper objectMapper;
    private final TradingOrderProperties properties;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OrderMarginSnapshotCache snapshotCache;

    public LeverageSettingEventPublisher(ObjectMapper objectMapper,
                                         TradingOrderProperties properties,
                                         @Qualifier("orderKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate,
                                         OrderMarginSnapshotCache snapshotCache) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.kafkaTemplate = kafkaTemplate;
        this.snapshotCache = snapshotCache;
    }

    public void publish(LeverageSettingRequest setting, long eventId, Instant eventTime) {
        LeverageSettingEvent event = new LeverageSettingEvent(
                LeverageSettingEvent.CURRENT_SCHEMA_VERSION, eventId, setting, eventTime);
        try {
            String key = event.setting().productLine().name() + ":" + event.setting().userId();
            kafkaTemplate.send(properties.getKafka().getLeverageSettingEventsTopic(), key,
                    objectMapper.writeValueAsString(event))
                    .get(properties.getEventPublish().getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            OrderMarginSnapshotCache.ApplyResult result = snapshotCache.applyLeverage(event);
            if (result == OrderMarginSnapshotCache.ApplyResult.CONFLICT) {
                throw new IllegalStateException("杠杆 JVM 快照同一事件编号出现不同事实");
            }
        } catch (Exception ex) {
            throw new IllegalStateException("杠杆事件发送 Kafka 失败", ex);
        }
    }
}
