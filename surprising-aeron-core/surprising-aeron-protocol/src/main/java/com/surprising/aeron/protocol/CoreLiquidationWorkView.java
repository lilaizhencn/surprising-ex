package com.surprising.aeron.protocol;

import java.util.List;

public record CoreLiquidationWorkView(boolean riskScanPending, List<CoreLiquidationActionView> actions) {
    public CoreLiquidationWorkView {
        if (actions == null) throw new IllegalArgumentException("liquidation actions are required");
        actions = List.copyOf(actions);
    }
}
