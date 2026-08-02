package com.surprising.trading.order.service;

import com.surprising.trading.api.model.FeeScheduleEvent;
import com.surprising.trading.api.model.FeeScheduleEventType;
import com.surprising.trading.api.model.FeeScheduleResponse;
import com.surprising.trading.api.cache.FeeScheduleSnapshotCache;
import com.surprising.trading.order.config.TradingOrderProperties;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** 费率配置提交后直接发送 Kafka 通知，并更新本节点的 JVM 快照。 */
@Service
public class FeeScheduleEventPublisher {

    private final ObjectMapper objectMapper;
    private final TradingOrderProperties properties;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final FeeScheduleSnapshotCache snapshotCache;

    public FeeScheduleEventPublisher(ObjectMapper objectMapper,
                                     TradingOrderProperties properties,
                                     @Qualifier("orderKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate,
                                     FeeScheduleSnapshotCache snapshotCache) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.kafkaTemplate = kafkaTemplate;
        this.snapshotCache = snapshotCache;
    }

    public void publish(FeeScheduleResponse schedule) {
        if (schedule == null) {
            throw new IllegalArgumentException("fee schedule is required");
        }
        FeeScheduleEventType type = schedule.status() == com.surprising.trading.api.model.FeeScheduleStatus.DISABLED
                ? FeeScheduleEventType.DISABLED : FeeScheduleEventType.UPSERTED;
        FeeScheduleEvent event = new FeeScheduleEvent(FeeScheduleEvent.CURRENT_SCHEMA_VERSION,
                schedule.productLine(), schedule.feeScheduleId(), type, schedule,
                schedule.updatedAt() == null ? Instant.now() : schedule.updatedAt());
        try {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        publishAfterCommit(event);
                    }
                });
            } else {
                publishAfterCommit(event);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("费率事件发送 Kafka 失败", ex);
        }
    }

    private void publishAfterCommit(FeeScheduleEvent event) {
        try {
            String topic = properties.getKafka().getFeeScheduleEventsTopic();
            String key = event.productLine().name() + ":" + event.schedule().userId();
            kafkaTemplate.send(topic, key, objectMapper.writeValueAsString(event))
                    .get(properties.getEventPublish().getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            FeeScheduleSnapshotCache.ApplyResult result = snapshotCache.apply(event);
            if (result == FeeScheduleSnapshotCache.ApplyResult.CONFLICT) {
                throw new IllegalStateException("费率 JVM 快照同一修订号出现不同状态");
            }
        } catch (Exception ex) {
            throw new IllegalStateException("费率事件发送 Kafka 失败", ex);
        }
    }
}
