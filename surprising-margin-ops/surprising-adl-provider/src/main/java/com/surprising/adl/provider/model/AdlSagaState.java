package com.surprising.adl.provider.model;

import com.surprising.account.api.model.AccountCommandStatus;

public record AdlSagaState(
        long executionId,
        String productLine,
        String accountType,
        long deficitUserId,
        long targetUserId,
        String asset,
        String symbol,
        String targetSide,
        String targetPositionSide,
        long closedQuantitySteps,
        long entryPriceTicks,
        long markPriceTicks,
        long requestedDeficitUnits,
        long realizedProfitUnits,
        long coveredUnits,
        long priorityScorePpm,
        String reserveCommandId,
        String targetCommandId,
        String finalizeCommandId,
        String releaseCommandId,
        String sagaStatus,
        String reserveStatus,
        String targetStatus,
        String finalizeStatus,
        String releaseStatus,
        String finalizeResult,
        String terminalErrorCode,
        String terminalErrorMessage) {

    public boolean reserveRejected() {
        return AccountCommandStatus.REJECTED.name().equals(reserveStatus);
    }

    public boolean targetRejectedAfterReservation() {
        return AccountCommandStatus.APPLIED.name().equals(reserveStatus)
                && AccountCommandStatus.REJECTED.name().equals(targetStatus);
    }

    public boolean finalizeApplied() {
        return AccountCommandStatus.APPLIED.name().equals(finalizeStatus);
    }

    public boolean releaseApplied() {
        return AccountCommandStatus.APPLIED.name().equals(releaseStatus);
    }
}
