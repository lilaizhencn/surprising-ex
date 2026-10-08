package com.surprising.marketmaker.provider.repository;

import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Repository;

/** Recent diagnostic events only: bounded to 2,048 entries, discarded on restart. */
@Repository
public class InMemoryMarketMakerRunEventRepository implements MarketMakerRunEventRepository {
    private static final int CAPACITY = 2048;
    private static final AdminCursorPage.SortSpec DESC = new AdminCursorPage.SortSpec("createdAt", true);
    private static final List<AdminCursorPage.SortSpec> SORTS = List.of(DESC,
            new AdminCursorPage.SortSpec("createdAt", false));
    private final Deque<MarketMakerRunEventRecord> events = new ArrayDeque<>(CAPACITY);
    private long sequence;

    @Override public synchronized void record(MarketMakerRunEventWrite v) {
        if (events.size() == CAPACITY) events.removeFirst();
        events.addLast(new MarketMakerRunEventRecord(++sequence, v.strategyId(), v.productLine(), v.instrumentId(),
                v.accountId(), v.nodeId(), v.cycleSequence(), v.eventType(), v.submittedOrders(), v.canceledOrders(),
                v.rejectedOrders(), v.skippedReason(), truncate(v.errorMessage(), 1000), truncate(v.traceId(), 128),
                v.createdAt() == null ? Instant.now() : v.createdAt()));
    }
    @Override public List<MarketMakerRunEventRecord> find(ProductLine line, String strategy, String instrument,
                                                         Long account, String type, int limit) {
        return findPage(line, strategy, instrument, account, type, limit, null, null).items();
    }
    @Override public synchronized CursorPage<MarketMakerRunEventRecord> findPage(ProductLine line, String strategy,
            String instrument, Long account, String type, int limit, String cursor, String sort) {
        var order = AdminCursorPage.parseSort(sort, DESC, SORTS);
        var after = AdminCursorPage.decodeCursor(cursor);
        int count = AdminCursorPage.limit(limit, 1000);
        Comparator<MarketMakerRunEventRecord> comparator = Comparator.comparing(MarketMakerRunEventRecord::createdAt)
                .thenComparingLong(MarketMakerRunEventRecord::eventId);
        if (order.descending()) comparator = comparator.reversed();
        var rows = events.stream().filter(v -> (line == null || v.productLine() == line)
                && (strategy == null || v.strategyId().equalsIgnoreCase(strategy))
                && (instrument == null || v.instrumentId().equals(instrument))
                && (account == null || account.equals(v.accountId()))
                && (type == null || type.equals(v.eventType())))
                .filter(v -> {
                    if (after == null) return true;
                    int compared = v.createdAt().compareTo(after.timestamp());
                    if (compared == 0) compared = Long.compare(v.eventId(), after.id());
                    return order.descending() ? compared < 0 : compared > 0;
                }).sorted(comparator).limit(count + 1L).toList();
        var page = AdminCursorPage.page(rows, count, order, MarketMakerRunEventRecord::createdAt,
                MarketMakerRunEventRecord::eventId);
        return new CursorPage<>(page.items(), page.nextCursor(), page.hasMore(), page.sort(), page.limit());
    }
    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
