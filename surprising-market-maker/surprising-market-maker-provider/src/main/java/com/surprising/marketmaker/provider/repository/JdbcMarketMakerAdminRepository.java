package com.surprising.marketmaker.provider.repository;

import com.surprising.product.api.ProductLine;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcMarketMakerAdminRepository implements MarketMakerAdminRepository {

    private static final int MAX_RUN_EVENT_LIMIT = 1000;
    private static final AdminCursorPage.SortSpec RUN_EVENT_CREATED_AT_DESC =
            new AdminCursorPage.SortSpec("createdAt", "created_at", "event_id", true);
    private static final List<AdminCursorPage.SortSpec> RUN_EVENT_SORTS = List.of(
            RUN_EVENT_CREATED_AT_DESC,
            new AdminCursorPage.SortSpec("createdAt", "created_at", "event_id", false));

    private final JdbcTemplate jdbcTemplate;

    public JdbcMarketMakerAdminRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void recordRunEvent(MarketMakerRunEventWrite event) {
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
    public void recordReferenceSample(MarketMakerReferenceSampleWrite sample) {
        jdbcTemplate.update("""
                INSERT INTO market_maker_reference_samples (
                    product_line, strategy_id, symbol, node_id, cycle_sequence, source_name, transport,
                    bid_levels, ask_levels, best_bid_ticks, best_ask_ticks, mid_price_ticks,
                    spread_ticks, received_at, trace_id, sampled_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                sample.productLine().name(),
                sample.strategyId(),
                sample.symbol(),
                sample.nodeId(),
                sample.cycleSequence(),
                truncate(sample.sourceName(), 64),
                truncate(sample.transport(), 32),
                sample.bidLevels(),
                sample.askLevels(),
                sample.bestBidTicks(),
                sample.bestAskTicks(),
                sample.midPriceTicks(),
                sample.spreadTicks(),
                Timestamp.from(sample.receivedAt() == null ? Instant.now() : sample.receivedAt()),
                truncate(sample.traceId(), 128),
                Timestamp.from(sample.sampledAt() == null ? Instant.now() : sample.sampledAt()));
    }

    @Override
    public List<MarketMakerRunEventRecord> runEvents(ProductLine productLine,
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
    public CursorPage<MarketMakerRunEventRecord> runEventsPage(ProductLine productLine,
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
        List<MarketMakerRunEventRecord> fetchedRows = jdbcTemplate.query(sql, this::toRunEvent,
                args.toArray());
        AdminCursorPage.CursorPage<MarketMakerRunEventRecord> page = AdminCursorPage.page(
                fetchedRows,
                safeLimit,
                sortSpec,
                MarketMakerRunEventRecord::createdAt,
                MarketMakerRunEventRecord::eventId);
        return new CursorPage<>(page.items(), page.nextCursor(), page.hasMore(), page.sort(), page.limit());
    }

    @Override
    public List<MarketMakerPnlAttributionRecord> pnlAttribution(List<MarketMakerPnlScope> scopes,
                                                                Instant since,
                                                                Instant until) {
        List<MarketMakerPnlAttributionRecord> rows = new ArrayList<>();
        for (MarketMakerPnlScope scope : scopes) {
            rows.add(pnlAttribution(scope, since, until));
        }
        return rows;
    }

    private MarketMakerPnlAttributionRecord pnlAttribution(MarketMakerPnlScope scope,
                                                           Instant since,
                                                           Instant until) {
        Timestamp sinceTs = Timestamp.from(since);
        Timestamp untilTs = Timestamp.from(until);
        String orderPrefix = scope.clientOrderPrefix() + "%";
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                WITH scoped_orders AS (
                    SELECT order_id, user_id, client_order_id, symbol, status, margin_mode, created_at
                      FROM trading_orders
                     WHERE user_id = ?
                       AND symbol = ?
                       AND client_order_id LIKE ?
                ),
                scoped_trades AS (
                    SELECT t.trade_id, t.symbol, t.price_ticks, t.quantity_steps, t.event_time,
                           CASE WHEN t.maker_order_id = o.order_id THEN 'MAKER' ELSE 'TAKER' END AS liquidity_role
                      FROM trading_match_trades t
                      JOIN scoped_orders o
                        ON o.order_id = t.maker_order_id OR o.order_id = t.taker_order_id
                     WHERE t.event_time >= ?
                       AND t.event_time < ?
                ),
                scoped_fees AS (
                    SELECT l.amount_units
                      FROM account_ledger_entries l
                      JOIN scoped_orders o ON o.order_id = l.order_id
                     WHERE l.user_id = ?
                       AND l.reference_type = 'TRADE_FEE'
                       AND l.created_at >= ?
                       AND l.created_at < ?
                ),
                position_row AS (
                    SELECT signed_quantity_steps, realized_pnl_units, updated_at
                      FROM account_positions
                     WHERE user_id = ?
                       AND symbol = ?
                       AND margin_mode = ?
                )
                SELECT
                       (SELECT COUNT(*) FROM scoped_orders WHERE created_at >= ? AND created_at < ?) AS order_count,
                       (SELECT COUNT(*) FROM scoped_orders WHERE status = 'REJECTED' AND created_at >= ? AND created_at < ?) AS rejected_orders,
                       COUNT(*) FILTER (WHERE liquidity_role = 'MAKER') AS maker_trades,
                       COUNT(*) FILTER (WHERE liquidity_role = 'TAKER') AS taker_trades,
                       COUNT(*) AS total_trades,
                       COALESCE(SUM(quantity_steps) FILTER (WHERE liquidity_role = 'MAKER'), 0) AS maker_quantity_steps,
                       COALESCE(SUM(quantity_steps) FILTER (WHERE liquidity_role = 'TAKER'), 0) AS taker_quantity_steps,
                       COALESCE(SUM(quantity_steps), 0) AS total_quantity_steps,
                       COALESCE(SUM(price_ticks::numeric * quantity_steps::numeric), 0) AS total_notional_ticks,
                       (SELECT COALESCE(SUM(amount_units), 0) FROM scoped_fees) AS net_fee_units,
                       (SELECT COUNT(*) FROM scoped_fees) AS fee_entries,
                       COALESCE((SELECT realized_pnl_units FROM position_row), 0) AS current_realized_pnl_units,
                       COALESCE((SELECT signed_quantity_steps FROM position_row), 0) AS signed_inventory_steps,
                       (SELECT updated_at FROM position_row) AS position_updated_at,
                       MIN(event_time) AS first_trade_at,
                       MAX(event_time) AS last_trade_at
                  FROM scoped_trades
                """,
                scope.accountId(), scope.symbol(), orderPrefix,
                sinceTs, untilTs,
                scope.accountId(), sinceTs, untilTs,
                scope.accountId(), scope.symbol(), scope.marginMode(),
                sinceTs, untilTs,
                sinceTs, untilTs);
        return new MarketMakerPnlAttributionRecord(
                scope.strategyId(),
                scope.productLine(),
                scope.symbol(),
                scope.accountId(),
                scope.marginMode(),
                longValue(row.get("order_count")),
                longValue(row.get("rejected_orders")),
                longValue(row.get("maker_trades")),
                longValue(row.get("taker_trades")),
                longValue(row.get("total_trades")),
                longValue(row.get("maker_quantity_steps")),
                longValue(row.get("taker_quantity_steps")),
                longValue(row.get("total_quantity_steps")),
                decimalString(row.get("total_notional_ticks")),
                longValue(row.get("net_fee_units")),
                longValue(row.get("fee_entries")),
                longValue(row.get("current_realized_pnl_units")),
                longValue(row.get("signed_inventory_steps")),
                instantValue(row.get("position_updated_at")),
                instantValue(row.get("first_trade_at")),
                instantValue(row.get("last_trade_at")));
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

    private long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private String decimalString(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        return value == null ? "0" : String.valueOf(value);
    }

    private Instant instantValue(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        return null;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
