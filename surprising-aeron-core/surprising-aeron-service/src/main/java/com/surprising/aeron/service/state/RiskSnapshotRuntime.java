package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CorePositionSide;

public record RiskSnapshotRuntime(long userId, int symbolId, CorePositionSide positionSide,
                                  long priceSequence, long equityUnits, long unrealizedPnlUnits,
                                  long maintenanceMarginUnits, long marginRatioPpm, CoreRiskStatus status) {
    public RiskSnapshotRuntime {
        if (userId <= 0 || symbolId < 0 || positionSide == null || priceSequence <= 0
                || maintenanceMarginUnits < 0 || marginRatioPpm < 0 || status == null) {
            throw new IllegalArgumentException("invalid runtime risk snapshot");
        }
    }
}
