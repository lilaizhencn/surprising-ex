package com.surprising.aeron.service;

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
        private Context(int laneCount) {
            if (laneCount <= 0 || laneCount > Long.SIZE) {
                throw new IllegalArgumentException("invalid account lane count");
            }
        }

        long coreSequence() { return coreSequence; }
        long expectedLaneMask() { return expectedLaneMask; }
        long completedLaneMask() { return completedLaneMask; }
        CoreMatchingResult matchingResult() { return matchingResult; }

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
        }
    }
}
