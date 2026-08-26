package com.surprising.aeron.protocol;

public record CoreLaneMetricsView(
        int matchingEngineCount,
        int accountLaneCount,
        int matcherDispatchDepth,
        int matcherDispatchCapacity,
        int matcherDispatchHighWaterMark,
        int matchingCompletionDepth,
        int matchingCompletionCapacity,
        int matchingCompletionHighWaterMark,
        int commandContextDepth,
        int commandContextCapacity,
        int commandContextHighWaterMark,
        long committedCoreSequence,
        long[] accountLaneRevisions,
        long[] accountLaneAppliedSequences,
        long[] accountLaneCommittedSequences,
        int[] accountLaneQueueDepths,
        int[] accountLaneQueueCapacities,
        int[] accountLaneQueueHighWaterMarks,
        long[] accountLaneRejectedSubmissions,
        long[] accountLaneOldestPendingSequences,
        long[] accountLaneCompletedOperations,
        long[] accountLaneLatencySamples,
        long[] accountLaneTotalLatencyNanos,
        long[] accountLaneMaxLatencyNanos) {

    public static final int OPERATION_TYPE_COUNT = 4;

    public CoreLaneMetricsView {
        int operationValues = Math.multiplyExact(accountLaneCount, OPERATION_TYPE_COUNT);
        if (matchingEngineCount <= 0 || accountLaneCount <= 0 || matcherDispatchDepth < 0
                || matcherDispatchCapacity <= 0 || matcherDispatchDepth > matcherDispatchCapacity
                || matcherDispatchHighWaterMark < matcherDispatchDepth
                || matcherDispatchHighWaterMark > matcherDispatchCapacity
                || matchingCompletionDepth < 0 || matchingCompletionCapacity <= 0
                || matchingCompletionDepth > matchingCompletionCapacity
                || matchingCompletionHighWaterMark < matchingCompletionDepth
                || matchingCompletionHighWaterMark > matchingCompletionCapacity
                || commandContextDepth < 0 || commandContextCapacity <= 0
                || commandContextDepth > commandContextCapacity
                || commandContextHighWaterMark < commandContextDepth
                || commandContextHighWaterMark > commandContextCapacity
                || committedCoreSequence < 0
                || !length(accountLaneRevisions, accountLaneCount)
                || !length(accountLaneAppliedSequences, accountLaneCount)
                || !length(accountLaneCommittedSequences, accountLaneCount)
                || !length(accountLaneQueueDepths, accountLaneCount)
                || !length(accountLaneQueueCapacities, accountLaneCount)
                || !length(accountLaneQueueHighWaterMarks, accountLaneCount)
                || !length(accountLaneRejectedSubmissions, accountLaneCount)
                || !length(accountLaneOldestPendingSequences, accountLaneCount)
                || !length(accountLaneCompletedOperations, operationValues)
                || !length(accountLaneLatencySamples, operationValues)
                || !length(accountLaneTotalLatencyNanos, operationValues)
                || !length(accountLaneMaxLatencyNanos, operationValues)) {
            throw new IllegalArgumentException("invalid Core Lane metrics view");
        }
        for (int laneId = 0; laneId < accountLaneCount; laneId++) {
            if (accountLaneRevisions[laneId] < 0
                    || accountLaneAppliedSequences[laneId] < accountLaneCommittedSequences[laneId]
                    || accountLaneCommittedSequences[laneId] < 0
                    || accountLaneQueueDepths[laneId] < 0 || accountLaneQueueCapacities[laneId] <= 0
                    || accountLaneQueueDepths[laneId] > accountLaneQueueCapacities[laneId]
                    || accountLaneQueueHighWaterMarks[laneId] < accountLaneQueueDepths[laneId]
                    || accountLaneQueueHighWaterMarks[laneId] > accountLaneQueueCapacities[laneId]
                    || accountLaneRejectedSubmissions[laneId] < 0
                    || accountLaneOldestPendingSequences[laneId] < 0) {
                throw new IllegalArgumentException("invalid Account Lane metrics view");
            }
        }
        accountLaneRevisions = accountLaneRevisions.clone();
        accountLaneAppliedSequences = accountLaneAppliedSequences.clone();
        accountLaneCommittedSequences = accountLaneCommittedSequences.clone();
        accountLaneQueueDepths = accountLaneQueueDepths.clone();
        accountLaneQueueCapacities = accountLaneQueueCapacities.clone();
        accountLaneQueueHighWaterMarks = accountLaneQueueHighWaterMarks.clone();
        accountLaneRejectedSubmissions = accountLaneRejectedSubmissions.clone();
        accountLaneOldestPendingSequences = accountLaneOldestPendingSequences.clone();
        accountLaneCompletedOperations = accountLaneCompletedOperations.clone();
        accountLaneLatencySamples = accountLaneLatencySamples.clone();
        accountLaneTotalLatencyNanos = accountLaneTotalLatencyNanos.clone();
        accountLaneMaxLatencyNanos = accountLaneMaxLatencyNanos.clone();
    }

    private static boolean length(long[] values, int expected) {
        return values != null && values.length == expected;
    }

    private static boolean length(int[] values, int expected) {
        return values != null && values.length == expected;
    }

    @Override public long[] accountLaneRevisions() { return accountLaneRevisions.clone(); }
    @Override public long[] accountLaneAppliedSequences() { return accountLaneAppliedSequences.clone(); }
    @Override public long[] accountLaneCommittedSequences() { return accountLaneCommittedSequences.clone(); }
    @Override public int[] accountLaneQueueDepths() { return accountLaneQueueDepths.clone(); }
    @Override public int[] accountLaneQueueCapacities() { return accountLaneQueueCapacities.clone(); }
    @Override public int[] accountLaneQueueHighWaterMarks() { return accountLaneQueueHighWaterMarks.clone(); }
    @Override public long[] accountLaneRejectedSubmissions() { return accountLaneRejectedSubmissions.clone(); }
    @Override public long[] accountLaneOldestPendingSequences() { return accountLaneOldestPendingSequences.clone(); }
    @Override public long[] accountLaneCompletedOperations() { return accountLaneCompletedOperations.clone(); }
    @Override public long[] accountLaneLatencySamples() { return accountLaneLatencySamples.clone(); }
    @Override public long[] accountLaneTotalLatencyNanos() { return accountLaneTotalLatencyNanos.clone(); }
    @Override public long[] accountLaneMaxLatencyNanos() { return accountLaneMaxLatencyNanos.clone(); }
}
