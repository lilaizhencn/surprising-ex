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
        long[] accountLaneCommittedSequences) {

    public CoreLaneMetrics {
        if (matchingEngineCount <= 0 || accountLaneCount <= 0 || matcherDispatchDepth < 0
                || matcherDispatchCapacity <= 0 || matcherDispatchHighWaterMark < matcherDispatchDepth
                || matchingCompletionDepth < 0 || matchingCompletionCapacity <= 0
                || matchingCompletionHighWaterMark < matchingCompletionDepth || commandContextDepth < 0
                || commandContextCapacity <= 0 || commandContextHighWaterMark < commandContextDepth
                || committedCoreSequence < 0 || accountLaneRevisions == null
                || accountLaneAppliedSequences == null || accountLaneCommittedSequences == null
                || accountLaneRevisions.length != accountLaneCount
                || accountLaneAppliedSequences.length != accountLaneCount
                || accountLaneCommittedSequences.length != accountLaneCount) {
            throw new IllegalArgumentException("invalid Core lane metrics");
        }
        accountLaneRevisions = accountLaneRevisions.clone();
        accountLaneAppliedSequences = accountLaneAppliedSequences.clone();
        accountLaneCommittedSequences = accountLaneCommittedSequences.clone();
    }

    @Override
    public long[] accountLaneRevisions() { return accountLaneRevisions.clone(); }

    @Override
    public long[] accountLaneAppliedSequences() { return accountLaneAppliedSequences.clone(); }

    @Override
    public long[] accountLaneCommittedSequences() { return accountLaneCommittedSequences.clone(); }
}
