package com.surprising.aeron.service.orchestration.trade.commit;

import com.surprising.aeron.service.orchestration.TradingCoreRuntime;
import java.util.Objects;

/**
 * Owns the Owner-thread commit publication batch.
 *
 * <p>The matching coordinator decides when an ordered command is complete, while this class
 * owns whether a state change is deferred, dirty or provisional and performs the one publication
 * that advances the runtime projection. It does not calculate funds, mutate orders or own Lane
 * state.</p>
 */
public final class CommitPublication {
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

    public boolean deferred() { return deferred; }
    public boolean dirty() { return dirty; }
    public boolean provisionalOnly() { return provisionalOnly; }

    public void initialize(long runtimePatchRevision, long publishedSequence) {
        if (deferred || dirty || provisionalOnly || active) {
            throw new IllegalStateException("cannot initialize an active commit publication");
        }
        if (publishedSequence < 0) throw new IllegalArgumentException("initial commit sequence is negative");
        this.runtimePatchRevision = runtimePatchRevision;
        this.publishedSequence = publishedSequence;
    }

    public void activate() {
        if (closed) throw new IllegalStateException("cannot activate closed commit publication");
        activated = true;
    }

    public boolean activated() { return activated; }
    public long publishedSequence() { return publishedSequence; }

    public void assertHealthy() {
        if (closed) throw new IllegalStateException("commit publication is closed");
        if (!activated) throw new IllegalStateException("commit publication is not activated");
    }

    public void close() { closed = true; }

    public void request() {
        if (deferred) {
            dirty = true;
            provisionalOnly = false;
            return;
        }
        publish();
    }

    public void begin() {
        if (deferred) throw new IllegalStateException("snapshot projection batch is already active");
        deferred = true;
        dirty = false;
        provisionalOnly = false;
    }

    public void complete() {
        boolean changed = dirty;
        boolean provisional = provisionalOnly;
        clearBatchState();
        if (changed && provisional) owner.runtimeState().clearChangedKeys();
        else if (changed) publish();
    }

    public void abort() { clearBatchState(); }

    public void deferProvisionalProjection() {
        if (!deferred) throw new IllegalStateException("provisional projection requires a command batch");
        if (!dirty) provisionalOnly = true;
        dirty = true;
    }

    public void suspend() { clearBatchState(); }

    public void restore(boolean dirty, boolean provisionalOnly) {
        if (deferred || active) throw new IllegalStateException("another commit publication is active");
        deferred = true;
        this.dirty = dirty;
        this.provisionalOnly = provisionalOnly;
    }

    void publish() {
        if (active) throw new IllegalStateException("owner commit publisher is already active");
        active = true;
        try {
            assertHealthy();
            long sequence = Math.incrementExact(publishedSequence);
            try {
                if (!owner.factContextActive()) {
                    throw new IllegalStateException("runtime commit requires an active command scope");
                }
                owner.runtimeState().appendFundsDelta(owner.runtimeState().coldCommandFunds());
                if (owner.realtimeCapture() != null) owner.runtimeState().captureRealtimeChanges(owner.realtimeCapture());
                if (owner.runtimeState().committedRevision() < runtimePatchRevision)
                    throw new IllegalStateException("runtime changed-index commit is out of order");
                owner.factIndexes().applyCurrent(owner.runtimeState(), owner.identities());
                publishedSequence = sequence;
                runtimePatchRevision = owner.runtimeState().committedRevision();
                owner.runtimeState().clearCommittedChanges(owner.identities());
            } catch (RuntimeException failure) {
                owner.commitPublicationFailure(new IllegalStateException(
                        "owner commit failed after deterministic mutation; restart from snapshot and log is required",
                        failure));
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
