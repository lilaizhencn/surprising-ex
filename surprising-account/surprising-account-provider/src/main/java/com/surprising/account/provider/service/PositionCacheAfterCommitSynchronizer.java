package com.surprising.account.provider.service;

import com.surprising.account.api.model.PositionCacheEvent;
import com.surprising.account.provider.repository.PositionCacheProjectionRepository;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Collects changed position keys for one account transaction.
 *
 * <p>The durable position event and the low-latency Redis accelerator share the same final snapshot. When a
 * mutation has no externally visible position event, the synchronizer captures the final state once immediately
 * before commit. Redis I/O always happens after commit and never delays the financial transaction.</p>
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
        ProjectionKey key = new ProjectionKey(productLine, userId, symbol,
                MarginMode.defaultIfNull(marginMode), PositionSide.defaultIfNull(positionSide));
        state().keys.add(key);
    }

    /**
     * Supplies the exact snapshot already stored in the transactional position event.
     * A later revision wins when one transaction mutates the same position more than once.
     */
    public void schedule(PositionCacheEvent snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("position cache snapshot is required");
        }
        ProjectionKey key = ProjectionKey.from(snapshot);
        TransactionState state = state();
        state.keys.add(key);
        state.suppliedSnapshots.merge(key, snapshot,
                (current, incoming) -> incoming.revision() > current.revision() ? incoming : current);
    }

    private TransactionState state() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("position cache projection must be scheduled inside an active transaction");
        }
        TransactionState state = (TransactionState) TransactionSynchronizationManager.getResource(this);
        if (state != null) {
            return state;
        }
        TransactionState registeredState = new TransactionState();
        TransactionSynchronizationManager.bindResource(this, registeredState);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                if (readOnly) {
                    throw new IllegalStateException("position cache projection cannot run in a read-only transaction");
                }
                for (ProjectionKey changed : registeredState.keys) {
                    PositionCacheEvent supplied = registeredState.suppliedSnapshots.get(changed);
                    registeredState.events.add(supplied != null ? supplied : repository.captureFinalSnapshot(
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
        return registeredState;
    }

    private static final class TransactionState {
        private final Set<ProjectionKey> keys = new LinkedHashSet<>();
        private final Map<ProjectionKey, PositionCacheEvent> suppliedSnapshots = new LinkedHashMap<>();
        private final List<PositionCacheEvent> events = new ArrayList<>();
    }

    private record ProjectionKey(
            ProductLine productLine,
            long userId,
            String symbol,
            MarginMode marginMode,
            PositionSide positionSide) {

        private static ProjectionKey from(PositionCacheEvent event) {
            return new ProjectionKey(event.productLine(), event.userId(), event.symbol(),
                    event.marginMode(), event.positionSide());
        }
    }
}
