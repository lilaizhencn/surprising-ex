package com.surprising.trading.api.model;

import java.util.List;

public record FeeScheduleQueryResponse(
        int count,
        List<FeeScheduleResponse> schedules,
        String nextCursor,
        boolean hasMore,
        String sort,
        int limit) {

    public FeeScheduleQueryResponse(int count, List<FeeScheduleResponse> schedules) {
        this(count, schedules, null, false, null, count);
    }
}
