package com.surprising.aeron.protocol;

import java.util.List;

public record CoreLiquidationWorkView(CoreRiskScanContinuation riskScanContinuation,
                                      List<CoreLiquidationActionView> actions) {
    public CoreLiquidationWorkView {
        if (actions == null) throw new IllegalArgumentException("liquidation actions are required");
        actions = List.copyOf(actions);
    }

    public boolean riskScanPending() {
        return riskScanContinuation != null;
    }
}
