package com.surprising.aeron.service.state;

/** Preallocated SPSC sequence queue from one Account Lane to the core owner. */
final class LaneSequenceQueue {
    private final long[] sequences;
    private final int indexMask;
    private final PaddedSequence producerSequence = new PaddedSequence();
    private final PaddedSequence consumerSequence = new PaddedSequence();

    LaneSequenceQueue(int requestedCapacity) {
        if (requestedCapacity <= 0) throw new IllegalArgumentException("ready queue capacity must be positive");
        int capacity = 1;
        while (capacity < requestedCapacity) capacity = Math.multiplyExact(capacity, 2);
        sequences = new long[capacity];
        indexMask = capacity - 1;
    }

    void publish(long coreSequence) {
        if (coreSequence <= 0) throw new IllegalArgumentException("core sequence must be positive");
        long next = producerSequence.value;
        if (next - consumerSequence.value >= sequences.length) {
            throw new IllegalStateException("lane sequence queue is full");
        }
        sequences[(int) next & indexMask] = coreSequence;
        producerSequence.value = next + 1;
    }

    long poll() {
        long next = consumerSequence.value;
        if (next >= producerSequence.value) return 0;
        int index = (int) next & indexMask;
        long coreSequence = sequences[index];
        if (coreSequence <= 0) throw new IllegalStateException("lane sequence publication gap");
        sequences[index] = 0;
        consumerSequence.value = next + 1;
        return coreSequence;
    }

    boolean hasPending() {
        return consumerSequence.value < producerSequence.value;
    }

    /** Producer and consumer write different cache lines on the hot SPSC path. */
    private static final class PaddedSequence {
        @SuppressWarnings("unused")
        private long p01, p02, p03, p04, p05, p06, p07;
        private volatile long value;
        @SuppressWarnings("unused")
        private long p11, p12, p13, p14, p15, p16, p17;
    }
}
