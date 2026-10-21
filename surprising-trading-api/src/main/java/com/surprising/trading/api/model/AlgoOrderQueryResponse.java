package com.surprising.trading.api.model;

import java.util.List;

public record AlgoOrderQueryResponse(
        int count,
        List<AlgoOrderResponse> orders) {
}
