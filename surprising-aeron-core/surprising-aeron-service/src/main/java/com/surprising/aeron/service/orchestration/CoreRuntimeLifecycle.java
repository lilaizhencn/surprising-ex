package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.service.exception.FatalMatchingDivergenceException;

/**
 * 交易运行时的 owner 绑定、启动、健康检查和资源释放边界。
 *
 * <p>生命周期状态仍只保存在 {@link TradingCoreRuntime}，这里不复制状态；本类负责把
 * Matcher、Account Lane、投影日志、快照和查询会话按业务关闭顺序推进。</p>
 */
final class CoreRuntimeLifecycle {

    private final TradingCoreRuntime owner;

    CoreRuntimeLifecycle(TradingCoreRuntime owner) {
        this.owner = java.util.Objects.requireNonNull(owner);
    }

    void activate() {
        if (owner.activated) return;
        if (owner.closed) throw new IllegalStateException("cannot activate closed core state");
        try {
            owner.matcherPipeline.start(owner.matchingAdapter::activateShard);
            bindOwner();
            owner.runtimeState.startAccountLanes();
            owner.matchingAdapter.activate();
            owner.runtimeProjectionJournal.activate();
            owner.exportState.activate();
            owner.activated = true;
        } catch (RuntimeException failure) {
            try {
                close();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    void bindOwner() {
        Thread current = Thread.currentThread();
        if (owner.owner != null && owner.owner != current) {
            throw new IllegalStateException("trading runtime is bound to another thread");
        }
        owner.owner = current;
        owner.runtimeState.bindOwner();
        owner.identities.assertOwner();
    }

    void assertOwner() {
        if (owner.owner == null) bindOwner();
        else if (owner.owner != Thread.currentThread()) {
            throw new IllegalStateException("trading runtime is bound to another thread");
        }
    }

    void assertClusterCallbackComplete() {
        if (!owner.activated) activate();
        assertOwner();
        assertHealthy();
        if (owner.directCommand.active() || !owner.pendingMatching.isEmpty()
                || !owner.bookQueries.queryIds.isEmpty()) {
            throw new IllegalStateException("unfinished business work outside cluster log callback");
        }
    }

    void assertHealthy() {
        if (owner.fatalFailure != null) throw owner.fatalFailure;
        if (owner.commitPublicationFailure != null) throw owner.commitPublicationFailure;
        if (owner.runtimeState != null) owner.runtimeState.assertAccountLanesHealthy();
        owner.runtimeProjectionJournal.assertHealthy();
        owner.exportState.assertHealthy();
        RuntimeException auditFailure = owner.snapshots.snapshotAuditFailure.get();
        if (auditFailure != null) throw auditFailure;
    }

    FatalMatchingDivergenceException failMatching(CommandSlot pending, String detail, Throwable cause) {
        try {
            owner.matchingAdapter.poisonFromOwner("deterministic matcher settlement failed sequence="
                    + pending.sequence() + " operation=" + pending.operation());
        } catch (RuntimeException poisonFailure) {
            if (cause != null) cause.addSuppressed(poisonFailure);
            else cause = poisonFailure;
        }
        owner.fatalFailure = cause == null
                ? new FatalMatchingDivergenceException(pending.operation().name(), pending.sequence(), 0, detail)
                : new FatalMatchingDivergenceException(pending.operation().name(), pending.sequence(), 0, detail, cause);
        return owner.fatalFailure;
    }

    void close() {
        if (owner.closed) return;
        if (owner.activated) assertOwner();
        owner.closed = true;
        owner.snapshots.releaseSnapshotFence();
        owner.snapshots.inFlightMatcherSnapshot.set(null);
        owner.matcherPipeline.closeShards(owner.matchingAdapter::closeShard);
        owner.bookQueries.completedBookQueries.clear();
        owner.bookQueries.failedQueries.clear();
        owner.bookQueries.queryIds.clear();
        owner.runtimeState.endOrderBatchMutationScope();
        owner.clearFactContext();
        owner.directCommand.clear();
        owner.batches.clearPendingBatches();
        owner.pendingMatching.clear();
        owner.crossShardCancellations.clear();
        owner.admissions.pendingLifecycleScopes.clear();
        owner.exportState.close();
        owner.runtimeProjectionJournal.close();
        owner.runtimeState.close();
    }
}
