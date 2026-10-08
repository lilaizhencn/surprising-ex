package com.surprising.marketmaker.provider.repository;

import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.util.List;

/**
 * 当前进程的有界运行诊断记录，不持久化。
 */
public interface MarketMakerRunEventRepository {

    void record(MarketMakerRunEventWrite event);

    List<MarketMakerRunEventRecord> find(ProductLine productLine,
                                         String strategyId,
                                         String instrumentId,
                                         Long accountId,
                                         String eventType,
                                         int limit);

    CursorPage<MarketMakerRunEventRecord> findPage(ProductLine productLine,
                                                   String strategyId,
                                                   String instrumentId,
                                                   Long accountId,
                                                   String eventType,
                                                   int limit,
                                                   String cursor,
                                                   String sort);

    record MarketMakerRunEventWrite(
            String strategyId,
            ProductLine productLine,
            String instrumentId,
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

    record MarketMakerRunEventRecord(
            long eventId,
            String strategyId,
            ProductLine productLine,
            String instrumentId,
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
}
