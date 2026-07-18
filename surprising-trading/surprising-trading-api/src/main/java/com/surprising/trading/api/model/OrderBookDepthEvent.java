package com.surprising.trading.api.model;

import java.time.Instant;
import java.util.List;

/**
 * L2 order-book update for WebSocket fanout.
 *
 * <p>The matching market-data publisher emits full snapshots. Consumers replace the complete
 * local book for {@code symbol}; intermediate snapshots may be coalesced per symbol.</p>
 */
public record OrderBookDepthEvent(
        String symbol,
        long sequence,
        long previousSequence,
        OrderBookDepthUpdateType updateType,
        int depth,
        List<OrderBookLevel> bids,
        List<OrderBookLevel> asks,
        Instant eventTime) {
}
