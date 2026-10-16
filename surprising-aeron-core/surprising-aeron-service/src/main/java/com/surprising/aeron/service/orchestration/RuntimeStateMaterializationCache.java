package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.service.state.RuntimeStateMaterializer;
import com.surprising.aeron.service.state.TradingCoreState;

/** Owner-only immutable state cache used at query and snapshot boundaries. */
final class RuntimeStateMaterializationCache {
    private final TradingCoreRuntime owner;
    private TradingCoreState state;
    private long revision = Long.MIN_VALUE;
    private long marketRevision = Long.MIN_VALUE;
    private long sequence = Long.MIN_VALUE;

    RuntimeStateMaterializationCache(TradingCoreRuntime owner) {
        this.owner = java.util.Objects.requireNonNull(owner);
    }

    /** Full state exists only at an explicit cold read fence, outside the trading mutation path. */
    TradingCoreState current() {
        owner.runtimeState.requireSnapshotFenceReady();
        boolean acquired = owner.runtimeState.accountLanesStarted() && !owner.runtimeState.ownerLaneAccess;
        try {
            if (acquired) while (!owner.runtimeState.tryAcquireOwnerLaneAccess()) Thread.onSpinWait();
            if (owner.runtimeState.systemLedgerStarted()) {
                state = null;
                return RuntimeStateMaterializer.materialize(owner.runtimeState, owner.identities);
            }
            long currentRevision = owner.runtimeState.revision();
            long currentMarketRevision = owner.runtimeState.marketRevision();
            long currentSequence = owner.commits.publication.publishedSequence();
            if (state == null || revision != currentRevision || marketRevision != currentMarketRevision || sequence != currentSequence) {
                state = RuntimeStateMaterializer.materialize(owner.runtimeState, owner.identities);
                revision = currentRevision; marketRevision = currentMarketRevision; sequence = currentSequence;
            }
            return state;
        } finally {
            if (acquired) owner.runtimeState.releaseOwnerLaneAccess();
        }
    }

    TradingCoreState snapshot() {
        CoreSnapshotLifecycle.SnapshotFence fence = owner.snapshots.snapshotFence;
        return fence != null && fence.snapshotState != null ? fence.snapshotState : current();
    }

    /** Hash all business facts under one cold read fence, including pending Account Lane transfers. */
    long businessStateHash() {
        owner.runtimeState.requireSnapshotFenceReady();
        TradingCoreState current = current();
        return TradingCoreRuntime.canonicalBusinessStateHash(current.businessStateHash(),
                owner.runtimeState.feePoliciesSnapshot(), owner.runtimeState.pendingTransfersSnapshot());
    }
}
