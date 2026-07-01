package com.surprising.trading.api.model;

import java.util.List;

public record FeeScheduleQueryResponse(
        int count,
        List<FeeScheduleResponse> schedules) {
}
