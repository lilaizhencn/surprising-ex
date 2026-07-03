package com.surprising.trading.api.model;

import java.util.List;

public record AdminOrderEventQueryResponse(
        int count,
        List<AdminOrderEventResponse> events) {
}
