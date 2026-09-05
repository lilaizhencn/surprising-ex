package com.surprising.adl.api.model;

import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;

public record AdlEventResponse(
        long eventId,
        long deficitUserId,
        long targetUserId,
        String asset,
        String symbol,
        AdlSide targetSide,
        PositionSide targetPositionSide,
        long closedQuantitySteps,
        long entryPriceTicks,
        long markPriceTicks,
        long requestedDeficitUnits,
        long realizedProfitUnits,
        long coveredUnits,
        long remainingDeficitUnits,
        long priorityScorePpm,
        String reason,
        Instant createdAt) {

    public AdlEventResponse {
        targetPositionSide = PositionSide.defaultIfNull(targetPositionSide);
    }

    public AdlEventResponse(long eventId,
                            long deficitUserId,
                            long targetUserId,
                            String asset,
                            String symbol,
                            AdlSide targetSide,
                            long closedQuantitySteps,
                            long entryPriceTicks,
                            long markPriceTicks,
                            long requestedDeficitUnits,
                            long realizedProfitUnits,
                            long coveredUnits,
                            long remainingDeficitUnits,
                            long priorityScorePpm,
                            String reason,
                            Instant createdAt) {
        this(eventId, deficitUserId, targetUserId, asset, symbol, targetSide, PositionSide.NET,
                closedQuantitySteps, entryPriceTicks, markPriceTicks, requestedDeficitUnits, realizedProfitUnits,
                coveredUnits, remainingDeficitUnits, priorityScorePpm, reason, createdAt);
    }
}
