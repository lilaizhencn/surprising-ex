package com.surprising.trading.order.service;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.repository.CancelAllAfterRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 在令牌租约保护下重建 Redis 调度索引；生产倒计时来源为本地 RocksDB。 */
@Component
public class OrderScheduleIndexCoordinator {
    private final TradingOrderProperties properties;
    private final CancelAllAfterRepository timerRepository;
    private final OrderUserStateService orderUserStateService;
    private final OrderScheduleIndex index;
    private final OrderRedisLease lease;
    private final CancelAllAfterLocalStateStore localStateStore;

    /** 历史测试构造。 */
    public OrderScheduleIndexCoordinator(TradingOrderProperties properties,
                                         CancelAllAfterRepository timerRepository,
                                         OrderUserStateService orderUserStateService,
                                         OrderScheduleIndex index,
                                         OrderRedisLease lease) {
        this(properties, timerRepository, orderUserStateService, index, lease, null);
    }

    @Autowired
    public OrderScheduleIndexCoordinator(TradingOrderProperties properties,
                                         CancelAllAfterRepository timerRepository,
                                         OrderUserStateService orderUserStateService,
                                         OrderScheduleIndex index,
                                         OrderRedisLease lease,
                                         @Nullable CancelAllAfterLocalStateStore localStateStore) {
        this.properties = properties;
        this.timerRepository = timerRepository;
        this.orderUserStateService = orderUserStateService;
        this.index = index;
        this.lease = lease;
        this.localStateStore = localStateStore;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() { reconcile(); }

    public void reconcile() {
        ProductLine line = properties.getKafka().getProductLine();
        if (index.ready(line)) {
            try { index.markReady(line); } catch (RuntimeException ex) { index.markNotReady(line); }
            return;
        }
        OrderRedisLease.Lease held = null;
        try {
            held = lease.tryAcquire(lockKey(line), properties.getRedisIndex().getLockTtl());
            if (held == null) return;
            rebuild(line, held);
            index.markReady(line);
        } catch (RuntimeException ex) {
            index.markNotReady(line);
        } finally {
            try { lease.release(held); } catch (RuntimeException ignored) { }
        }
    }

    private void rebuild(ProductLine line, OrderRedisLease.Lease held) {
        int batch = Math.max(1, Math.min(5_000, properties.getRedisIndex().getRebuildBatchSize()));
        index.clear(line);
        long afterUser = 0;
        String afterScope = "";
        int synced = 0;
        if (localStateStore != null) {
            while (true) {
                var rows = localStateStore.activeTimersForIndex(line, afterUser, afterScope, batch);
                if (rows.isEmpty()) break;
                for (var row : rows) {
                    index.synchronizeTimer(line, row);
                    if (++synced % 100 == 0 && !lease.renew(held, properties.getRedisIndex().getLockTtl()))
                        throw new IllegalStateException("Redis schedule index rebuild lease lost");
                }
                var last = rows.get(rows.size() - 1);
                afterUser = last.userId(); afterScope = last.symbolScope();
                if (rows.size() < batch) break;
            }
        } else {
            while (true) {
                var rows = timerRepository.activeTimersForIndex(line, afterUser, afterScope, batch);
                if (rows.isEmpty()) break;
                for (var row : rows) {
                    index.synchronizeTimer(line, row);
                    if (++synced % 100 == 0 && !lease.renew(held, properties.getRedisIndex().getLockTtl()))
                        throw new IllegalStateException("Redis schedule index rebuild lease lost");
                }
                var last = rows.get(rows.size() - 1);
                afterUser = last.userId(); afterScope = last.symbolScope();
                if (rows.size() < batch) break;
            }
        }
        long afterAlgoId = 0;
        while (true) {
            var rows = orderUserStateService.scheduledAlgos(line, afterAlgoId, batch);
            if (rows.isEmpty()) return;
            for (var row : rows) {
                index.synchronizeAlgo(row);
                if (++synced % 100 == 0 && !lease.renew(held, properties.getRedisIndex().getLockTtl()))
                    throw new IllegalStateException("Redis schedule index rebuild lease lost");
            }
            afterAlgoId = rows.get(rows.size() - 1).algoOrderId();
            if (rows.size() < batch) return;
        }
    }

    private String lockKey(ProductLine line) {
        String prefix = properties.getRedisIndex().getKeyPrefix();
        return (prefix == null || prefix.isBlank() ? "surprising:order:v1" : prefix.trim())
                + ":schedule:rebuild-lock:" + line.name();
    }
}
