package com.surprising.trading.api.model;

import java.util.List;

public record OrderQueryResponse(
        int count,
        List<OrderResponse> orders,
        String nextCursor,
        boolean hasMore,
        String sort,
        int limit) {
    public OrderQueryResponse(int count, List<OrderResponse> orders) {
        this(count, orders, null, false, "createdAt.desc", count);
    }
}
