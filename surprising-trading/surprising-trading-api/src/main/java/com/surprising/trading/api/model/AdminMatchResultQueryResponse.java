package com.surprising.trading.api.model;

import java.util.List;

public record AdminMatchResultQueryResponse(
        int count,
        List<AdminMatchResultResponse> results) {
}
