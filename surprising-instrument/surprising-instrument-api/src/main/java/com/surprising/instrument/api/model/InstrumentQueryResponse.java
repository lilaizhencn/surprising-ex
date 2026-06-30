package com.surprising.instrument.api.model;

import java.util.List;

public record InstrumentQueryResponse(
        int count,
        List<InstrumentResponse> instruments) {
}
