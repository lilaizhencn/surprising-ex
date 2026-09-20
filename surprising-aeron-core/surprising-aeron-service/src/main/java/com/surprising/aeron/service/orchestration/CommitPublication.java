package com.surprising.aeron.service.orchestration;


import java.util.Objects;

/**
 * Owns the Owner-thread commit publication batch.
 *
 * <p>The matching coordinator decides when an ordered command is complete, while this class
 * owns whether a state change is deferred, dirty or provisional and performs the one publication
 * that advances the runtime projection. It does not calculate funds, mutate orders or own Lane
 * state.</p>
 */
final class CommitPublication {
    private final TradingCoreRuntime owner;
    private long runtimePatchRevision;
    private boolean deferred;
    private boolean dirty;
    private boolean provisionalOnly;
    private boolean active;

    CommitPublication(TradingCoreRuntime owner) {
        this.owner = Objects.requireNonNull(owner);
    }

    boolean deferred() { return deferred; }
    boolean dirty() { return dirty; }
    boolean provisionalOnly() { return provisionalOnly; }

    void initialize(long runtimePatchRevision) {
        if (deferred || dirty || provisionalOnly || active) {
            throw new IllegalStateException("cannot initialize an active commit publication");
        }
        this.runtimePatchRevision = runtimePatchRevision;
    }

    void request() {
        if (deferred) {
            dirty = true;
            provisionalOnly = false;
            return;
        }
        publish();
    }

    void begin() {
        if (deferred) throw new IllegalStateException("snapshot projection batch is already active");
        deferred = true;
        dirty = false;
        provisionalOnly = false;
    }

    void complete() {
        boolean changed = dirty;
        boolean provisional = provisionalOnly;
        clearBatchState();
        if (changed && provisional) owner.runtimeState.clearChangedKeys();
        else if (changed) publish();
    }

    void abort() { clearBatchState(); }

    void deferProvisionalProjection() {
        if (!deferred) throw new IllegalStateException("provisional projection requires a command batch");
        if (!dirty) provisionalOnly = true;
        dirty = true;
    }

    void suspend() { clearBatchState(); }

    void restore(boolean dirty, boolean provisionalOnly) {
        if (deferred || active) throw new IllegalStateException("another commit publication is active");
        deferred = true;
        this.dirty = dirty;
        this.provisionalOnly = provisionalOnly;
    }

    void publish() {
        if (active) throw new IllegalStateException("owner commit publisher is already active");
        active = true;
        try {
            long sequence = Math.incrementExact(owner.runtimeProjectionJournal.publishedSequence());
            try {
                if (!owner.factContextActive) {
                    throw new IllegalStateException("runtime commit requires an active command scope");
                }
                owner.runtimeState.appendFundsDelta(owner.commandFundsAccumulator);
                if (owner.realtimeCapture != null) owner.runtimeState.captureRealtimeChanges(owner.realtimeCapture);
                if (owner.runtimeState.committedRevision() < runtimePatchRevision)
                    throw new IllegalStateException("runtime changed-index commit is out of order");
                owner.factIndexes.applyCurrent(owner.runtimeState, owner.identities);
                owner.runtimeProjectionJournal.publish(sequence,
                        owner.runtimeProjectionJournal.auditBusinessStateHash(),
                        owner.runtimeProjectionJournal.auditFundsStateHash());
                owner.publicationSequence = sequence;
                runtimePatchRevision = owner.runtimeState.committedRevision();
                owner.runtimeState.clearCommittedChanges(owner.identities);
            } catch (RuntimeException failure) {
                owner.commitPublicationFailure = new IllegalStateException(
                        "owner commit failed after deterministic mutation; restart from snapshot and log is required",
                        failure);
                throw failure;
            }
        } finally {
            active = false;
        }
    }

    private void clearBatchState() {
        deferred = false;
        dirty = false;
        provisionalOnly = false;
    }
}
