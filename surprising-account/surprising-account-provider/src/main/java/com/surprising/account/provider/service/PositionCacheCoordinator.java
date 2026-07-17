package com.surprising.account.provider.service;

import com.surprising.account.api.model.PositionCacheEvent;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.account.provider.repository.PositionCacheProjectionRepository;
import com.surprising.account.provider.repository.PositionCacheProjectionRepository.Cursor;
import com.surprising.product.api.ProductLine;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bootstraps Redis from PostgreSQL and continuously repairs a bounded page while keeping readiness alive. */
@Component
public class PositionCacheCoordinator {

    private static final Logger log = LoggerFactory.getLogger(PositionCacheCoordinator.class);

    private final PositionCacheProjectionRepository repository;
    private final RedisPositionCache cache;
    private final PositionCacheRedisLease leaseLock;
    private final AccountProperties properties;
    private Cursor reconcileCursor = Cursor.start();

    public PositionCacheCoordinator(PositionCacheProjectionRepository repository,
                                    RedisPositionCache cache,
                                    PositionCacheRedisLease leaseLock,
                                    AccountProperties properties) {
        this.repository = repository;
        this.cache = cache;
        this.leaseLock = leaseLock;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        reconcile();
    }

    @Scheduled(fixedDelayString = "${surprising.account.position-cache.reconcile-delay-ms:10000}")
    public void reconcile() {
        ProductLine productLine = properties.getKafka().getProductLine();
        if (cache.ready(productLine)) {
            try {
                cache.markReady(productLine);
                reconcileOnePage(productLine);
            } catch (RuntimeException ex) {
                cache.markNotReady(productLine);
                log.warn("Redis position cache reconciliation failed for productLine={}: {}",
                        productLine, ex.getMessage());
            }
            return;
        }

        PositionCacheRedisLease.Lease lease = null;
        try {
            lease = leaseLock.tryAcquire(
                    cache.rebuildLockKey(productLine), properties.getPositionCache().getLockTtl());
            if (lease == null) {
                return;
            }
            rebuild(productLine, lease);
            cache.markReady(productLine);
            reconcileCursor = Cursor.start();
            log.info("Redis position cache ready for productLine={}", productLine);
        } catch (RuntimeException ex) {
            cache.markNotReady(productLine);
            log.warn("Redis position cache rebuild failed for productLine={}: {}", productLine, ex.getMessage());
        } finally {
            try {
                leaseLock.release(lease);
            } catch (RuntimeException ex) {
                log.debug("Redis position cache rebuild lease release failed: {}", ex.getMessage());
            }
        }
    }

    private void rebuild(ProductLine productLine, PositionCacheRedisLease.Lease lease) {
        Cursor cursor = Cursor.start();
        int synchronizedRows = 0;
        int batchSize = batchSize();
        while (true) {
            List<PositionCacheEvent> page = repository.page(productLine, cursor, batchSize);
            if (page.isEmpty()) {
                return;
            }
            for (PositionCacheEvent event : page) {
                cache.apply(event, true);
                cursor = repository.cursor(event);
                synchronizedRows++;
                if (synchronizedRows % 100 == 0
                        && !leaseLock.renew(lease, properties.getPositionCache().getLockTtl())) {
                    throw new IllegalStateException("Redis position cache rebuild lease was lost");
                }
            }
            if (page.size() < batchSize) {
                return;
            }
        }
    }

    private void reconcileOnePage(ProductLine productLine) {
        List<PositionCacheEvent> page = repository.page(productLine, reconcileCursor, batchSize());
        if (page.isEmpty()) {
            reconcileCursor = Cursor.start();
            return;
        }
        for (PositionCacheEvent event : page) {
            cache.apply(event, true);
            reconcileCursor = repository.cursor(event);
        }
        if (page.size() < batchSize()) {
            reconcileCursor = Cursor.start();
        }
    }

    private int batchSize() {
        return Math.max(1, Math.min(properties.getPositionCache().getRebuildBatchSize(), 5_000));
    }
}
