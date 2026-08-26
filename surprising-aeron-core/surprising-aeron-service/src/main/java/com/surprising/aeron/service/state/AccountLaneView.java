package com.surprising.aeron.service.state;

public record AccountLaneView(
        int laneId,
        long revision,
        long appliedSequence,
        long committedSequence,
        long localStateHash,
        long localFundsHash,
        int userCount,
        int queueDepth,
        int queueCapacity,
        int queueHighWaterMark,
        String ownerThreadName) {

    public AccountLaneView {
        if (laneId < 0 || revision < 0 || appliedSequence < committedSequence || committedSequence < 0
                || localStateHash == 0 || localFundsHash == 0 || userCount < 0 || queueDepth < 0
                || queueCapacity <= 0 || queueDepth > queueCapacity || queueHighWaterMark < queueDepth
                || queueHighWaterMark > queueCapacity || ownerThreadName == null || ownerThreadName.isBlank()) {
            throw new IllegalArgumentException("invalid account lane view");
        }
    }
}
