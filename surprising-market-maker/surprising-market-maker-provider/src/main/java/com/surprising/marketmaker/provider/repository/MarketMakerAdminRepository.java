package com.surprising.marketmaker.provider.repository;

import java.time.Instant;
import java.util.List;

public interface MarketMakerAdminRepository {

    void recordRunEvent(MarketMakerRunEventWrite event);

    void recordReferenceSample(MarketMakerReferenceSampleWrite sample);

    List<MarketMakerRunEventRecord> runEvents(String strategyId,
                                              String symbol,
                                              Long accountId,
                                              String eventType,
                                              int limit);

    CursorPage<MarketMakerRunEventRecord> runEventsPage(String strategyId,
                                                        String symbol,
                                                        Long accountId,
                                                        String eventType,
                                                        int limit,
                                                        String cursor,
                                                        String sort);

    List<MarketMakerPnlAttributionRecord> pnlAttribution(List<MarketMakerPnlScope> scopes,
                                                         Instant since,
                                                         Instant until);

    record MarketMakerRunEventWrite(
            String strategyId,
            String symbol,
            Long accountId,
            String nodeId,
            long cycleSequence,
            String eventType,
            long submittedOrders,
            long canceledOrders,
            long rejectedOrders,
            String skippedReason,
            String errorMessage,
            String traceId,
            Instant createdAt) {
    }

    record MarketMakerReferenceSampleWrite(
            String strategyId,
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

    record MarketMakerRunEventRecord(
            long eventId,
            String strategyId,
            String symbol,
            Long accountId,
            String nodeId,
            long cycleSequence,
            String eventType,
            long submittedOrders,
            long canceledOrders,
            long rejectedOrders,
            String skippedReason,
            String errorMessage,
            String traceId,
            Instant createdAt) {
    }

    record CursorPage<T>(
            List<T> items,
            String nextCursor,
            boolean hasMore,
            String sort,
            int limit) {
    }

    record MarketMakerPnlScope(
            String strategyId,
            String symbol,
            long accountId,
            String marginMode,
            String clientOrderPrefix) {
    }

    record MarketMakerPnlAttributionRecord(
            String strategyId,
            String symbol,
            long accountId,
            String marginMode,
            long orderCount,
            long rejectedOrders,
            long makerTrades,
            long takerTrades,
            long totalTrades,
            long makerQuantitySteps,
            long takerQuantitySteps,
            long totalQuantitySteps,
            String totalNotionalTicks,
            long netFeeUnits,
            long feeEntries,
            long currentRealizedPnlUnits,
            long signedInventorySteps,
            Instant positionUpdatedAt,
            Instant firstTradeAt,
            Instant lastTradeAt) {
    }
}
