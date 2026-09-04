package com.surprising.aeron.service;

import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.service.matching.CoreMatchingResult;

final class LaneCommandContextRing {
    private final Context[] contexts;
    private final int mask;
    private int inFlight;
    private int highWaterMark;

    LaneCommandContextRing(int capacity, int laneCount) {
        if (capacity <= 0 || (capacity & (capacity - 1)) != 0
                || laneCount <= 0 || laneCount > Long.SIZE) {
            throw new IllegalArgumentException("lane command context capacity must be a power of two");
        }
        contexts = new Context[capacity];
        for (int index = 0; index < capacity; index++) contexts[index] = new Context(laneCount);
        mask = capacity - 1;
    }

    Context claim(long coreSequence) {
        if (coreSequence <= 0) throw new IllegalArgumentException("coreSequence must be positive");
        Context context = contexts[(int) coreSequence & mask];
        if (context.coreSequence != 0) throw new IllegalStateException("lane command context ring is full");
        context.coreSequence = coreSequence;
        inFlight++;
        highWaterMark = Math.max(highWaterMark, inFlight);
        return context;
    }

    Context required(long coreSequence) {
        Context context = contexts[(int) coreSequence & mask];
        if (context.coreSequence != coreSequence) throw new IllegalStateException("unknown lane command context");
        return context;
    }

    void release(long coreSequence) {
        Context context = required(coreSequence);
        if (!context.complete()) throw new IllegalStateException("incomplete lane command context");
        context.clear();
        inFlight--;
    }

    boolean claimed(long coreSequence) {
        return contexts[(int) coreSequence & mask].coreSequence == coreSequence;
    }

    Context contextAt(int slot) { return contexts[slot]; }

    void discard(long coreSequence) {
        Context context = required(coreSequence);
        context.clear();
        inFlight--;
    }

    int inFlight() { return inFlight; }
    int highWaterMark() { return highWaterMark; }
    int capacity() { return contexts.length; }

    static final class Context {
        private long coreSequence;
        private long expectedLaneMask;
        private long completedLaneMask;
        private CoreMatchingResult matchingResult;
        private CoreMatchingResult completedMatchingResult;
        private CoreAdmissionReservation admission;
        private java.util.List<Long> commitChangedUserIds;
        private java.util.List<Long> commitChangedOrderIds;
        private final com.surprising.aeron.service.state.RuntimeFundsAccumulator commitFundsAccumulator =
                new com.surprising.aeron.service.state.RuntimeFundsAccumulator(32);
        private boolean commitSnapshotDirty;
        private boolean commitSnapshotProvisionalOnly;
        private CoreResultCode matchingRejection;
        private boolean pendingReady;
        private PendingMatching pending;
        private final PendingMatching reusablePending = new PendingMatching();
        private Context(int laneCount) {
            if (laneCount <= 0 || laneCount > Long.SIZE) {
                throw new IllegalArgumentException("invalid account lane count");
            }
        }

        long coreSequence() { return coreSequence; }
        long expectedLaneMask() { return expectedLaneMask; }
        long completedLaneMask() { return completedLaneMask; }
        CoreMatchingResult matchingResult() { return matchingResult; }
        CoreAdmissionReservation admission() { return admission; }
        PendingMatching pending() { return pending; }
        PendingMatching reusablePending() { return reusablePending; }

        void pending(PendingMatching value) {
            if (value == null || value.sequence() != coreSequence) {
                throw new IllegalStateException("invalid pending matching sequence");
            }
            pending = value;
        }

        void admission(CoreAdmissionReservation value) {
            if (value == null || admission != null) {
                throw new IllegalStateException("invalid sequence admission");
            }
            admission = value;
        }

        void suspendCommitContext(java.util.List<Long> changedUserIds,
                                  java.util.List<Long> changedOrderIds,
                                  com.surprising.aeron.service.state.RuntimeFundsAccumulator fundsAccumulator,
                                  boolean snapshotDirty, boolean snapshotProvisionalOnly) {
            if (changedUserIds == null || changedOrderIds == null || fundsAccumulator == null
                    || commitChangedUserIds != null) {
                throw new IllegalStateException("invalid suspended sequence commit context");
            }
            commitChangedUserIds = changedUserIds;
            commitChangedOrderIds = changedOrderIds;
            commitFundsAccumulator.clear();
            commitFundsAccumulator.add(fundsAccumulator);
            commitSnapshotDirty = snapshotDirty;
            commitSnapshotProvisionalOnly = snapshotProvisionalOnly;
        }

        java.util.List<Long> commitChangedUserIds() { return requiredCommit(commitChangedUserIds); }
        java.util.List<Long> commitChangedOrderIds() { return requiredCommit(commitChangedOrderIds); }
        void copyCommitFundsTo(com.surprising.aeron.service.state.RuntimeFundsAccumulator target) {
            requireCommit();
            target.clear();
            target.add(commitFundsAccumulator);
        }
        boolean commitSnapshotDirty() { requireCommit(); return commitSnapshotDirty; }
        boolean commitSnapshotProvisionalOnly() { requireCommit(); return commitSnapshotProvisionalOnly; }

        void clearCommitContext() {
            requireCommit();
            commitChangedUserIds = null;
            commitChangedOrderIds = null;
            commitFundsAccumulator.clear();
            commitSnapshotDirty = false;
            commitSnapshotProvisionalOnly = false;
        }

        private void requireCommit() {
            if (commitChangedUserIds == null) throw new IllegalStateException("sequence commit context is missing");
        }

        private <T> T requiredCommit(T value) {
            requireCommit();
            return value;
        }

        boolean hasCommitContext() { return commitChangedUserIds != null; }

        void publishMatchingCompletion(CoreMatchingResult result) {
            if (result == null || result.nativeCommand().coreSequence() != coreSequence) {
                throw new IllegalStateException("invalid matching completion");
            }
            if (completedMatchingResult == null) completedMatchingResult = result;
        }

        CoreMatchingResult takeMatchingCompletion() {
            CoreMatchingResult result = completedMatchingResult;
            completedMatchingResult = null;
            return result;
        }

        CoreMatchingResult matchingCompletion() {
            return completedMatchingResult;
        }

        boolean hasMatchingCompletion() {
            return completedMatchingResult != null;
        }

        void rejectMatching(CoreResultCode resultCode) {
            if (resultCode == null || matchingRejection != null) {
                throw new IllegalStateException("invalid matching rejection");
            }
            matchingRejection = resultCode;
        }

        boolean hasMatchingRejection() { return matchingRejection != null; }

        CoreResultCode matchingRejection() {
            if (matchingRejection == null) throw new IllegalStateException("matching rejection is missing");
            return matchingRejection;
        }

        void markPendingReady() { pendingReady = true; }

        boolean takePendingReady() {
            boolean value = pendingReady;
            pendingReady = false;
            return value;
        }

        boolean pendingReady() { return pendingReady; }

        void resetMatchingContinuation() {
            completedMatchingResult = null;
        }

        void result(CoreMatchingResult result, long expectedMask, long validLaneMask) {
            if (result == null || result.nativeCommand().coreSequence() != coreSequence
                    || (expectedMask & ~validLaneMask) != 0
                    || matchingResult != null) {
                throw new IllegalStateException("invalid immutable matching result fanout");
            }
            matchingResult = result;
            expectedLaneMask = expectedMask;
        }

        void completeLanes(long laneMask) {
            if (matchingResult == null
                    || (laneMask & ~expectedLaneMask) != 0
                    || (completedLaneMask & laneMask) != 0) {
                throw new IllegalStateException("duplicate or unexpected account lane completion"
                        + " laneMask=" + laneMask + " expected=" + expectedLaneMask
                        + " completed=" + completedLaneMask);
            }
            completedLaneMask |= laneMask;
        }

        boolean complete() {
            return matchingResult != null && completedLaneMask == expectedLaneMask;
        }

        private void clear() {
            coreSequence = 0;
            expectedLaneMask = 0;
            completedLaneMask = 0;
            matchingResult = null;
            completedMatchingResult = null;
            admission = null;
            commitChangedUserIds = null;
            commitChangedOrderIds = null;
            commitFundsAccumulator.clear();
            commitSnapshotDirty = false;
            commitSnapshotProvisionalOnly = false;
            matchingRejection = null;
            pendingReady = false;
            pending = null;
        }
    }
}
