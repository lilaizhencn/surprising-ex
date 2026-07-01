package com.surprising.trading.api.model;

import java.time.Instant;
import java.util.List;

/**
 * L2 order-book update for WebSocket fanout.
 *
 * <p>DELTA levels are absolute price-level states, not quantity differences. A level with
 * quantitySteps=0 removes the price from the local book.</p>
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
