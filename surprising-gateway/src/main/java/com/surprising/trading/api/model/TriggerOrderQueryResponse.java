package com.surprising.trading.api.model;

import java.util.List;

public record TriggerOrderQueryResponse(
        int count,
        List<TriggerOrderResponse> orders,
        String nextCursor,
        boolean hasMore,
        String sort,
        int limit) {
    public TriggerOrderQueryResponse(int count, List<TriggerOrderResponse> orders) {
        this(count, orders, null, false, "createdAt.desc", count);
    }
}
