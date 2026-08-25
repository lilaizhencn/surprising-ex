package com.surprising.aeron.service;

record AccountLaneAck(
        long coreSequence,
        int laneId,
        long laneRevision,
        long localStateHash,
        long localFundsHash,
        long feeUnits,
        long insuranceUnits,
        long deficitUnits,
        long fundingResidualUnits,
        long roundingResidualUnits,
        long clearingUnits) {

    AccountLaneAck {
        if (coreSequence <= 0 || laneId < 0 || laneId >= Long.SIZE || laneRevision < 0
                || localStateHash == 0 || localFundsHash == 0) {
            throw new IllegalArgumentException("invalid account lane ACK");
        }
    }
}
