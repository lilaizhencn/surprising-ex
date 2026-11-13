package com.surprising.account.api.model;

import java.util.List;

public record PositionQueryResponse(
        int count,
        List<PositionResponse> positions) {
}
