package com.surprising.aeron.service.state;

final class AccountLaneMetricsTracker {

    private static final long LATENCY_SAMPLE_MASK = 1_023;
    private final int mask;
    private final byte[] operationTypes;
    private final long[] enqueuedNanos;
    private final long[] completedOperations = new long[AccountLaneMetricsSnapshot.OPERATION_TYPE_COUNT];
    private final long[] latencySamples = new long[AccountLaneMetricsSnapshot.OPERATION_TYPE_COUNT];
    private final long[] totalLatencyNanos = new long[AccountLaneMetricsSnapshot.OPERATION_TYPE_COUNT];
    private final long[] maxLatencyNanos = new long[AccountLaneMetricsSnapshot.OPERATION_TYPE_COUNT];
    private int queueHighWaterMark;
    private long rejectedSubmissions;

    AccountLaneMetricsTracker(int queueCapacity) {
        this.mask = queueCapacity - 1;
        this.operationTypes = new byte[queueCapacity];
        this.enqueuedNanos = new long[queueCapacity];
    }

    void submitted(long sequence, AccountLaneOperationType type, int queueDepth) {
        int slot = (int) sequence & mask;
        operationTypes[slot] = (byte) type.ordinal();
        enqueuedNanos[slot] = (sequence & LATENCY_SAMPLE_MASK) == 1 ? System.nanoTime() : 0;
        queueHighWaterMark = Math.max(queueHighWaterMark, queueDepth);
    }

    void rejected() {
        rejectedSubmissions = Math.incrementExact(rejectedSubmissions);
    }

    void completed(long sequence) {
        int slot = (int) sequence & mask;
        int type = operationTypes[slot];
        completedOperations[type] = Math.incrementExact(completedOperations[type]);
        long startedAt = enqueuedNanos[slot];
        enqueuedNanos[slot] = 0;
        if (startedAt == 0) return;
        long latencyNanos = Math.max(0, System.nanoTime() - startedAt);
        latencySamples[type] = Math.incrementExact(latencySamples[type]);
        totalLatencyNanos[type] = Math.addExact(totalLatencyNanos[type], latencyNanos);
        maxLatencyNanos[type] = Math.max(maxLatencyNanos[type], latencyNanos);
    }

    int queueHighWaterMark() {
        return queueHighWaterMark;
    }

    AccountLaneMetricsSnapshot snapshot(int queueDepth, int queueCapacity, long oldestPendingSequence) {
        return new AccountLaneMetricsSnapshot(queueDepth, queueCapacity, queueHighWaterMark,
                rejectedSubmissions, oldestPendingSequence, completedOperations, latencySamples,
                totalLatencyNanos, maxLatencyNanos);
    }
}
