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

    TradingCoreState current() {
        owner.runtimeState.requireSnapshotFenceReady();
        long currentRevision = owner.runtimeState.revision();
        long currentMarketRevision = owner.runtimeState.marketRevision();
        long currentSequence = owner.commits.publication.publishedSequence();
        if (state == null || revision != currentRevision || marketRevision != currentMarketRevision
                || sequence != currentSequence) {
            state = RuntimeStateMaterializer.materialize(owner.runtimeState, owner.identities);
            revision = currentRevision;
            marketRevision = currentMarketRevision;
            sequence = currentSequence;
        }
        return state;
    }

    TradingCoreState snapshot() {
        CoreSnapshotLifecycle.SnapshotFence fence = owner.snapshots.snapshotFence;
        return fence != null && fence.snapshotState != null ? fence.snapshotState : current();
    }
}
