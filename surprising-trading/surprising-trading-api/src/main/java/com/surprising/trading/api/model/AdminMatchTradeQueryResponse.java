package com.surprising.trading.api.model;

import java.util.List;

public record AdminMatchTradeQueryResponse(
        int count,
        List<AdminMatchTradeResponse> trades,
        String nextCursor,
        boolean hasMore,
        String sort,
        int limit) {
    public AdminMatchTradeQueryResponse(int count, List<AdminMatchTradeResponse> trades) {
        this(count, trades, null, false, "eventTime.desc", count);
    }
}
