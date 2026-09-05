package com.surprising.marketmaker.provider.repository;

import com.surprising.product.api.ProductLine;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * market_maker_strategy_run_events 表的 JDBC 实现。
 */
@Repository
public class JdbcMarketMakerRunEventRepository implements MarketMakerRunEventRepository {

    private static final int MAX_RUN_EVENT_LIMIT = 1000;
    private static final AdminCursorPage.SortSpec RUN_EVENT_CREATED_AT_DESC =
            new AdminCursorPage.SortSpec("createdAt", "created_at", "event_id", true);
    private static final List<AdminCursorPage.SortSpec> RUN_EVENT_SORTS = List.of(
            RUN_EVENT_CREATED_AT_DESC,
            new AdminCursorPage.SortSpec("createdAt", "created_at", "event_id", false));

    private final JdbcTemplate jdbcTemplate;

    public JdbcMarketMakerRunEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void record(MarketMakerRunEventWrite event) {
        jdbcTemplate.update("""
                INSERT INTO market_maker_strategy_run_events (
                    product_line, strategy_id, symbol, account_id, node_id, cycle_sequence, event_type,
                    submitted_orders, canceled_orders, rejected_orders, skipped_reason,
                    error_message, trace_id, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                event.productLine().name(),
                event.strategyId(),
                event.symbol(),
                event.accountId(),
                event.nodeId(),
                event.cycleSequence(),
                event.eventType(),
                event.submittedOrders(),
                event.canceledOrders(),
                event.rejectedOrders(),
                event.skippedReason(),
                truncate(event.errorMessage(), 1000),
                truncate(event.traceId(), 128),
                Timestamp.from(event.createdAt() == null ? Instant.now() : event.createdAt()));
    }

    @Override
    public List<MarketMakerRunEventRecord> find(ProductLine productLine,
                                                String strategyId,
                                                String symbol,
                                                Long accountId,
                                                String eventType,
                                                int limit) {
        int safeLimit = AdminCursorPage.limit(limit, MAX_RUN_EVENT_LIMIT);
        return jdbcTemplate.query("""
                SELECT event_id, product_line, strategy_id, symbol, account_id, node_id, cycle_sequence,
                       event_type, submitted_orders, canceled_orders, rejected_orders,
                       skipped_reason, error_message, trace_id, created_at
                  FROM market_maker_strategy_run_events
                 WHERE (CAST(? AS text) IS NULL OR product_line = ?)
                   AND (CAST(? AS text) IS NULL OR strategy_id = ?)
                   AND (CAST(? AS text) IS NULL OR symbol = ?)
                   AND (CAST(? AS text) IS NULL OR account_id = ?)
                   AND (CAST(? AS text) IS NULL OR event_type = ?)
                 ORDER BY created_at DESC, event_id DESC
                 LIMIT ?
                """, this::toRunEvent,
                productLine == null ? null : productLine.name(),
                productLine == null ? null : productLine.name(),
                strategyId, strategyId,
                symbol, symbol,
                accountId, accountId,
                eventType, eventType,
                safeLimit);
    }

    @Override
    public CursorPage<MarketMakerRunEventRecord> findPage(ProductLine productLine,
                                                          String strategyId,
                                                          String symbol,
                                                          Long accountId,
                                                          String eventType,
                                                          int limit,
                                                          String cursor,
                                                          String sort) {
        int safeLimit = AdminCursorPage.limit(limit, MAX_RUN_EVENT_LIMIT);
        AdminCursorPage.SortSpec sortSpec = AdminCursorPage.parseSort(
                sort, RUN_EVENT_CREATED_AT_DESC, RUN_EVENT_SORTS);
        AdminCursorPage.Cursor decodedCursor = AdminCursorPage.decodeCursor(cursor);
        List<Object> args = new ArrayList<>();
        args.add(productLine == null ? null : productLine.name());
        args.add(productLine == null ? null : productLine.name());
        args.add(strategyId);
        args.add(strategyId);
        args.add(symbol);
        args.add(symbol);
        args.add(accountId);
        args.add(accountId);
        args.add(eventType);
        args.add(eventType);
        String sql = """
                SELECT event_id, product_line, strategy_id, symbol, account_id, node_id, cycle_sequence,
                       event_type, submitted_orders, canceled_orders, rejected_orders,
                       skipped_reason, error_message, trace_id, created_at
                  FROM market_maker_strategy_run_events
                 WHERE (CAST(? AS text) IS NULL OR product_line = ?)
                   AND (CAST(? AS text) IS NULL OR strategy_id = ?)
                   AND (CAST(? AS text) IS NULL OR symbol = ?)
                   AND (CAST(? AS text) IS NULL OR account_id = ?)
                   AND (CAST(? AS text) IS NULL OR event_type = ?)
                """ + AdminCursorPage.seekCondition(sortSpec, decodedCursor) + """
                 ORDER BY %s %s, event_id %s
                 LIMIT ?
                """.formatted(sortSpec.column(), sortSpec.directionSql(), sortSpec.directionSql());
        AdminCursorPage.addCursorArgs(args, decodedCursor);
        args.add(safeLimit + 1);
        List<MarketMakerRunEventRecord> fetchedRows = jdbcTemplate.query(sql, this::toRunEvent, args.toArray());
        AdminCursorPage.CursorPage<MarketMakerRunEventRecord> page = AdminCursorPage.page(
                fetchedRows,
                safeLimit,
                sortSpec,
                MarketMakerRunEventRecord::createdAt,
                MarketMakerRunEventRecord::eventId);
        return new CursorPage<>(page.items(), page.nextCursor(), page.hasMore(), page.sort(), page.limit());
    }

    private MarketMakerRunEventRecord toRunEvent(ResultSet rs, int rowNum) throws SQLException {
        return new MarketMakerRunEventRecord(
                rs.getLong("event_id"),
                rs.getString("strategy_id"),
                ProductLine.valueOf(rs.getString("product_line")),
                rs.getString("symbol"),
                nullableLong(rs, "account_id"),
                rs.getString("node_id"),
                rs.getLong("cycle_sequence"),
                rs.getString("event_type"),
                rs.getLong("submitted_orders"),
                rs.getLong("canceled_orders"),
                rs.getLong("rejected_orders"),
                rs.getString("skipped_reason"),
                rs.getString("error_message"),
                rs.getString("trace_id"),
                rs.getTimestamp("created_at").toInstant());
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
