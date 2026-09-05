package com.surprising.marketmaker.provider.repository;

import com.surprising.product.api.ProductLine;
import java.time.Instant;

/**
 * 仅负责 market_maker_reference_samples 表。
 */
public interface MarketMakerReferenceSampleRepository {

    void record(MarketMakerReferenceSampleWrite sample);

    record MarketMakerReferenceSampleWrite(
            String strategyId,
            ProductLine productLine,
            String symbol,
            String nodeId,
            long cycleSequence,
            String sourceName,
            String transport,
            int bidLevels,
            int askLevels,
            long bestBidTicks,
            long bestAskTicks,
            long midPriceTicks,
            long spreadTicks,
            Instant receivedAt,
            String traceId,
            Instant sampledAt) {
    }
}
