package com.surprising.funding.api.model;

import java.util.List;

public record FundingRateQueryResponse(
        int count,
        List<FundingRateResponse> rates) {
}
