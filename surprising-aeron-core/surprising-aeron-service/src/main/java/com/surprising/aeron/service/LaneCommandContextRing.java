package com.surprising.aeron.service;

import com.surprising.aeron.service.matching.CoreMatchingResult;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

final class LaneCommandContextRing {
    private final Context[] contexts;
    private final int mask;
    private long nextMatchingSubmissionId;
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

    SubmissionToken beginMatchingSubmission(long coreSequence) {
        if (nextMatchingSubmissionId == Long.MAX_VALUE) {
            throw new IllegalStateException("matching submission token id is exhausted");
        }
        return required(coreSequence).beginMatchingSubmission(++nextMatchingSubmissionId);
    }

    void discard(long coreSequence) {
        Context context = required(coreSequence);
        context.clear();
        inFlight--;
    }

    int inFlight() { return inFlight; }
    int highWaterMark() { return highWaterMark; }
    int capacity() { return contexts.length; }

    record SubmissionToken(long coreSequence, long tokenId, boolean active) {
        SubmissionToken {
            if (coreSequence <= 0 || tokenId <= 0) {
                throw new IllegalArgumentException("invalid matching submission token");
            }
        }
    }

    static final class Context {
        private static final AtomicIntegerFieldUpdater<Context> SUBMISSION_WRITERS =
                AtomicIntegerFieldUpdater.newUpdater(Context.class, "submissionWriters");
        private long coreSequence;
        private long expectedLaneMask;
        private long completedLaneMask;
        private CoreMatchingResult matchingResult;
        private CoreMatchingResult completedMatchingResult;
        private volatile SubmissionToken matchingSubmission;
        @SuppressWarnings("unused")
        private volatile int submissionWriters;
        private Context(int laneCount) {
            if (laneCount <= 0 || laneCount > Long.SIZE) {
                throw new IllegalArgumentException("invalid account lane count");
            }
        }

        long coreSequence() { return coreSequence; }
        long expectedLaneMask() { return expectedLaneMask; }
        long completedLaneMask() { return completedLaneMask; }
        CoreMatchingResult matchingResult() { return matchingResult; }

        private SubmissionToken beginMatchingSubmission(long tokenId) {
            invalidateMatchingSubmission();
            SubmissionToken token = new SubmissionToken(coreSequence, tokenId, true);
            matchingSubmission = token;
            return token;
        }

        boolean acceptsMatchingSubmission(SubmissionToken token) {
            return token != null && token.active() && matchingSubmission == token;
        }

        SubmissionToken matchingSubmissionToken() {
            return matchingSubmission;
        }

        boolean enqueueMatchingCompletion(
                SubmissionToken token,
                MatchingCompletionQueue completions,
                CoreMatchingResult result) {
            SUBMISSION_WRITERS.incrementAndGet(this);
            try {
                if (!acceptsMatchingSubmission(token)) return false;
                return completions.offer(result);
            } finally {
                SUBMISSION_WRITERS.decrementAndGet(this);
            }
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
            invalidateMatchingSubmission();
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
            invalidateMatchingSubmission();
            coreSequence = 0;
            expectedLaneMask = 0;
            completedLaneMask = 0;
            matchingResult = null;
            completedMatchingResult = null;
        }

        private void invalidateMatchingSubmission() {
            SubmissionToken current = matchingSubmission;
            if (current != null && current.active()) {
                matchingSubmission = new SubmissionToken(
                        current.coreSequence(), current.tokenId(), false);
            }
            while (submissionWriters != 0) Thread.onSpinWait();
        }
    }
}
