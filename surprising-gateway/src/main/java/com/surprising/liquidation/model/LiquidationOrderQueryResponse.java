package com.surprising.liquidation.api.model;

import java.util.List;

public record LiquidationOrderQueryResponse(
        int count,
        List<LiquidationOrderResponse> orders,
        String nextCursor,
        boolean hasMore,
        String sort,
        int limit) {

    public LiquidationOrderQueryResponse(int count, List<LiquidationOrderResponse> orders) {
        this(count, orders, null, false, "createdAt.desc", count);
    }
}
