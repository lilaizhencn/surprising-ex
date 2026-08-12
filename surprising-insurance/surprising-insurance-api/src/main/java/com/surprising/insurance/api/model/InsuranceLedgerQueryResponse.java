package com.surprising.insurance.api.model;

import java.util.List;

public record InsuranceLedgerQueryResponse(
        int count,
        List<InsuranceFundLedgerResponse> entries,
        String nextCursor,
        boolean hasMore,
        String sort,
        int limit) {

    public InsuranceLedgerQueryResponse(int count, List<InsuranceFundLedgerResponse> entries) {
        this(count, entries, null, false, "createdAt.desc", count);
    }
}
