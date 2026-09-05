package com.surprising.aeron.service.state;

import java.util.List;

public record AccountLaneSnapshot(
        int laneId,
        long revision,
        long appliedSequence,
        long committedSequence,
        long localStateHash,
        long localFundsHash,
        List<Long> userIds) {

    public AccountLaneSnapshot {
        if (laneId < 0 || laneId >= Long.SIZE || revision < 0 || appliedSequence < committedSequence
                || committedSequence < 0 || localStateHash == 0 || localFundsHash == 0 || userIds == null
                || userIds.stream().anyMatch(userId -> userId == null || userId <= 0)) {
            throw new IllegalArgumentException("invalid account lane snapshot");
        }
        userIds = userIds.stream().sorted().toList();
        if (userIds.stream().distinct().count() != userIds.size()) {
            throw new IllegalArgumentException("duplicate account lane user");
        }
    }
}
