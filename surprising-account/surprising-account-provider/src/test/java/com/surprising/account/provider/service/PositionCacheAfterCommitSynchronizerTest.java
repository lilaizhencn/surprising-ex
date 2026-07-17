package com.surprising.account.provider.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.surprising.account.api.model.PositionCacheEvent;
import com.surprising.account.provider.repository.PositionCacheProjectionRepository;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class PositionCacheAfterCommitSynchronizerTest {

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void readsAndAppliesOnlyAfterDatabaseCommit() {
        PositionCacheProjectionRepository repository = mock(PositionCacheProjectionRepository.class);
        RedisPositionCache cache = mock(RedisPositionCache.class);
        PositionCacheEvent snapshot = snapshot();
        when(repository.snapshot(ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", MarginMode.CROSS,
                PositionSide.NET)).thenReturn(snapshot);
        PositionCacheAfterCommitSynchronizer synchronizer = new PositionCacheAfterCommitSynchronizer(repository, cache);
        TransactionSynchronizationManager.initSynchronization();

        synchronizer.schedule(ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", MarginMode.CROSS,
                PositionSide.NET);

        verifyNoInteractions(repository, cache);
        TransactionSynchronizationManager.getSynchronizations().forEach(synchronization -> synchronization.afterCommit());
        verify(repository).snapshot(ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", MarginMode.CROSS,
                PositionSide.NET);
        verify(cache).apply(snapshot, false);
    }

    private PositionCacheEvent snapshot() {
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        return new PositionCacheEvent(9L, ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", 7L,
                MarginMode.CROSS, PositionSide.NET, 3L, 60_000L, 180_000L, 0L,
                "USDT", 20_000L, now, now, 9L);
    }
}
