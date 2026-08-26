package com.surprising.aeron.service;

public record CoreLaneMetrics(
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

    public CoreLaneMetrics {
        if (matchingEngineCount <= 0 || accountLaneCount <= 0 || matcherDispatchDepth < 0
                || matcherDispatchCapacity <= 0 || matcherDispatchHighWaterMark < matcherDispatchDepth
                || matchingCompletionDepth < 0 || matchingCompletionCapacity <= 0
                || matchingCompletionHighWaterMark < matchingCompletionDepth || commandContextDepth < 0
                || commandContextCapacity <= 0 || commandContextHighWaterMark < commandContextDepth
                || committedCoreSequence < 0 || accountLaneRevisions == null
                || accountLaneAppliedSequences == null || accountLaneCommittedSequences == null
                || accountLaneQueueDepths == null || accountLaneQueueCapacities == null
                || accountLaneQueueHighWaterMarks == null || accountLaneRejectedSubmissions == null
                || accountLaneOldestPendingSequences == null || accountLaneCompletedOperations == null
                || accountLaneLatencySamples == null
                || accountLaneTotalLatencyNanos == null || accountLaneMaxLatencyNanos == null
                || accountLaneRevisions.length != accountLaneCount
                || accountLaneAppliedSequences.length != accountLaneCount
                || accountLaneCommittedSequences.length != accountLaneCount
                || accountLaneQueueDepths.length != accountLaneCount
                || accountLaneQueueCapacities.length != accountLaneCount
                || accountLaneQueueHighWaterMarks.length != accountLaneCount
                || accountLaneRejectedSubmissions.length != accountLaneCount
                || accountLaneOldestPendingSequences.length != accountLaneCount
                || accountLaneCompletedOperations.length != accountLaneCount * OPERATION_TYPE_COUNT
                || accountLaneLatencySamples.length != accountLaneCompletedOperations.length
                || accountLaneTotalLatencyNanos.length != accountLaneCompletedOperations.length
                || accountLaneMaxLatencyNanos.length != accountLaneCompletedOperations.length) {
            throw new IllegalArgumentException("invalid Core lane metrics");
        }
        for (int laneId = 0; laneId < accountLaneCount; laneId++) {
            if (accountLaneQueueDepths[laneId] < 0 || accountLaneQueueCapacities[laneId] <= 0
                    || accountLaneQueueDepths[laneId] > accountLaneQueueCapacities[laneId]
                    || accountLaneQueueHighWaterMarks[laneId] < accountLaneQueueDepths[laneId]
                    || accountLaneQueueHighWaterMarks[laneId] > accountLaneQueueCapacities[laneId]
                    || accountLaneRejectedSubmissions[laneId] < 0
                    || accountLaneOldestPendingSequences[laneId] < 0) {
                throw new IllegalArgumentException("invalid Account Lane queue metrics");
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

    @Override
    public long[] accountLaneRevisions() { return accountLaneRevisions.clone(); }

    @Override
    public long[] accountLaneAppliedSequences() { return accountLaneAppliedSequences.clone(); }

    @Override
    public long[] accountLaneCommittedSequences() { return accountLaneCommittedSequences.clone(); }

    @Override
    public int[] accountLaneQueueDepths() { return accountLaneQueueDepths.clone(); }

    @Override
    public int[] accountLaneQueueCapacities() { return accountLaneQueueCapacities.clone(); }

    @Override
    public int[] accountLaneQueueHighWaterMarks() { return accountLaneQueueHighWaterMarks.clone(); }

    @Override
    public long[] accountLaneRejectedSubmissions() { return accountLaneRejectedSubmissions.clone(); }

    @Override
    public long[] accountLaneOldestPendingSequences() { return accountLaneOldestPendingSequences.clone(); }

    @Override
    public long[] accountLaneCompletedOperations() { return accountLaneCompletedOperations.clone(); }

    @Override
    public long[] accountLaneLatencySamples() { return accountLaneLatencySamples.clone(); }

    @Override
    public long[] accountLaneTotalLatencyNanos() { return accountLaneTotalLatencyNanos.clone(); }

    @Override
    public long[] accountLaneMaxLatencyNanos() { return accountLaneMaxLatencyNanos.clone(); }
}
