package com.surprising.aeron.service;

import com.surprising.aeron.service.matching.CoreMatchingResult;
import com.surprising.aeron.service.state.AccountLaneView;
import com.surprising.aeron.service.state.RuntimeTreasuryDelta;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;

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

    CompletableFuture<?>[] activeMatchingFutures() {
        ArrayList<CompletableFuture<?>> futures = new ArrayList<>(inFlight);
        for (Context context : contexts) {
            if (context.coreSequence != 0 && context.matchingFuture != null) {
                futures.add(context.matchingFuture);
            }
        }
        return futures.toArray(CompletableFuture[]::new);
    }

    static final class Context {
        private long coreSequence;
        private long expectedLaneMask;
        private long ackLaneMask;
        private CoreMatchingResult matchingResult;
        private CoreMatchingResult completedMatchingResult;
        private CompletableFuture<CoreMatchingResult> matchingFuture;
        private final RuntimeTreasuryDelta treasuryDelta = new RuntimeTreasuryDelta(
                RuntimeTreasuryDelta.ORDER_BATCH_CAPACITY);
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
        long ackLaneMask() { return ackLaneMask; }
        CoreMatchingResult matchingResult() { return matchingResult; }
        RuntimeTreasuryDelta treasuryDelta() { return treasuryDelta; }

        void trackMatchingFuture(CompletableFuture<CoreMatchingResult> future) {
            if (future == null || matchingFuture != null) {
                throw new IllegalStateException("matching future is already tracked");
            }
            matchingFuture = future;
        }

        CompletableFuture<CoreMatchingResult> matchingFuture() {
            return matchingFuture;
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
            if (result != null) matchingFuture = null;
            return result;
        }

        boolean hasMatchingCompletion() {
            return completedMatchingResult != null;
        }

        void resetMatchingContinuation() {
            completedMatchingResult = null;
            matchingFuture = null;
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

        void acknowledge(AccountLaneAck ack) {
            if (ack == null || ack.coreSequence() != coreSequence || matchingResult == null
                    || ack.matchingResult() != matchingResult || ack.laneId() >= laneRevisions.length) {
                throw new IllegalStateException("invalid account lane ACK");
            }
            long laneBit = 1L << ack.laneId();
            if ((expectedLaneMask & laneBit) == 0 || (ackLaneMask & laneBit) != 0) {
                throw new IllegalStateException("duplicate or unexpected account lane ACK");
            }
            ackLaneMask |= laneBit;
            laneRevisions[ack.laneId()] = ack.laneRevision();
            localStateHashes[ack.laneId()] = ack.localStateHash();
            localFundsHashes[ack.laneId()] = ack.localFundsHash();
            treasuryDelta.merge(ack.treasuryDelta());
        }

        void validate(AccountLaneView view) {
            if (view == null || view.laneId() < 0 || view.laneId() >= laneRevisions.length) {
                throw new IllegalStateException("invalid account lane ACK view");
            }
            long laneBit = 1L << view.laneId();
            if ((ackLaneMask & laneBit) == 0
                    || laneRevisions[view.laneId()] != view.revision()
                    || localStateHashes[view.laneId()] != view.localStateHash()
                    || localFundsHashes[view.laneId()] != view.localFundsHash()) {
                throw new IllegalStateException("account lane ACK revision or hash mismatch");
            }
        }

        boolean complete() {
            return matchingResult != null && ackLaneMask == expectedLaneMask;
        }

        private void clear() {
            coreSequence = 0;
            expectedLaneMask = 0;
            ackLaneMask = 0;
            matchingResult = null;
            completedMatchingResult = null;
            matchingFuture = null;
            treasuryDelta.clear();
            java.util.Arrays.fill(laneRevisions, 0);
            java.util.Arrays.fill(localStateHashes, 0);
            java.util.Arrays.fill(localFundsHashes, 0);
        }
    }
}
