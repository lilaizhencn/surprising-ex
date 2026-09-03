package com.surprising.aeron.service.state;

/** Preallocated SPSC completion queue from one Account Lane to the core owner. */
final class PlaceAdmissionReadyQueue {
    private final long[] sequences;
    private final int indexMask;
    private volatile long producerSequence;
    private volatile long consumerSequence;

    PlaceAdmissionReadyQueue(int requestedCapacity) {
        if (requestedCapacity <= 0) throw new IllegalArgumentException("ready queue capacity must be positive");
        int capacity = 1;
        while (capacity < requestedCapacity) capacity = Math.multiplyExact(capacity, 2);
        sequences = new long[capacity];
        indexMask = capacity - 1;
    }

    void publish(long coreSequence) {
        if (coreSequence <= 0) throw new IllegalArgumentException("core sequence must be positive");
        long next = producerSequence;
        if (next - consumerSequence >= sequences.length) {
            throw new IllegalStateException("place admission ready queue is full");
        }
        sequences[(int) next & indexMask] = coreSequence;
        producerSequence = next + 1;
    }

    long poll() {
        long next = consumerSequence;
        if (next >= producerSequence) return 0;
        int index = (int) next & indexMask;
        long coreSequence = sequences[index];
        if (coreSequence <= 0) throw new IllegalStateException("place admission ready publication gap");
        sequences[index] = 0;
        consumerSequence = next + 1;
        return coreSequence;
    }
}
