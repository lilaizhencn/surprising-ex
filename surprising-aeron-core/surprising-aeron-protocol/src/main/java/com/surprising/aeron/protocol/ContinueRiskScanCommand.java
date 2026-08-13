package com.surprising.aeron.protocol;

public record ContinueRiskScanCommand(int maxUsers) {
    public ContinueRiskScanCommand {
        if (maxUsers <= 0 || maxUsers > 4096) {
            throw new IllegalArgumentException("invalid risk scan batch size");
        }
    }
}
