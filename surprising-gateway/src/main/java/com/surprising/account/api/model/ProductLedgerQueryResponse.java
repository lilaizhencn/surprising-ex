package com.surprising.account.api.model;

import java.util.List;

public record ProductLedgerQueryResponse(
        int count,
        List<ProductLedgerEntryResponse> entries,
        String nextCursor,
        boolean hasMore,
        String sort,
        int limit) {
    public ProductLedgerQueryResponse(int count, List<ProductLedgerEntryResponse> entries) {
        this(count, entries, null, false, "createdAt.desc", count);
    }
}
