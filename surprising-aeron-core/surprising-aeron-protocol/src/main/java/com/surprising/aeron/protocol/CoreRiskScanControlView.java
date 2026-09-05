package com.surprising.aeron.protocol;

public record CoreRiskScanControlView(
        long version,
        String ruleName,
        boolean enabled,
        long scanDelayMs,
        int scanBatchSize,
        String updatedBy,
        String reason,
        long updatedAtEpochMillis) {

    public CoreRiskScanControlView {
        if (version <= 0 || ruleName == null || ruleName.isBlank() || ruleName.length() > 128
                || scanDelayMs < 0 || scanBatchSize < 1
                || scanBatchSize > ExecuteLiquidationBatchCommand.MAX_RISK_SCAN_USERS
                || updatedBy == null || updatedBy.isBlank() || updatedBy.length() > 128
                || reason == null || reason.isBlank() || reason.length() > 500
                || updatedAtEpochMillis < 0) {
            throw new IllegalArgumentException("invalid risk scan control view");
        }
        ruleName = ruleName.trim();
        updatedBy = updatedBy.trim();
        reason = reason.trim();
    }
}
