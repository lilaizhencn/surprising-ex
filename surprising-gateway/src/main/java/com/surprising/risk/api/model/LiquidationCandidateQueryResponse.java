package com.surprising.risk.api.model;

import java.util.List;

public record LiquidationCandidateQueryResponse(
        int count,
        List<LiquidationCandidateResponse> candidates,
        String nextCursor,
        boolean hasMore,
        String sort,
        int limit) {

    public LiquidationCandidateQueryResponse(int count, List<LiquidationCandidateResponse> candidates) {
        this(count, candidates, null, false, "eventTime.asc", count);
    }
}
