package com.surprising.insurance.provider.model;

public record InsurancePendingCoverage(
        long coverageId,
        String accountType,
        long userId,
        String asset,
        long coveredUnits,
        String reserveCommandId,
        String finalizeCommandId,
        String coverageStatus,
        String reserveStatus,
        String finalizeStatus,
        String finalizeResult,
        String errorCode,
        String errorMessage) {

    public boolean reserveApplied() {
        return "APPLIED".equals(reserveStatus);
    }

    public boolean reserveRejected() {
        return "REJECTED".equals(reserveStatus);
    }

    public boolean finalizeApplied() {
        return "APPLIED".equals(finalizeStatus);
    }
}
