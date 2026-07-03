package com.surprising.funding.api.model;

import java.util.List;

public record FundingRateQueryResponse(
        int count,
        List<FundingRateResponse> rates,
        String nextCursor,
        boolean hasMore,
        String sort,
        int limit) {

    public FundingRateQueryResponse(int count, List<FundingRateResponse> rates) {
        this(count, rates, null, false, "eventTime.desc", count);
    }
}
