package com.surprising.account.provider.service;

import com.surprising.account.api.cache.PositionSnapshotCache;
import com.surprising.account.api.model.PositionCacheEvent;
import com.surprising.account.api.model.PositionUpdatedEvent;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.account.provider.service.PositionCacheProjectionService.Cursor;
import com.surprising.product.api.ProductLine;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 从 PostgreSQL 初始化 Redis，并在维持就绪状态的同时持续修复有限范围的数据页。 */
@Component
    public class PositionCacheCoordinator {

    private static final Logger log = LoggerFactory.getLogger(PositionCacheCoordinator.class);

    private final PositionCacheProjectionService projectionService;
    private final RedisPositionCache cache;
    private final PositionCacheRedisLease leaseLock;
    private final PositionSnapshotCache snapshotCache;
    private final AccountProperties properties;
    private Cursor reconcileCursor = Cursor.start();

    public PositionCacheCoordinator(PositionCacheProjectionService projectionService,
                                    RedisPositionCache cache,
                                    PositionCacheRedisLease leaseLock,
                                    PositionSnapshotCache snapshotCache,
                                    AccountProperties properties) {
        this.projectionService = projectionService;
        this.cache = cache;
        this.leaseLock = leaseLock;
        this.snapshotCache = snapshotCache;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        reconcile();
    }

    public void reconcile() {
        ProductLine productLine = properties.getKafka().getProductLine();
        if (cache.ready(productLine)) {
            try {
                if (!snapshotCache.ready()) {
                    rebuildLocal(productLine);
                    snapshotCache.markReady();
                }
                cache.markReady(productLine);
                reconcileOnePage(productLine);
            } catch (RuntimeException ex) {
                cache.markNotReady(productLine);
                snapshotCache.markNotReady();
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
            snapshotCache.markReady();
            cache.markReady(productLine);
            reconcileCursor = Cursor.start();
            log.info("Redis position cache ready for productLine={}", productLine);
        } catch (RuntimeException ex) {
            cache.markNotReady(productLine);
            snapshotCache.markNotReady();
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
            List<PositionCacheEvent> page = projectionService.page(productLine, cursor, batchSize);
            if (page.isEmpty()) {
                return;
            }
            for (PositionCacheEvent event : page) {
                applyLocal(event);
                cache.apply(event, true);
                cursor = projectionService.cursor(event);
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
        List<PositionCacheEvent> page = projectionService.page(productLine, reconcileCursor, batchSize());
        if (page.isEmpty()) {
            reconcileCursor = Cursor.start();
            return;
        }
        for (PositionCacheEvent event : page) {
            applyLocal(event);
            cache.apply(event, true);
            reconcileCursor = projectionService.cursor(event);
        }
        if (page.size() < batchSize()) {
            reconcileCursor = Cursor.start();
        }
    }

    private int batchSize() {
        return Math.max(1, Math.min(properties.getPositionCache().getRebuildBatchSize(), 5_000));
    }

    /** 将数据库恢复页转换为统一持仓事件，供本地 JVM 快照和 Redis 使用同一份数据。 */
    private void applyLocal(PositionCacheEvent event) {
        PositionSnapshotCache.ApplyResult result = snapshotCache.apply(new PositionUpdatedEvent(
                PositionUpdatedEvent.CURRENT_SCHEMA_VERSION,
                event.eventId(),
                0L,
                event.productLine(),
                event.revision(),
                event.userId(),
                event.symbol(),
                event.instrumentVersion(),
                event.marginMode(),
                event.positionSide(),
                event.signedQuantitySteps(),
                event.entryPriceTicks(),
                event.entryValueTicks(),
                event.realizedPnlUnits(),
                event.marginAsset(),
                event.marginUnits(),
                event.positionUpdatedAt(),
                event.marginUpdatedAt(),
                event.positionUpdatedAt(),
                "position-cache-rebuild"));
        if (result == PositionSnapshotCache.ApplyResult.PRODUCT_LINE_MISMATCH) {
            throw new IllegalStateException("position rebuild product line does not match local snapshot");
        }
    }

    private void rebuildLocal(ProductLine productLine) {
        Cursor cursor = Cursor.start();
        int batchSize = batchSize();
        while (true) {
            List<PositionCacheEvent> page = projectionService.page(productLine, cursor, batchSize);
            if (page.isEmpty()) {
                return;
            }
            for (PositionCacheEvent event : page) {
                applyLocal(event);
                cursor = projectionService.cursor(event);
            }
            if (page.size() < batchSize) {
                return;
            }
        }
    }
}
