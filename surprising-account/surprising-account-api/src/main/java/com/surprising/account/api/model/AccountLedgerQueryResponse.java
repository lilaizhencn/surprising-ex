package com.surprising.account.api.model;

import java.util.List;

public record AccountLedgerQueryResponse(
        int count,
        List<AccountLedgerEntryResponse> entries,
        String nextCursor,
        boolean hasMore,
        String sort,
        int limit) {
    public AccountLedgerQueryResponse(int count, List<AccountLedgerEntryResponse> entries) {
        this(count, entries, null, false, "createdAt.desc", count);
    }
}
