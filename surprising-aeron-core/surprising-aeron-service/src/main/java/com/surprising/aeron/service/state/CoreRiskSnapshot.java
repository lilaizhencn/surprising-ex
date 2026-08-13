package com.surprising.aeron.service.state;

public record CoreRiskSnapshot(
        long userId,
        String symbol,
        long priceSequence,
        long equityUnits,
        long unrealizedPnlUnits,
        long maintenanceMarginUnits,
        long marginRatioPpm,
        CoreRiskStatus status) {
    public CoreRiskSnapshot {
        symbol = OrderReservation.normalizeSymbol(symbol);
        if (userId <= 0 || priceSequence <= 0 || maintenanceMarginUnits < 0
                || marginRatioPpm < 0 || status == null) {
            throw new IllegalArgumentException("invalid risk snapshot");
        }
    }

    public String key() {
        return userId + ":" + symbol;
    }
}
