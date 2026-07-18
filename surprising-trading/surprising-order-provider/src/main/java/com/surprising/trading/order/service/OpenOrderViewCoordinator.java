package com.surprising.trading.order.service;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.repository.OrderRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Rebuilds a generation-isolated open-order projection before making Redis reads available. */
@Component
public class OpenOrderViewCoordinator {
    private final TradingOrderProperties properties;
    private final OrderRepository repository;
    private final RedisOpenOrderView view;
    private final OrderRedisLease lease;

    public OpenOrderViewCoordinator(TradingOrderProperties properties, OrderRepository repository,
                                    RedisOpenOrderView view, OrderRedisLease lease) {
        this.properties = properties;
        this.repository = repository;
        this.view = view;
        this.lease = lease;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() { reconcile(); }

    @Scheduled(fixedDelayString = "${surprising.trading.order.redis-index.reconcile-delay-ms:10000}")
    public void reconcile() {
        ProductLine line = properties.getKafka().getProductLine();
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
                }
                afterUserId = users.get(users.size() - 1);
                if (users.size() < batch) {
                    break;
                }
            }
            view.markReady(line, epoch, properties.getRedisIndex().getReadyTtl());
        } catch (RuntimeException ex) {
            view.markNotReady(line);
        } finally {
            try { lease.release(held); } catch (RuntimeException ignored) { }
        }
    }

    private String lockKey(ProductLine line) {
        String prefix = properties.getRedisIndex().getKeyPrefix();
        return (prefix == null || prefix.isBlank() ? "surprising:order:v1" : prefix.trim())
                + ":open:rebuild-lock:" + line.name();
    }
}
