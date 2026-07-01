package com.surprising.trading.api.model;

import java.util.List;

public record FeeTierQueryResponse(
        int count,
        List<FeeTierResponse> tiers) {
}
