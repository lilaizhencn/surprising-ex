package com.surprising.funding.provider.model;

import java.util.List;

public record FundingPaymentPage(
        List<FundingPaymentCandidate> items,
        FundingPaymentCursor nextCursor,
        boolean hasMore) {

    public FundingPaymentPage {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static FundingPaymentPage empty(FundingPaymentCursor cursor) {
        return new FundingPaymentPage(List.of(), cursor, false);
    }
}
