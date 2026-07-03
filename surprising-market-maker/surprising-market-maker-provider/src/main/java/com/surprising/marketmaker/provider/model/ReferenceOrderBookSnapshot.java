package com.surprising.marketmaker.provider.model;

import java.time.Instant;
import java.util.List;

public record ReferenceOrderBookSnapshot(
        String source,
        String symbol,
        List<ReferenceOrderBookLevel> bids,
        List<ReferenceOrderBookLevel> asks,
        Instant receivedAt) {

    public ReferenceOrderBookSnapshot {
        bids = bids == null ? List.of() : List.copyOf(bids);
        asks = asks == null ? List.of() : List.copyOf(asks);
    }

    public boolean hasTwoSidedDepth() {
        return bestBidTicks() > 0 && bestAskTicks() > bestBidTicks();
    }

    public long bestBidTicks() {
        return bids.isEmpty() ? 0L : bids.get(0).priceTicks();
    }

    public long bestAskTicks() {
        return asks.isEmpty() ? 0L : asks.get(0).priceTicks();
    }

    public long midPriceTicks() {
        long bestBid = bestBidTicks();
        long bestAsk = bestAskTicks();
        if (bestBid > 0 && bestAsk > bestBid) {
            return (bestBid + bestAsk) / 2L;
        }
        return 0L;
    }

    public int commonDepth() {
        return Math.min(bids.size(), asks.size());
    }
}
