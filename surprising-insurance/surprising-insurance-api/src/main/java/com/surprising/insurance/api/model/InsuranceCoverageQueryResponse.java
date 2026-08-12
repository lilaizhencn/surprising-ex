package com.surprising.insurance.api.model;

import java.util.List;

public record InsuranceCoverageQueryResponse(
        int count,
        List<InsuranceCoverageResponse> coverages,
        String nextCursor,
        boolean hasMore,
        String sort,
        int limit) {

    public InsuranceCoverageQueryResponse(int count, List<InsuranceCoverageResponse> coverages) {
        this(count, coverages, null, false, "createdAt.desc", count);
    }
}
