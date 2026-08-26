package com.surprising.aeron.service;

import com.surprising.aeron.service.matching.CoreMatchingResult;
import com.surprising.aeron.service.state.RuntimeTreasuryDelta;

public record AccountLaneAck(
        long coreSequence,
        int laneId,
        long laneRevision,
        long localStateHash,
        long localFundsHash,
        CoreMatchingResult matchingResult,
        RuntimeTreasuryDelta treasuryDelta) {

    public AccountLaneAck {
        if (coreSequence <= 0 || laneId < 0 || laneId >= Long.SIZE || laneRevision < 0
                || localStateHash == 0 || localFundsHash == 0
                || matchingResult == null || treasuryDelta == null) {
            throw new IllegalArgumentException("invalid account lane ACK");
        }
    }
}
