package com.surprising.trading.order.service;

import com.surprising.trading.api.cache.FeeScheduleSnapshotCache;
import com.surprising.trading.api.model.FeeScheduleEvent;
import com.surprising.trading.api.model.FeeScheduleEventType;
import com.surprising.trading.api.model.FeeScheduleResponse;
import com.surprising.trading.api.model.FeeScheduleStatus;
import com.surprising.trading.order.repository.OrderFeeRepository;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 进程内直接更新费率 JVM 快照并异步投影到仓储，不再经由 Kafka 自发自收。 */
@Service
public class FeeScheduleEventPublisher {

    private final FeeScheduleSnapshotCache snapshotCache;
    private final OrderFeeRepository projectionRepository;

    @Autowired
    public FeeScheduleEventPublisher(FeeScheduleSnapshotCache snapshotCache,
                                     OrderFeeRepository projectionRepository) {
        this.snapshotCache = snapshotCache;
        this.projectionRepository = projectionRepository;
    }

    public FeeScheduleEventPublisher(FeeScheduleSnapshotCache snapshotCache) {
        this(snapshotCache, null);
    }

    public void publish(FeeScheduleResponse schedule) {
        if (schedule == null) {
            throw new IllegalArgumentException("fee schedule is required");
        }
        FeeScheduleEventType type = schedule.status() == FeeScheduleStatus.DISABLED
                ? FeeScheduleEventType.DISABLED : FeeScheduleEventType.UPSERTED;
        FeeScheduleEvent event = new FeeScheduleEvent(FeeScheduleEvent.CURRENT_SCHEMA_VERSION,
                schedule.productLine(), schedule.feeScheduleId(), type, schedule,
                schedule.updatedAt() == null ? Instant.now() : schedule.updatedAt());
        FeeScheduleSnapshotCache.ApplyResult result = snapshotCache.apply(event);
        if (result == FeeScheduleSnapshotCache.ApplyResult.CONFLICT) {
            throw new IllegalStateException("费率 JVM 快照同一修订号出现不同状态");
        }
        if (projectionRepository != null) {
            projectionRepository.project(event.schedule());
        }
    }
}
