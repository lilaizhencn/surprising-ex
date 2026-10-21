package com.surprising.aeron.service.lane;

/** Preallocated SPSC sequence queue from one Account Lane to the core owner. */
public final class LaneSequenceQueue {
    private final long[] sequences;
    private final int indexMask;
    /** 消费者独占游标及已观察到的生产者上界；非空时不重复跨核读取。 */
    private long consumerCursor, observedProducer;
    private final PaddedSequence producerSequence = new PaddedSequence();
    private final PaddedSequence consumerSequence = new PaddedSequence();

    public LaneSequenceQueue(int requestedCapacity) {
        if (requestedCapacity <= 0) throw new IllegalArgumentException("ready queue capacity must be positive");
        int capacity = 1;
        while (capacity < requestedCapacity) capacity = Math.multiplyExact(capacity, 2);
        sequences = new long[capacity];
        indexMask = capacity - 1;
    }

    public void publish(long coreSequence) {
        if (coreSequence <= 0) throw new IllegalArgumentException("core sequence must be positive");
        long next = producerSequence.value;
        if (next - consumerSequence.value >= sequences.length) {
            throw new IllegalStateException("lane sequence queue is full");
        }
        sequences[(int) next & indexMask] = coreSequence;
        producerSequence.value = next + 1;
    }

    public long poll() {
        long next = consumerCursor;
        if (!hasPending()) return 0;
        int index = (int) next & indexMask;
        long coreSequence = sequences[index];
        if (coreSequence <= 0) throw new IllegalStateException("lane sequence publication gap");
        sequences[index] = 0;
        consumerCursor = next + 1;
        consumerSequence.value = consumerCursor;
        return coreSequence;
    }

    public boolean hasPending() {
        if (consumerCursor < observedProducer) return true;
        observedProducer = producerSequence.value;
        return consumerCursor < observedProducer;
    }

    boolean hasCapacity() {
        return producerSequence.value - consumerSequence.value < sequences.length;
    }

    int capacity() { return sequences.length; }

    /** Producer and consumer write different cache lines on the hot SPSC path. */
    private static class PaddedSequenceLhsPadding {
        @SuppressWarnings("unused")
        private long p00, p01, p02, p03, p04, p05, p06, p07, p08, p09, p0a, p0b, p0c, p0d, p0e;
    }

    private static class PaddedSequenceValue extends PaddedSequenceLhsPadding {
        protected volatile long value;
    }

    private static final class PaddedSequence extends PaddedSequenceValue {
        @SuppressWarnings("unused")
        private long p10, p11, p12, p13, p14, p15, p16, p17, p18, p19, p1a, p1b, p1c, p1d, p1e;
    }
}
