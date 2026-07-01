package com.surprising.trading.api.model;

import java.util.List;

public record TriggerOrderQueryResponse(
        int count,
        List<TriggerOrderResponse> orders) {
}
