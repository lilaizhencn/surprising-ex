package com.surprising.account.provider.service;

import com.surprising.account.provider.repository.PositionCacheProjectionRepository;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Low-latency accelerator for account-provider writes.
 *
 * <p>The database outbox remains the recovery path and covers other writers such as funding and ADL.
 * This callback only runs after commit, so a failed Redis write never rolls back or falsely reports a
 * funds mutation. The revisioned Kafka projection will retry the same state.</p>
 */
@Component
public class PositionCacheAfterCommitSynchronizer {

    private static final Logger log = LoggerFactory.getLogger(PositionCacheAfterCommitSynchronizer.class);

    private final PositionCacheProjectionRepository repository;
    private final RedisPositionCache cache;

    public PositionCacheAfterCommitSynchronizer(PositionCacheProjectionRepository repository,
                                                RedisPositionCache cache) {
        this.repository = repository;
        this.cache = cache;
    }

    public void schedule(ProductLine productLine,
                         long userId,
                         String symbol,
                         MarginMode marginMode,
                         PositionSide positionSide) {
        Runnable synchronize = () -> synchronize(productLine, userId, symbol, marginMode, positionSide);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    synchronize.run();
                }
            });
            return;
        }
        synchronize.run();
    }

    private void synchronize(ProductLine productLine,
                             long userId,
                             String symbol,
                             MarginMode marginMode,
                             PositionSide positionSide) {
        try {
            cache.apply(repository.snapshot(productLine, userId, symbol, marginMode, positionSide), false);
        } catch (RuntimeException ex) {
            cache.markNotReady(productLine);
            log.warn("after-commit Redis position projection failed line={} user={} symbol={} mode={} side={}: {}",
                    productLine, userId, symbol, marginMode, positionSide, ex.getMessage());
        }
    }
}
