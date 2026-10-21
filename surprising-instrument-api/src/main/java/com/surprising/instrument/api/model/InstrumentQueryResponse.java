package com.surprising.instrument.api.model;

import java.util.List;

public record InstrumentQueryResponse(
        int count,
        List<InstrumentResponse> instruments,
        String nextCursor,
        boolean hasMore,
        String sort,
        int limit) {

    public InstrumentQueryResponse(int count, List<InstrumentResponse> instruments) {
        this(count, instruments, null, false, null, count);
    }
}
