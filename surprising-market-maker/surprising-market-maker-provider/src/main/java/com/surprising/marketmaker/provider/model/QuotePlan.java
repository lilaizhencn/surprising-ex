package com.surprising.marketmaker.provider.model;

import java.util.List;

public record QuotePlan(
        long anchorPriceTicks,
        long signedPositionSteps,
        List<DesiredQuote> quotes) {
}
