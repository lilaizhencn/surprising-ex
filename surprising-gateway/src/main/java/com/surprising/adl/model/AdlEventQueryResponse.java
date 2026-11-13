package com.surprising.adl.api.model;

import java.util.List;

public record AdlEventQueryResponse(
        int count,
        List<AdlEventResponse> events,
        String nextCursor,
        boolean hasMore,
        String sort,
        int limit) {

    public AdlEventQueryResponse(int count, List<AdlEventResponse> events) {
        this(count, events, null, false, "createdAt.desc", count);
    }
}
