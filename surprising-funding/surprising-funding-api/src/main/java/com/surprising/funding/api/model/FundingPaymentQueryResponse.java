package com.surprising.funding.api.model;

import java.util.List;

public record FundingPaymentQueryResponse(
        int count,
        List<FundingPaymentResponse> payments) {
}
