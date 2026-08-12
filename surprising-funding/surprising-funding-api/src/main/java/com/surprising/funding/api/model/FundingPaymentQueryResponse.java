package com.surprising.funding.api.model;

import java.util.List;

public record FundingPaymentQueryResponse(
        int count,
        List<FundingPaymentResponse> payments,
        String nextCursor,
        boolean hasMore,
        String sort,
        int limit) {

    public FundingPaymentQueryResponse(int count, List<FundingPaymentResponse> payments) {
        this(count, payments, null, false, "createdAt.desc", count);
    }
}
