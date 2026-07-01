package com.surprising.account.api.model;

import java.time.Instant;

public record PositionResponse(
        long userId,
        String symbol,
        long instrumentVersion,
        long signedQuantitySteps,
        long entryPriceTicks,
        long realizedPnlUnits,
        Instant updatedAt) {
}
