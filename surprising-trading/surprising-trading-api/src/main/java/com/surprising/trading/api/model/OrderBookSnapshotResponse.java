package com.surprising.trading.api.model;

import java.time.Instant;
import java.util.List;

public record OrderBookSnapshotResponse(
        String symbol,
        long sequence,
        int depth,
        List<OrderBookLevel> bids,
        List<OrderBookLevel> asks,
        Instant eventTime) {
}
