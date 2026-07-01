package com.surprising.risk.api.model;

import java.util.List;

public record LiquidationCandidateQueryResponse(
        int count,
        List<LiquidationCandidateResponse> candidates) {
}
