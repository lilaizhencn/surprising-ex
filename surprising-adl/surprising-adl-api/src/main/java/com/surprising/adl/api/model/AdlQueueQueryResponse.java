package com.surprising.adl.api.model;

import java.util.List;

public record AdlQueueQueryResponse(
        int count,
        List<AdlQueuePositionResponse> positions) {
}
