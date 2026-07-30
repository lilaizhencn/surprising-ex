package com.surprising.account.provider.service;

import com.surprising.account.api.model.PositionCacheEvent;
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
 * 收集单个账户事务中发生变化的持仓键。
 *
 * <p>持久化持仓事件与低延迟 Redis 加速层共享同一份最终快照。若某次变更没有对外持仓事件，
 * 同步器会在提交前读取一次最终状态。Redis I/O 始终在提交后执行，不阻塞资金事务。</p>
 */
@Component
public class PositionCacheAfterCommitSynchronizer {

    private final PositionCacheProjectionService projectionService;
    private final PositionCacheAccelerationWorker accelerationWorker;

    public PositionCacheAfterCommitSynchronizer(PositionCacheProjectionService projectionService,
                                                PositionCacheAccelerationWorker accelerationWorker) {
        this.projectionService = projectionService;
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
     * 提供已写入事务持仓事件的精确快照。
     * 同一事务多次修改同一持仓时，以较新的版本号为准。
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
                    registeredState.events.add(supplied != null ? supplied : projectionService.captureFinalSnapshot(
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
