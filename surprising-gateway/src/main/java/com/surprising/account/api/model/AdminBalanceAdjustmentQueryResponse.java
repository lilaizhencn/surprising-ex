package com.surprising.account.api.model;

import java.util.List;

public record AdminBalanceAdjustmentQueryResponse(
        int count,
        List<AdminBalanceAdjustmentRecord> adjustments,
        String nextCursor,
        boolean hasMore,
        String sort,
        int limit) {
    public AdminBalanceAdjustmentQueryResponse(int count, List<AdminBalanceAdjustmentRecord> adjustments) {
        this(count, adjustments, null, false, "createdAt.desc", count);
    }
}
