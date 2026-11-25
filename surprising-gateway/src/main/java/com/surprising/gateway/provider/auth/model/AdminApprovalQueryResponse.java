package com.surprising.gateway.provider.auth.model;

import java.util.List;

public record AdminApprovalQueryResponse(
        int count,
        List<AdminApprovalResponse> approvals,
        String nextCursor,
        boolean hasMore,
        String sort,
        int limit) {
    public AdminApprovalQueryResponse(int count, List<AdminApprovalResponse> approvals) {
        this(count, approvals, null, false, "requestedAt.desc", count);
    }
}
