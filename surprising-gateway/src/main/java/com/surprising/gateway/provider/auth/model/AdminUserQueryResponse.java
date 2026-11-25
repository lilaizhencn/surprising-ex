package com.surprising.gateway.provider.auth.model;

import java.util.List;

public record AdminUserQueryResponse(
        int count,
        List<AuthenticatedUser> users,
        String nextCursor,
        boolean hasMore,
        String sort,
        int limit) {

    public AdminUserQueryResponse(int count, List<AuthenticatedUser> users) {
        this(count, users, null, false, null, count);
    }
}
