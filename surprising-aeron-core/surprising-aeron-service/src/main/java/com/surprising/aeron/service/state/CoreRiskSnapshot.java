package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CorePositionSide;

public record CoreRiskSnapshot(
        long userId,
        String symbol,
        CorePositionSide positionSide,
        long priceSequence,
        long equityUnits,
        long unrealizedPnlUnits,
        long maintenanceMarginUnits,
        long marginRatioPpm,
        CoreRiskStatus status) {
    public CoreRiskSnapshot {
        symbol = OrderReservation.normalizeSymbol(symbol);
        if (userId <= 0 || positionSide == null || priceSequence <= 0 || maintenanceMarginUnits < 0
                || marginRatioPpm < 0 || status == null) {
            throw new IllegalArgumentException("invalid risk snapshot");
        }
    }

    public String key() {
        return positionSide == CorePositionSide.NET
                ? userId + ":" + symbol
                : userId + ":" + symbol + ":" + positionSide.name();
    }

    public CoreRiskSnapshot(long userId, String symbol, long priceSequence, long equityUnits,
                            long unrealizedPnlUnits, long maintenanceMarginUnits, long marginRatioPpm,
                            CoreRiskStatus status) {
        this(userId, symbol, CorePositionSide.NET, priceSequence, equityUnits, unrealizedPnlUnits,
                maintenanceMarginUnits, marginRatioPpm, status);
    }
}
