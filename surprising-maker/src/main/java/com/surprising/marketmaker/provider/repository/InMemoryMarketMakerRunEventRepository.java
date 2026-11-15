package com.surprising.marketmaker.provider.repository;

import com.surprising.trading.api.utils.AdminCursorPage;

import com.surprising.product.api.ProductLine;
import com.surprising.marketmaker.provider.model.MarketMakerRunEventPageQuery;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Repository;

/** Recent diagnostic events only: bounded to 2,048 entries, discarded on restart. */
@Repository
public class InMemoryMarketMakerRunEventRepository implements MarketMakerRunEventRepository {
    private static final int CAPACITY = 2048;
    private final ConcurrentLinkedDeque<MarketMakerRunEventRecord> events = new ConcurrentLinkedDeque<>();
    private final AtomicInteger size = new AtomicInteger();
    private final AtomicLong sequence = new AtomicLong();

    @Override public void record(MarketMakerRunEventWrite v) {
        events.addLast(new MarketMakerRunEventRecord(sequence.incrementAndGet(), v.strategyId(), v.productLine(), v.instrumentId(),
                v.accountId(), v.nodeId(), v.cycleSequence(), v.eventType(), v.submittedOrders(), v.canceledOrders(),
                v.rejectedOrders(), v.skippedReason(), truncate(v.errorMessage(), 1000), v.createdAt() == null ? Instant.now() : v.createdAt()));
        if (size.incrementAndGet() > CAPACITY && events.pollFirst() != null) {
            size.decrementAndGet();
        }
    }
    @Override public CursorPage<MarketMakerRunEventRecord> findPage(ProductLine line, String strategy,
            String instrument, Long account, String type, MarketMakerRunEventPageQuery query) {
        var order = new AdminCursorPage.SortSpec("createdAt", query.descending());
        var after = query.afterTime() == null ? null : new AdminCursorPage.Cursor(query.afterTime(), query.afterId());
        int count = query.limit();
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
