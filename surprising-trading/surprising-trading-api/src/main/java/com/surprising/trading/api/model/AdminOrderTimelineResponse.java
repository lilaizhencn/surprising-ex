package com.surprising.trading.api.model;

import java.util.List;

public record AdminOrderTimelineResponse(
        OrderResponse order,
        List<AdminOrderEventResponse> events,
        List<AdminMatchResultResponse> matchResults,
        List<AdminMatchTradeResponse> trades) {
}
