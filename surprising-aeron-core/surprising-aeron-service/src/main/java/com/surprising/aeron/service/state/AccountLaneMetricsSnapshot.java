package com.surprising.aeron.service.state;

public record AccountLaneMetricsSnapshot(
        int queueDepth,
        int queueCapacity,
        int queueHighWaterMark,
        long rejectedSubmissions,
        long oldestPendingSequence,
        long[] completedOperations,
        long[] latencySamples,
        long[] totalLatencyNanos,
        long[] maxLatencyNanos) {

    public static final int OPERATION_TYPE_COUNT = AccountLaneOperationType.values().length;

    public AccountLaneMetricsSnapshot {
        if (queueDepth < 0 || queueCapacity <= 0 || queueDepth > queueCapacity
                || queueHighWaterMark < queueDepth || queueHighWaterMark > queueCapacity
                || rejectedSubmissions < 0 || oldestPendingSequence < 0
                || !length(completedOperations) || !length(latencySamples)
                || !length(totalLatencyNanos) || !length(maxLatencyNanos)) {
            throw new IllegalArgumentException("invalid Account Lane metrics snapshot");
        }
        completedOperations = completedOperations.clone();
        latencySamples = latencySamples.clone();
        totalLatencyNanos = totalLatencyNanos.clone();
        maxLatencyNanos = maxLatencyNanos.clone();
    }

    static AccountLaneMetricsSnapshot empty(int queueCapacity) {
        return new AccountLaneMetricsSnapshot(0, queueCapacity, 0, 0, 0,
                new long[OPERATION_TYPE_COUNT], new long[OPERATION_TYPE_COUNT],
                new long[OPERATION_TYPE_COUNT], new long[OPERATION_TYPE_COUNT]);
    }

    private static boolean length(long[] values) {
        return values != null && values.length == OPERATION_TYPE_COUNT;
    }

    @Override public long[] completedOperations() { return completedOperations.clone(); }
    @Override public long[] latencySamples() { return latencySamples.clone(); }
    @Override public long[] totalLatencyNanos() { return totalLatencyNanos.clone(); }
    @Override public long[] maxLatencyNanos() { return maxLatencyNanos.clone(); }
}
