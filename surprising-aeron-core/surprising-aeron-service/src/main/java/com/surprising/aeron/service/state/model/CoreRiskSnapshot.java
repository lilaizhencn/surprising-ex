package com.surprising.aeron.service.state.model;

import com.surprising.aeron.service.state.OrderReservation;

import com.surprising.aeron.protocol.CorePositionSide;

public record CoreRiskSnapshot(
        long userId,
        String instrumentId,
        CorePositionSide positionSide,
        long priceSequence,
        long equityUnits,
        long unrealizedPnlUnits,
        long maintenanceMarginUnits,
        long marginRatioPpm,
        CoreRiskStatus status) {
    public CoreRiskSnapshot {
        instrumentId = OrderReservation.requireInstrumentId(instrumentId);
        if (userId <= 0 || positionSide == null || priceSequence <= 0 || maintenanceMarginUnits < 0
                || marginRatioPpm < 0 || status == null) {
            throw new IllegalArgumentException("invalid risk snapshot");
        }
    }

    public String key() {
        return positionSide == CorePositionSide.NET
                ? userId + ":" + instrumentId
                : userId + ":" + instrumentId + ":" + positionSide.name();
    }

    public CoreRiskSnapshot(long userId, String instrumentId, long priceSequence, long equityUnits,
                            long unrealizedPnlUnits, long maintenanceMarginUnits, long marginRatioPpm,
                            CoreRiskStatus status) {
        this(userId, instrumentId, CorePositionSide.NET, priceSequence, equityUnits, unrealizedPnlUnits,
                maintenanceMarginUnits, marginRatioPpm, status);
    }
}
