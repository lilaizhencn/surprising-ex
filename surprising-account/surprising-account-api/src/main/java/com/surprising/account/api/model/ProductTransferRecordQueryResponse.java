package com.surprising.account.api.model;

import java.util.List;

public record ProductTransferRecordQueryResponse(
        int count,
        List<ProductTransferRecordResponse> transfers,
        String nextCursor,
        boolean hasMore,
        String sort,
        int limit) {
    public ProductTransferRecordQueryResponse(int count, List<ProductTransferRecordResponse> transfers) {
        this(count, transfers, null, false, "createdAt.desc", count);
    }
}
