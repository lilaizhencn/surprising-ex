package com.surprising.gateway.provider.auth.model;

import java.util.List;

public record CursorPage<T>(
        List<T> items,
        String nextCursor,
        boolean hasMore,
        String sort,
        int limit) {
}
