package com.surprising.gateway.provider.auth.model;

import java.util.List;

public record LoginLogQueryResponse(
        int count,
        List<LoginLogResponse> logs,
        String nextCursor,
        boolean hasMore,
        String sort,
        int limit) {
    public LoginLogQueryResponse(int count, List<LoginLogResponse> logs) {
        this(count, logs, null, false, "createdAt.desc", count);
    }
}
