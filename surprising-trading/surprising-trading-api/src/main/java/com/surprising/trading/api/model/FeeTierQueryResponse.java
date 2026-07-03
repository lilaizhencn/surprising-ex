package com.surprising.trading.api.model;

import java.util.List;

public record FeeTierQueryResponse(
        int count,
        List<FeeTierResponse> tiers,
        String nextCursor,
        boolean hasMore,
        String sort,
        int limit) {

    public FeeTierQueryResponse(int count, List<FeeTierResponse> tiers) {
        this(count, tiers, null, false, null, count);
    }
}
