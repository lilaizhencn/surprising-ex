package com.surprising.aeron.service;

import com.surprising.aeron.service.matching.CoreMatchingResult;
import com.surprising.aeron.service.state.AccountLaneView;
import com.surprising.aeron.service.state.MatcherSettlementPlan;
import com.surprising.aeron.service.state.PerpetualLaneJournal;

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
        private MatcherSettlementPlan settlementPlan;
        private PerpetualLaneJournal[] settlementJournals;
        private CoreMatchingResult completedMatchingResult;
        private long matchingSubmissionGeneration;
        private final long[] laneRevisions;
        private final long[] localStateHashes;
        private final long[] localFundsHashes;

        private Context(int laneCount) {
            laneRevisions = new long[laneCount];
            localStateHashes = new long[laneCount];
            localFundsHashes = new long[laneCount];
        }

        long coreSequence() { return coreSequence; }
        long expectedLaneMask() { return expectedLaneMask; }
        long completedLaneMask() { return completedLaneMask; }
        CoreMatchingResult matchingResult() { return matchingResult; }
        MatcherSettlementPlan settlementPlan() { return settlementPlan; }
        PerpetualLaneJournal[] settlementJournals() { return settlementJournals; }

        long beginMatchingSubmission() {
            return ++matchingSubmissionGeneration;
        }

        boolean acceptsMatchingSubmission(long generation) {
            return coreSequence != 0 && generation == matchingSubmissionGeneration;
        }

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

        void resetMatchingContinuation() {
            completedMatchingResult = null;
            matchingSubmissionGeneration++;
        }

        void result(CoreMatchingResult result, MatcherSettlementPlan plan,
                    long expectedMask, long validLaneMask) {
            if (result == null || result.nativeCommand().coreSequence() != coreSequence
                    || (expectedMask & ~validLaneMask) != 0
                    || plan != null && (plan.coreSequence() != coreSequence
                    || plan.requiredLaneMask() != expectedMask)
                    || matchingResult != null) {
                throw new IllegalStateException("invalid immutable matching result fanout");
            }
            matchingResult = result;
            settlementPlan = plan;
            expectedLaneMask = expectedMask;
        }

        void result(CoreMatchingResult result, long expectedMask, long validLaneMask) {
            result(result, null, expectedMask, validLaneMask);
        }

        void dispatch(PerpetualLaneJournal[] journals) {
            if (matchingResult == null || settlementPlan == null || journals == null
                    || settlementJournals != null) {
                throw new IllegalStateException("invalid settlement journal dispatch");
            }
            settlementJournals = journals;
        }

        boolean settlementDispatched() { return settlementJournals != null; }

        boolean settlementReady() {
            if (settlementJournals == null) return false;
            for (PerpetualLaneJournal journal : settlementJournals) {
                if (journal != null && !journal.completed()) return false;
            }
            return true;
        }

        Throwable settlementFailure() {
            if (settlementJournals == null) return null;
            for (PerpetualLaneJournal journal : settlementJournals) {
                if (journal != null && journal.failure() != null) return journal.failure();
            }
            return null;
        }

        void completeLane(int laneId, long laneRevision, long localStateHash, long localFundsHash) {
            if (matchingResult == null || laneId < 0 || laneId >= laneRevisions.length) {
                throw new IllegalStateException("invalid account lane ACK");
            }
            long laneBit = 1L << laneId;
            if ((expectedLaneMask & laneBit) == 0 || (completedLaneMask & laneBit) != 0) {
                throw new IllegalStateException("duplicate or unexpected account lane ACK");
            }
            completedLaneMask |= laneBit;
            laneRevisions[laneId] = laneRevision;
            localStateHashes[laneId] = localStateHash;
            localFundsHashes[laneId] = localFundsHash;
        }

        void validate(AccountLaneView view) {
            if (view == null || view.laneId() < 0 || view.laneId() >= laneRevisions.length) {
                throw new IllegalStateException("invalid account lane ACK view");
            }
            long laneBit = 1L << view.laneId();
            if ((completedLaneMask & laneBit) == 0
                    || laneRevisions[view.laneId()] != view.revision()
                    || localStateHashes[view.laneId()] != view.localStateHash()
                    || localFundsHashes[view.laneId()] != view.localFundsHash()) {
                throw new IllegalStateException("account lane ACK revision or hash mismatch");
            }
        }

        boolean complete() {
            return matchingResult != null && completedLaneMask == expectedLaneMask;
        }

        private void clear() {
            coreSequence = 0;
            expectedLaneMask = 0;
            completedLaneMask = 0;
            matchingResult = null;
            settlementPlan = null;
            settlementJournals = null;
            completedMatchingResult = null;
            matchingSubmissionGeneration++;
            java.util.Arrays.fill(laneRevisions, 0);
            java.util.Arrays.fill(localStateHashes, 0);
            java.util.Arrays.fill(localFundsHashes, 0);
        }
    }
}
