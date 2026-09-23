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
    private long publishedSequence;
    private boolean activated;
    private boolean closed;

    CommitPublication(TradingCoreRuntime owner) {
        this.owner = Objects.requireNonNull(owner);
    }

    boolean deferred() { return deferred; }
    boolean dirty() { return dirty; }
    boolean provisionalOnly() { return provisionalOnly; }

    void initialize(long runtimePatchRevision, long publishedSequence) {
        if (deferred || dirty || provisionalOnly || active) {
            throw new IllegalStateException("cannot initialize an active commit publication");
        }
        if (publishedSequence < 0) throw new IllegalArgumentException("initial commit sequence is negative");
        this.runtimePatchRevision = runtimePatchRevision;
        this.publishedSequence = publishedSequence;
    }

    void activate() {
        if (closed) throw new IllegalStateException("cannot activate closed commit publication");
        activated = true;
    }

    boolean activated() { return activated; }
    long publishedSequence() { return publishedSequence; }

    void assertHealthy() {
        if (closed) throw new IllegalStateException("commit publication is closed");
        if (!activated) throw new IllegalStateException("commit publication is not activated");
    }

    void close() { closed = true; }

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
        CoreMatchingPhaseMetrics.OwnerPublication timing = null;
        long timingStart = 0;
        try {
            assertHealthy();
            long sequence = Math.incrementExact(publishedSequence);
            timing = CoreMatchingPhaseMetrics.sampleOwnerPublication(sequence);
            timingStart = timing == null ? 0 : System.nanoTime();
            long stepStart = timingStart;
            try {
                if (!owner.factContextActive) {
                    throw new IllegalStateException("runtime commit requires an active command scope");
                }
                owner.runtimeState.appendFundsDelta(owner.commandFundsAccumulator);
                if (timing != null) { timing.fundsNanos = System.nanoTime() - stepStart; stepStart = System.nanoTime(); }
                if (owner.realtimeCapture != null) owner.runtimeState.captureRealtimeChanges(owner.realtimeCapture);
                if (timing != null) { timing.realtimeCaptureNanos = System.nanoTime() - stepStart; stepStart = System.nanoTime(); }
                if (owner.runtimeState.committedRevision() < runtimePatchRevision)
                    throw new IllegalStateException("runtime changed-index commit is out of order");
                owner.factIndexes.applyCurrent(owner.runtimeState, owner.identities);
                if (timing != null) { timing.indexesNanos = System.nanoTime() - stepStart; stepStart = System.nanoTime(); }
                publishedSequence = sequence;
                runtimePatchRevision = owner.runtimeState.committedRevision();
                if (timing != null) { timing.journalNanos = System.nanoTime() - stepStart; stepStart = System.nanoTime(); }
                owner.runtimeState.clearCommittedChanges(owner.identities);
                if (timing != null) { timing.clearNanos = System.nanoTime() - stepStart; timing.completed = true; }
            } catch (RuntimeException failure) {
                owner.commitPublicationFailure = new IllegalStateException(
                        "owner commit failed after deterministic mutation; restart from snapshot and log is required",
                        failure);
                throw failure;
            }
        } finally {
            active = false;
            if (timing != null) {
                timing.totalNanos = System.nanoTime() - timingStart;
                timing.end();
                timing.commit();
            }
        }
    }

    private void clearBatchState() {
        deferred = false;
        dirty = false;
        provisionalOnly = false;
    }
}
