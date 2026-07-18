package com.surprising.account.provider.service;

import com.surprising.account.api.model.PositionCacheEvent;
import com.surprising.account.provider.repository.PositionCacheProjectionRepository;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Collects changed position keys for one account transaction.
 *
 * <p>Immediately before commit, each distinct key is captured once as a final-state snapshot. After commit,
 * that exact snapshot is offered to a bounded asynchronous Redis worker. The cache path never writes an outbox
 * row and never blocks the transaction on Redis I/O.</p>
 */
@Component
public class PositionCacheAfterCommitSynchronizer {

    private final PositionCacheProjectionRepository repository;
    private final PositionCacheAccelerationWorker accelerationWorker;

    public PositionCacheAfterCommitSynchronizer(PositionCacheProjectionRepository repository,
                                                PositionCacheAccelerationWorker accelerationWorker) {
        this.repository = repository;
        this.accelerationWorker = accelerationWorker;
    }

    public void schedule(ProductLine productLine,
                         long userId,
                         String symbol,
                         MarginMode marginMode,
                         PositionSide positionSide) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("position cache projection must be scheduled inside an active transaction");
        }
        ProjectionKey key = new ProjectionKey(productLine, userId, symbol,
                MarginMode.defaultIfNull(marginMode), PositionSide.defaultIfNull(positionSide));
        TransactionState state = (TransactionState) TransactionSynchronizationManager.getResource(this);
        if (state == null) {
            state = new TransactionState();
            TransactionSynchronizationManager.bindResource(this, state);
            TransactionState registeredState = state;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    if (readOnly) {
                        throw new IllegalStateException("position cache projection cannot run in a read-only transaction");
                    }
                    for (ProjectionKey changed : registeredState.keys) {
                        registeredState.events.add(repository.captureFinalSnapshot(
                                changed.productLine(), changed.userId(), changed.symbol(),
                                changed.marginMode(), changed.positionSide()));
                    }
                }

                @Override
                public void afterCommit() {
                    accelerationWorker.submitAll(List.copyOf(registeredState.events));
                }

                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(
                            PositionCacheAfterCommitSynchronizer.this);
                }
            });
        }
        state.keys.add(key);
    }

    private static final class TransactionState {
        private final Set<ProjectionKey> keys = new LinkedHashSet<>();
        private final List<PositionCacheEvent> events = new ArrayList<>();
    }

    private record ProjectionKey(
            ProductLine productLine,
            long userId,
            String symbol,
            MarginMode marginMode,
            PositionSide positionSide) {
    }
}
