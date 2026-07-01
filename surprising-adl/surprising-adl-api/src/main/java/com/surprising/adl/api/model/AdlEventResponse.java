package com.surprising.adl.api.model;

import java.time.Instant;

public record AdlEventResponse(
        long eventId,
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
}
