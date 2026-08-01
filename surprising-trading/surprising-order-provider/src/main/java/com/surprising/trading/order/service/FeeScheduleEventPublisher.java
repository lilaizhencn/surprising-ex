package com.surprising.trading.order.service;

import com.surprising.trading.api.model.FeeScheduleEvent;
import com.surprising.trading.api.model.FeeScheduleEventType;
import com.surprising.trading.api.model.FeeScheduleResponse;
import com.surprising.trading.api.cache.FeeScheduleSnapshotCache;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.repository.OutboxRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** 在费率表事务内写入 Outbox，可靠通知所有产品线对应的费率快照消费者。 */
@Service
public class FeeScheduleEventPublisher {

    private final ObjectMapper objectMapper;
    private final TradingOrderProperties properties;
    private final OutboxRepository outboxRepository;
    private final FeeScheduleSnapshotCache snapshotCache;

    public FeeScheduleEventPublisher(ObjectMapper objectMapper,
                                     TradingOrderProperties properties,
                                     OutboxRepository outboxRepository,
                                     FeeScheduleSnapshotCache snapshotCache) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.outboxRepository = outboxRepository;
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
            outboxRepository.enqueue("FEE_SCHEDULE", schedule.feeScheduleId(),
                    properties.getKafka().getFeeScheduleEventsTopic(),
                    schedule.productLine().name() + ":" + schedule.userId(), type.name(),
                    objectMapper.writeValueAsString(event), event.eventTime());
            applyAfterCommit(event);
        } catch (Exception ex) {
            throw new IllegalStateException("费率事件写入 Outbox 失败", ex);
        }
    }

    private void applyAfterCommit(FeeScheduleEvent event) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    snapshotCache.apply(event);
                }
            });
            return;
        }
        snapshotCache.apply(event);
    }
}
