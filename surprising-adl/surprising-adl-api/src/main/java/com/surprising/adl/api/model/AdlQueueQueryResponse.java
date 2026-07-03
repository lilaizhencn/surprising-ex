package com.surprising.adl.api.model;

import java.util.List;

public record AdlQueueQueryResponse(
        int count,
        List<AdlQueuePositionResponse> positions,
        String nextCursor,
        boolean hasMore,
        String sort,
        int limit) {

    public AdlQueueQueryResponse(int count, List<AdlQueuePositionResponse> positions) {
        this(count, positions, null, false, "priorityScorePpm.desc", count);
    }
}
