package com.surprising.aeron.service;

import com.surprising.aeron.service.matching.CoreMatchingResult;

final class LaneCommandContextRing {
    private final Context[] contexts;
    private final int mask;
    private int inFlight;
    private int highWaterMark;

    LaneCommandContextRing(int capacity) {
        if (capacity <= 0 || (capacity & (capacity - 1)) != 0) {
            throw new IllegalArgumentException("lane command context capacity must be a power of two");
        }
        contexts = new Context[capacity];
        for (int index = 0; index < capacity; index++) contexts[index] = new Context();
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
        private long ackLaneMask;
        private CoreMatchingResult matchingResult;
        private long feeUnits;
        private long insuranceUnits;
        private long deficitUnits;
        private long fundingResidualUnits;
        private long roundingResidualUnits;
        private long clearingUnits;

        long coreSequence() { return coreSequence; }
        long expectedLaneMask() { return expectedLaneMask; }
        long ackLaneMask() { return ackLaneMask; }
        CoreMatchingResult matchingResult() { return matchingResult; }
        long feeUnits() { return feeUnits; }
        long insuranceUnits() { return insuranceUnits; }
        long deficitUnits() { return deficitUnits; }
        long fundingResidualUnits() { return fundingResidualUnits; }
        long roundingResidualUnits() { return roundingResidualUnits; }
        long clearingUnits() { return clearingUnits; }

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
            if (ack == null || ack.coreSequence() != coreSequence || matchingResult == null) {
                throw new IllegalStateException("invalid account lane ACK");
            }
            long laneBit = 1L << ack.laneId();
            if ((expectedLaneMask & laneBit) == 0 || (ackLaneMask & laneBit) != 0) {
                throw new IllegalStateException("duplicate or unexpected account lane ACK");
            }
            ackLaneMask |= laneBit;
            feeUnits = Math.addExact(feeUnits, ack.feeUnits());
            insuranceUnits = Math.addExact(insuranceUnits, ack.insuranceUnits());
            deficitUnits = Math.addExact(deficitUnits, ack.deficitUnits());
            fundingResidualUnits = Math.addExact(fundingResidualUnits, ack.fundingResidualUnits());
            roundingResidualUnits = Math.addExact(roundingResidualUnits, ack.roundingResidualUnits());
            clearingUnits = Math.addExact(clearingUnits, ack.clearingUnits());
        }

        boolean complete() {
            return matchingResult != null && ackLaneMask == expectedLaneMask;
        }

        private void clear() {
            coreSequence = 0;
            expectedLaneMask = 0;
            ackLaneMask = 0;
            matchingResult = null;
            feeUnits = 0;
            insuranceUnits = 0;
            deficitUnits = 0;
            fundingResidualUnits = 0;
            roundingResidualUnits = 0;
            clearingUnits = 0;
        }
    }
}
