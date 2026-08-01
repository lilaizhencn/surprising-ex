package com.surprising.trading.order.service;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.repository.OrderRepository;
import com.surprising.trading.order.repository.OrderPositionRepository;
import com.surprising.trading.order.repository.LeverageSettingRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 先重建按代隔离的活跃订单投影，再开放 Redis 读取。 */
@Component
public class OpenOrderViewCoordinator {
    private final TradingOrderProperties properties;
    private final OrderRepository repository;
    private final RedisOpenOrderView view;
    private final OrderRedisLease lease;
    private final OrderMarginSnapshotCache marginSnapshotCache;
    private final OrderPositionRepository positionRepository;
    private final LeverageSettingRepository leverageRepository;

    public OpenOrderViewCoordinator(TradingOrderProperties properties, OrderRepository repository,
                                    RedisOpenOrderView view, OrderRedisLease lease) {
        this(properties, repository, view, lease, null, null, null);
    }

    public OpenOrderViewCoordinator(TradingOrderProperties properties, OrderRepository repository,
                                    RedisOpenOrderView view, OrderRedisLease lease,
                                    OrderMarginSnapshotCache marginSnapshotCache) {
        this(properties, repository, view, lease, marginSnapshotCache, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public OpenOrderViewCoordinator(TradingOrderProperties properties, OrderRepository repository,
                                    RedisOpenOrderView view, OrderRedisLease lease,
                                    OrderMarginSnapshotCache marginSnapshotCache,
                                    OrderPositionRepository positionRepository,
                                    LeverageSettingRepository leverageRepository) {
        this.properties = properties;
        this.repository = repository;
        this.view = view;
        this.lease = lease;
        this.marginSnapshotCache = marginSnapshotCache;
        this.positionRepository = positionRepository;
        this.leverageRepository = leverageRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() { reconcile(); }

    public void reconcile() {
        ProductLine line = properties.getKafka().getProductLine();
        boolean marginSnapshotReady = warmMarginSnapshot(line);
        if (!view.rebuildRequired(line, properties.getRedisIndex().getRebuildMaxAge())) {
            try {
                view.refreshReady(line, properties.getRedisIndex().getReadyTtl());
            } catch (RuntimeException ex) {
                view.markNotReady(line);
            }
            return;
        }
        OrderRedisLease.Lease held = null;
        try {
            held = lease.tryAcquire(lockKey(line), properties.getRedisIndex().getLockTtl());
            if (held == null) return;
            if (marginSnapshotCache != null) {
                marginSnapshotCache.markOrderProjectionNotReady(line);
            }
            long epoch = view.startRebuild(line);
            long afterUserId = 0;
            int synchronizedRows = 0;
            int batch = Math.max(1, Math.min(5_000, properties.getRedisIndex().getRebuildBatchSize()));
            while (true) {
                var users = repository.activeOpenOrderUsers(line, afterUserId, batch);
                if (users.isEmpty()) {
                    break;
                }
                for (long userId : users) {
                    view.initializeUser(line, userId, epoch);
                    long afterOrderId = 0;
                    while (true) {
                        var rows = repository.activeOrdersForOpenOrderView(line, userId, afterOrderId, batch);
                        if (rows.isEmpty()) {
                            break;
                        }
                        for (var row : rows) {
                            view.synchronize(row);
                            if (marginSnapshotCache != null) {
                                marginSnapshotCache.applyOrder(row);
                            }
                            if (++synchronizedRows % 100 == 0
                                    && !lease.renew(held, properties.getRedisIndex().getLockTtl())) {
                                throw new IllegalStateException("Redis open-order rebuild lease lost");
                            }
                        }
                        afterOrderId = rows.get(rows.size() - 1).orderId();
                        if (rows.size() < batch) {
                            break;
                        }
                    }
                    if (marginSnapshotCache != null) {
                        long afterMarginOrderId = 0L;
                        while (true) {
                            var marginRows = repository.activeOrdersForMarginSnapshot(
                                    line, userId, afterMarginOrderId, batch);
                            if (marginRows.isEmpty()) {
                                break;
                            }
                            marginRows.forEach(marginSnapshotCache::applyOrder);
                            afterMarginOrderId = marginRows.get(marginRows.size() - 1).orderId();
                            if (marginRows.size() < batch) {
                                break;
                            }
                        }
                    }
                }
                afterUserId = users.get(users.size() - 1);
                if (users.size() < batch) {
                    break;
                }
            }
            view.markReady(line, epoch, properties.getRedisIndex().getReadyTtl());
            if (marginSnapshotCache != null) {
                marginSnapshotCache.markOrderProjectionReady(line);
                if (!marginSnapshotReady) {
                    marginSnapshotCache.markNotReady(line);
                }
            }
        } catch (RuntimeException ex) {
            view.markNotReady(line);
            if (marginSnapshotCache != null) {
                marginSnapshotCache.markOrderProjectionNotReady(line);
            }
        } finally {
            try { lease.release(held); } catch (RuntimeException ignored) { }
        }
    }

    /** Redis 活跃订单代已经存在时，仍需单独完成本 JVM 保证金快照的启动恢复。 */
    private boolean warmMarginSnapshot(ProductLine line) {
        if (marginSnapshotCache == null || marginSnapshotCache.ready(line)) {
            return marginSnapshotCache != null && marginSnapshotCache.ready(line);
        }
        int batch = Math.max(1, Math.min(properties.getRedisIndex().getRebuildBatchSize(), 5_000));
        try {
            marginSnapshotCache.markNotReady(line);
            if (positionRepository == null || leverageRepository == null) {
                throw new IllegalStateException("保证金快照恢复仓储未配置");
            }
            for (var position : positionRepository.snapshot(line)) {
                if (position.instrumentVersion() != null && position.instrumentVersion() > 0L) {
                    marginSnapshotCache.putPosition(line, position.userId(), position.symbol(), position.marginMode(),
                            position.positionSide(), position.instrumentVersion(), position.signedQuantitySteps());
                }
                marginSnapshotCache.putDefaultLeverageIfAbsent(line, position.userId(), position.symbol(),
                        position.marginMode());
            }
            for (var leverage : leverageRepository.snapshot(line)) {
                marginSnapshotCache.putLeverage(line, leverage.userId(), leverage.symbol(), leverage.marginMode(),
                        leverage.leveragePpm());
            }
            long afterUserId = 0L;
            while (true) {
                var users = repository.activeMarginSnapshotUsers(line, afterUserId, batch);
                if (users.isEmpty()) {
                    break;
                }
                for (long userId : users) {
                    long afterOrderId = 0L;
                    while (true) {
                        var rows = repository.activeOrdersForMarginSnapshot(line, userId, afterOrderId, batch);
                        if (rows.isEmpty()) {
                            break;
                        }
                        rows.forEach(order -> {
                            marginSnapshotCache.applyOrder(order);
                            marginSnapshotCache.putDefaultLeverageIfAbsent(line, order.userId(), order.symbol(),
                                    order.marginMode());
                            marginSnapshotCache.putPositionIfAbsent(line, order.userId(), order.symbol(),
                                    order.marginMode(), order.positionSide(), order.instrumentVersion());
                        });
                        afterOrderId = rows.get(rows.size() - 1).orderId();
                        if (rows.size() < batch) {
                            break;
                        }
                    }
                }
                afterUserId = users.get(users.size() - 1);
                if (users.size() < batch) {
                    break;
                }
            }
            marginSnapshotCache.markOrderProjectionReady(line);
            return true;
        } catch (RuntimeException ex) {
            marginSnapshotCache.markNotReady(line);
            return false;
        }
    }

    private String lockKey(ProductLine line) {
        String prefix = properties.getRedisIndex().getKeyPrefix();
        return (prefix == null || prefix.isBlank() ? "surprising:order:v1" : prefix.trim())
                + ":open:rebuild-lock:" + line.name();
    }
}
