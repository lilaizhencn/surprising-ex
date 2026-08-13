package com.surprising.trading.api.model;

import java.util.List;

public record UserMatchTradeQueryResponse(
        List<UserMatchTradeResponse> trades,
        String nextCursor,
        boolean hasMore,
        String sort,
        int limit) {
}
