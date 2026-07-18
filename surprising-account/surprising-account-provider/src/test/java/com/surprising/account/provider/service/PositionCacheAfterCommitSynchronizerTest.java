package com.surprising.account.provider.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.surprising.account.api.model.PositionCacheEvent;
import com.surprising.account.provider.repository.PositionCacheProjectionRepository;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class PositionCacheAfterCommitSynchronizerTest {

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void capturesOneFinalSnapshotBeforeCommitAndUpdatesRedisOnlyAfterCommit() {
        PositionCacheProjectionRepository repository = mock(PositionCacheProjectionRepository.class);
        PositionCacheAccelerationWorker worker = mock(PositionCacheAccelerationWorker.class);
        PositionCacheEvent snapshot = snapshot();
        when(repository.captureFinalSnapshot(ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", MarginMode.CROSS,
                PositionSide.NET)).thenReturn(snapshot);
        PositionCacheAfterCommitSynchronizer synchronizer =
                new PositionCacheAfterCommitSynchronizer(repository, worker);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();

        synchronizer.schedule(ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", MarginMode.CROSS,
                PositionSide.NET);
        synchronizer.schedule(ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", MarginMode.CROSS,
                PositionSide.NET);

        verifyNoInteractions(repository, worker);
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        synchronizations.forEach(synchronization -> synchronization.beforeCommit(false));
        verify(repository, times(1)).captureFinalSnapshot(
                ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", MarginMode.CROSS,
                PositionSide.NET);
        verifyNoInteractions(worker);

        synchronizations.forEach(TransactionSynchronization::afterCommit);
        verify(worker).submitAll(List.of(snapshot));
        synchronizations.forEach(synchronization ->
                synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
    }

    @Test
    void rejectsSchedulingOutsideATransaction() {
        PositionCacheAfterCommitSynchronizer synchronizer = new PositionCacheAfterCommitSynchronizer(
                mock(PositionCacheProjectionRepository.class), mock(PositionCacheAccelerationWorker.class));

        assertThatThrownBy(() -> synchronizer.schedule(
                ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", MarginMode.CROSS, PositionSide.NET))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active transaction");
    }

    @Test
    void rollbackDoesNotCaptureOrAccelerate() {
        PositionCacheProjectionRepository repository = mock(PositionCacheProjectionRepository.class);
        PositionCacheAccelerationWorker worker = mock(PositionCacheAccelerationWorker.class);
        PositionCacheAfterCommitSynchronizer synchronizer =
                new PositionCacheAfterCommitSynchronizer(repository, worker);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();

        synchronizer.schedule(ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", MarginMode.CROSS,
                PositionSide.NET);
        TransactionSynchronizationManager.getSynchronizations().forEach(synchronization ->
                synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verifyNoInteractions(repository, worker);
    }

    private PositionCacheEvent snapshot() {
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        return new PositionCacheEvent(9L, ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", 7L,
                MarginMode.CROSS, PositionSide.NET, 3L, 60_000L, 180_000L, 0L,
                "USDT", 20_000L, now, now, 9L);
    }
}
