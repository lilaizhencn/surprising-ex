package com.surprising.gateway.provider.auth.model;

import java.util.List;

public record AdminOperationLogQueryResponse(
        int count,
        List<AdminOperationLogResponse> logs,
        String nextCursor,
        boolean hasMore,
        String sort,
        int limit) {
    public AdminOperationLogQueryResponse(int count, List<AdminOperationLogResponse> logs) {
        this(count, logs, null, false, "createdAt.desc", count);
    }
}
