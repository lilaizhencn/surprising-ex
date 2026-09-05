package com.surprising.aeron.protocol;

public record UpdateRiskScanControlCommand(
        long expectedVersion,
        String ruleName,
        boolean enabled,
        long scanDelayMs,
        int scanBatchSize,
        String adminUserId,
        String reason) {

    public UpdateRiskScanControlCommand {
        if (expectedVersion <= 0 || ruleName == null || ruleName.isBlank() || ruleName.length() > 128
                || scanDelayMs < 0 || scanBatchSize < 1
                || scanBatchSize > ExecuteLiquidationBatchCommand.MAX_RISK_SCAN_USERS
                || adminUserId == null || adminUserId.isBlank() || adminUserId.length() > 128
                || reason == null || reason.isBlank() || reason.length() > 500) {
            throw new IllegalArgumentException("invalid risk scan control update");
        }
        ruleName = ruleName.trim();
        adminUserId = adminUserId.trim();
        reason = reason.trim();
    }
}
