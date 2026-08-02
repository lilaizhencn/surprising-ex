package com.surprising.account.provider.service;

import com.surprising.account.api.cache.PositionSnapshotCache;
import com.surprising.account.api.model.PositionUpdatedEvent;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.product.api.ProductLine;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 将本地 JVM 持仓快照重放到 Redis 读模型。
 *
 * <p>Redis 只是可丢失的加速投影，启动恢复唯一依赖 Kafka 持仓事件消费形成的
 * {@link PositionSnapshotCache}。这里不读取数据库、不做 JOIN，也不负责资金状态裁决。</p>
 */
@Component
public class PositionCacheCoordinator {

    private static final Logger log = LoggerFactory.getLogger(PositionCacheCoordinator.class);

    private final RedisPositionCache cache;
    private final PositionSnapshotCache snapshotCache;
    private final AccountProperties properties;
    private final AtomicBoolean reconciling = new AtomicBoolean();

    public PositionCacheCoordinator(RedisPositionCache cache,
                                    PositionSnapshotCache snapshotCache,
                                    AccountProperties properties) {
        this.cache = cache;
        this.snapshotCache = snapshotCache;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        reconcile();
    }

    /** 只使用本地快照修复 Redis；快照未追平时保持失败关闭。 */
    public void reconcile() {
        ProductLine productLine = properties.getKafka().getProductLine();
        if (!reconciling.compareAndSet(false, true)) {
            return;
        }
        try {
            if (!snapshotCache.ready()) {
                cache.markNotReady(productLine);
                return;
            }
            for (PositionUpdatedEvent event : snapshotCache.snapshot().values()) {
                if (event.productLine() != productLine) {
                    throw new IllegalStateException("本地持仓快照产品线不匹配: " + event.productLine());
                }
                cache.apply(event.cacheEvent(), true);
            }
            cache.markReady(productLine);
        } catch (RuntimeException ex) {
            cache.markNotReady(productLine);
            log.warn("本地持仓快照重放到 Redis 失败 productLine={}: {}", productLine, ex.getMessage());
        } finally {
            reconciling.set(false);
        }
    }
}
