package com.surprising.trading.api.model;

import java.util.List;

public record OrderQueryResponse(
        int count,
        List<OrderResponse> orders) {
}
