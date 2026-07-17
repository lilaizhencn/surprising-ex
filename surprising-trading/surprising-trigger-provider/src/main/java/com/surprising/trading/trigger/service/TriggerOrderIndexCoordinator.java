package com.surprising.trading.trigger.service;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.trigger.config.TriggerProperties;
import com.surprising.trading.trigger.model.TriggerOrderRecord;
import com.surprising.trading.trigger.repository.TriggerOrderRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Builds and keeps the Redis TP/SL index ready without making the lock part of business correctness. */
@Component
public class TriggerOrderIndexCoordinator {

    private static final Logger log = LoggerFactory.getLogger(TriggerOrderIndexCoordinator.class);

    private final TriggerOrderRepository repository;
    private final TriggerOrderIndex index;
    private final RedisLeaseLock leaseLock;
    private final TriggerProperties properties;

    public TriggerOrderIndexCoordinator(TriggerOrderRepository repository,
                                        TriggerOrderIndex index,
                                        RedisLeaseLock leaseLock,
                                        TriggerProperties properties) {
        this.repository = repository;
        this.index = index;
        this.leaseLock = leaseLock;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        reconcile();
    }

    @Scheduled(fixedDelayString = "${surprising.trading.trigger.redis-index.reconcile-delay-ms:10000}")
    public void reconcile() {
        ProductLine productLine = properties.getKafka().getProductLine();
        if (index.ready(productLine)) {
            try {
                index.markReady(productLine);
            } catch (RuntimeException ex) {
                index.markNotReady(productLine);
            }
            return;
        }

        RedisLeaseLock.Lease lease = null;
        try {
            lease = leaseLock.tryAcquire(lockKey(productLine), properties.getRedisIndex().getLockTtl());
            if (lease == null) {
                return;
            }
            rebuild(productLine, lease);
            index.markReady(productLine);
            log.info("Redis trigger index ready for productLine={}", productLine);
        } catch (RuntimeException ex) {
            index.markNotReady(productLine);
            log.warn("Redis trigger index rebuild failed for productLine={}: {}", productLine, ex.getMessage());
        } finally {
            try {
                leaseLock.release(lease);
            } catch (RuntimeException ex) {
                log.debug("Redis trigger index rebuild lease release failed: {}", ex.getMessage());
            }
        }
    }

    private void rebuild(ProductLine productLine, RedisLeaseLock.Lease lease) {
        long afterTriggerOrderId = 0L;
        int synchronizedOrders = 0;
        int batchSize = Math.max(1, Math.min(properties.getRedisIndex().getRebuildBatchSize(), 5_000));
        while (true) {
            List<TriggerOrderRecord> orders = repository.staticOrdersForIndex(
                    productLine, afterTriggerOrderId, batchSize);
            if (orders.isEmpty()) {
                return;
            }
            for (TriggerOrderRecord order : orders) {
                index.synchronize(order);
                synchronizedOrders++;
                if (synchronizedOrders % 100 == 0
                        && !leaseLock.renew(lease, properties.getRedisIndex().getLockTtl())) {
                    throw new IllegalStateException("Redis trigger index rebuild lease was lost");
                }
            }
            afterTriggerOrderId = orders.get(orders.size() - 1).triggerOrderId();
            if (orders.size() < batchSize) {
                return;
            }
        }
    }

    private String lockKey(ProductLine productLine) {
        String prefix = properties.getRedisIndex().getKeyPrefix();
        if (prefix == null || prefix.isBlank()) {
            prefix = "surprising:trigger:v1";
        }
        return prefix.trim() + ":rebuild-lock:" + productLine.name();
    }
}
