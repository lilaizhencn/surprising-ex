package com.surprising.gateway.provider.auth.model;

import java.util.List;

public record AdminSessionQueryResponse(
        int count,
        List<AdminSessionResponse> sessions,
        String nextCursor,
        boolean hasMore,
        String sort,
        int limit) {

    public AdminSessionQueryResponse(int count, List<AdminSessionResponse> sessions) {
        this(count, sessions, null, false, null, count);
    }
}
