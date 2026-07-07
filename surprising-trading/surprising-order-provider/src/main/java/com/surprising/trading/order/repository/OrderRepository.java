package com.surprising.trading.order.repository;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.AdminMatchResultResponse;
import com.surprising.trading.api.model.AdminMatchTradeResponse;
import com.surprising.trading.api.model.AdminCursorPage;
import com.surprising.trading.api.model.AdminOrderEventResponse;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderCommandType;
import com.surprising.trading.api.model.OrderEvent;
import com.surprising.trading.api.model.OrderEventType;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PositionMode;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.order.model.OrderRecord;
import com.surprising.trading.order.model.ReduceOnlyPosition;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OrderRepository {

    private static final String INSERT_ORDER_SQL = """
            INSERT INTO trading_orders (
                order_id, user_id, client_order_id, symbol, instrument_version, side, order_type, time_in_force,
                price_ticks, quantity_steps, executed_quantity_steps, remaining_quantity_steps,
                margin_mode, position_side, maker_fee_rate_ppm, taker_fee_rate_ppm,
                reduce_only, post_only, status, reject_reason, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (user_id, client_order_id) WHERE client_order_id IS NOT NULL DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;

    public OrderRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long nextSequence(String sequenceName) {
        // PostgreSQL native sequences avoid the row-lock hotspot created by table based counters.
        Long value = jdbcTemplate.queryForObject("SELECT nextval(CAST(? AS regclass))", Long.class,
                tradingSequenceIdentifier(sequenceName));
        if (value == null) {
            throw new IllegalStateException("Failed to allocate sequence " + sequenceName);
        }
        return value;
    }

    public boolean insert(OrderRecord order) {
        int rows = jdbcTemplate.update(INSERT_ORDER_SQL,
                order.orderId(), order.userId(), emptyToNull(order.clientOrderId()), order.symbol(),
                nullableVersion(order.instrumentVersion()), order.side().name(), order.orderType().name(),
                order.timeInForce().name(),
                order.priceTicks(), order.quantitySteps(), order.executedQuantitySteps(), order.remainingQuantitySteps(),
                order.marginMode().name(), order.positionSide().name(), order.makerFeeRatePpm(), order.takerFeeRatePpm(),
                order.reduceOnly(), order.postOnly(), order.status().name(), order.rejectReason(),
                Timestamp.from(order.createdAt()), Timestamp.from(order.updatedAt()));
        return rows == 1;
    }

    public void lockUserPositionMode(long userId) {
        lockUserPositionMode(ProductLine.LINEAR_PERPETUAL, userId);
    }

    public void lockUserPositionMode(ProductLine productLine, long userId) {
        jdbcTemplate.query("""
                SELECT pg_advisory_xact_lock(hashtext('position-mode'), hashtext(?))
                """, rs -> null, productLine(productLine).name() + ":" + userId);
    }

    public PositionMode positionMode(long userId) {
        return positionMode(ProductLine.LINEAR_PERPETUAL, userId);
    }

    public PositionMode positionMode(ProductLine productLine, long userId) {
        String mode = jdbcTemplate.query("""
                SELECT position_mode
                  FROM account_position_modes
                 WHERE product_line = ?
                   AND user_id = ?
                """, (rs, rowNum) -> rs.getString("position_mode"), productLine(productLine).name(), userId)
                .stream().findFirst().orElse(null);
        return PositionMode.fromNullableDbValue(mode);
    }

    private ProductLine productLine(ProductLine productLine) {
        return productLine == null ? ProductLine.LINEAR_PERPETUAL : productLine;
    }

    public void lockUserSymbolMarginScope(long userId, String symbol) {
        jdbcTemplate.query("""
                SELECT pg_advisory_xact_lock(hashtext('trading-margin-mode'), hashtext(?))
                """, rs -> null, userId + ":" + symbol);
    }

    public boolean hasActiveMarginModeConflict(long userId, String symbol, MarginMode marginMode) {
        String normalizedMode = MarginMode.defaultIfNull(marginMode).name();
        Boolean conflict = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM account_positions p
                     WHERE p.user_id = ?
                       AND p.symbol = ?
                       AND p.margin_mode <> ?
                       AND p.signed_quantity_steps <> 0
                    UNION ALL
                    SELECT 1
                      FROM trading_orders o
                     WHERE o.user_id = ?
                       AND o.symbol = ?
                       AND o.margin_mode <> ?
                       AND o.status IN ('ACCEPTED', 'PARTIALLY_FILLED', 'CANCEL_REQUESTED')
                       AND o.remaining_quantity_steps > 0
                    UNION ALL
                    SELECT 1
                      FROM trading_trigger_orders t
                     WHERE t.user_id = ?
                       AND t.symbol = ?
                       AND t.margin_mode <> ?
                       AND t.status IN ('PENDING', 'TRIGGERING')
                    UNION ALL
                    SELECT 1
                      FROM trading_algo_orders a
                     WHERE a.user_id = ?
                       AND a.symbol = ?
                       AND a.margin_mode <> ?
                       AND a.status IN ('PENDING', 'RUNNING', 'CANCEL_REQUESTED')
                )
                """, Boolean.class, userId, symbol, normalizedMode, userId, symbol, normalizedMode,
                userId, symbol, normalizedMode, userId, symbol, normalizedMode);
        return Boolean.TRUE.equals(conflict);
    }

    public boolean requestCancel(long orderId, Instant now) {
        // Conditional update prevents concurrent cancel requests from producing duplicate cancel commands.
        int rows = jdbcTemplate.update("""
                UPDATE trading_orders
                   SET status = 'CANCEL_REQUESTED',
                       updated_at = ?
                 WHERE order_id = ?
                   AND status IN ('ACCEPTED', 'PARTIALLY_FILLED')
                """, Timestamp.from(now), orderId);
        return rows == 1;
    }

    public void reject(long orderId, String rejectReason, Instant now) {
        int rows = jdbcTemplate.update("""
                UPDATE trading_orders
                   SET status = 'REJECTED',
                       remaining_quantity_steps = 0,
                       reject_reason = ?,
                       updated_at = ?
                 WHERE order_id = ?
                   AND status = 'ACCEPTED'
                   AND executed_quantity_steps = 0
                """, rejectReason, Timestamp.from(now), orderId);
        if (rows != 1) {
            throw new IllegalStateException("failed to reject order " + orderId);
        }
    }

    public void insertEvent(OrderEvent event) {
        int rows = jdbcTemplate.update("""
                INSERT INTO trading_order_events (
                    event_id, order_id, user_id, symbol, event_type, status, reason, trace_id, event_time
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """, event.eventId(), event.orderId(), event.userId(), event.symbol(),
                event.eventType().name(), event.status().name(), event.reason(), event.traceId(),
                Timestamp.from(event.eventTime()));
        if (rows != 1) {
            throw new IllegalStateException("failed to insert order event " + event.eventId());
        }
    }

    public Optional<OrderRecord> findByOrderId(long orderId) {
        return jdbcTemplate.query("SELECT * FROM trading_orders WHERE order_id = ?",
                (rs, rowNum) -> toRecord(rs), orderId).stream().findFirst();
    }

    public boolean orderMatchesContractType(long orderId, String contractType) {
        String normalizedContractType = emptyToNull(contractType);
        if (normalizedContractType == null) {
            return true;
        }
        Boolean matched = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM trading_orders o
                      JOIN instruments i
                        ON i.symbol = o.symbol AND i.version = o.instrument_version
                     WHERE o.order_id = ?
                       AND i.contract_type = ?
                )
                """, Boolean.class, orderId, normalizedContractType);
        return Boolean.TRUE.equals(matched);
    }

    public Optional<OrderRecord> findByClientOrderId(long userId, String clientOrderId) {
        return jdbcTemplate.query("""
                SELECT *
                  FROM trading_orders
                 WHERE user_id = ? AND client_order_id = ?
                """, (rs, rowNum) -> toRecord(rs), userId, clientOrderId).stream().findFirst();
    }

    public List<OrderRecord> openOrders(long userId, String symbol, int limit) {
        return openOrders(userId, symbol, limit, null);
    }

    public List<OrderRecord> openOrders(long userId, String symbol, int limit, String contractType) {
        String normalizedSymbol = emptyToNull(symbol);
        String normalizedContractType = emptyToNull(contractType);
        String sql = """
                SELECT *
                  FROM trading_orders
                 WHERE user_id = ?
                   AND (CAST(? AS text) IS NULL OR symbol = ?)
                   AND (CAST(? AS text) IS NULL OR EXISTS (
                        SELECT 1
                          FROM instruments i
                         WHERE i.symbol = trading_orders.symbol
                           AND i.version = trading_orders.instrument_version
                           AND i.contract_type = ?
                   ))
                   AND status IN ('ACCEPTED', 'PARTIALLY_FILLED', 'CANCEL_REQUESTED')
                 ORDER BY created_at DESC
                 LIMIT ?
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> toRecord(rs),
                userId, normalizedSymbol, normalizedSymbol, normalizedContractType, normalizedContractType, limit);
    }

    public List<OrderRecord> adminOrders(Long userId,
                                         String symbol,
                                         OrderStatus status,
                                         Long orderId,
                                         int limit) {
        return adminOrderPage(userId, symbol, status, orderId, limit, null, null).items();
    }

    public AdminCursorPage.CursorPage<OrderRecord> adminOrderPage(Long userId,
                                                                  String symbol,
                                                                  OrderStatus status,
                                                                  Long orderId,
                                                                  int limit,
                                                                  String cursor,
                                                                  String sort) {
        return adminOrderPage(userId, symbol, status, orderId, limit, null, cursor, sort);
    }

    public AdminCursorPage.CursorPage<OrderRecord> adminOrderPage(Long userId,
                                                                  String symbol,
                                                                  OrderStatus status,
                                                                  Long orderId,
                                                                  int limit,
                                                                  String contractType,
                                                                  String cursor,
                                                                  String sort) {
        String normalizedSymbol = emptyToNull(symbol);
        String normalizedStatus = status == null ? null : status.name();
        String normalizedContractType = emptyToNull(contractType);
        int safeLimit = AdminCursorPage.limit(limit, 1000);
        AdminCursorPage.SortSpec createdAtDesc = new AdminCursorPage.SortSpec(
                "createdAt", "created_at", "order_id", true);
        AdminCursorPage.SortSpec createdAtAsc = new AdminCursorPage.SortSpec(
                "createdAt", "created_at", "order_id", false);
        AdminCursorPage.SortSpec sortSpec = AdminCursorPage.parseSort(
                sort, createdAtDesc, List.of(createdAtDesc, createdAtAsc));
        AdminCursorPage.Cursor decodedCursor = AdminCursorPage.decodeCursor(cursor);
        List<Object> args = new ArrayList<>();
        args.add(userId);
        args.add(userId);
        args.add(normalizedSymbol);
        args.add(normalizedSymbol);
        args.add(normalizedStatus);
        args.add(normalizedStatus);
        args.add(orderId);
        args.add(orderId);
        args.add(normalizedContractType);
        args.add(normalizedContractType);
        AdminCursorPage.addCursorArgs(args, decodedCursor);
        args.add(safeLimit + 1);
        List<OrderRecord> rows = jdbcTemplate.query("""
                SELECT *
                  FROM trading_orders
                 WHERE (CAST(? AS text) IS NULL OR user_id = ?)
                   AND (CAST(? AS text) IS NULL OR symbol = ?)
                   AND (CAST(? AS text) IS NULL OR status = ?)
                   AND (CAST(? AS text) IS NULL OR order_id = ?)
                   AND (CAST(? AS text) IS NULL OR EXISTS (
                        SELECT 1
                          FROM instruments i
                         WHERE i.symbol = trading_orders.symbol
                           AND i.version = trading_orders.instrument_version
                           AND i.contract_type = ?
                   ))
                %s
                 ORDER BY %s %s, %s %s
                 LIMIT ?
                """.formatted(AdminCursorPage.seekCondition(sortSpec, decodedCursor),
                        sortSpec.column(), sortSpec.directionSql(), sortSpec.idColumn(), sortSpec.directionSql()),
                (rs, rowNum) -> toRecord(rs),
                args.toArray());
        return AdminCursorPage.page(rows, safeLimit, sortSpec, OrderRecord::createdAt, OrderRecord::orderId);
    }

    public List<OrderRecord> adminCancelableOrders(Long userId, String symbol, int limit) {
        return adminCancelableOrders(userId, symbol, null, limit);
    }

    public List<OrderRecord> adminCancelableOrders(Long userId, String symbol, String contractType, int limit) {
        String normalizedSymbol = emptyToNull(symbol);
        String normalizedContractType = emptyToNull(contractType);
        int safeLimit = Math.max(1, Math.min(limit, 1000));
        return jdbcTemplate.query("""
                SELECT *
                  FROM trading_orders
                 WHERE (CAST(? AS text) IS NULL OR user_id = ?)
                   AND (CAST(? AS text) IS NULL OR symbol = ?)
                   AND (CAST(? AS text) IS NULL OR EXISTS (
                        SELECT 1
                          FROM instruments i
                         WHERE i.symbol = trading_orders.symbol
                           AND i.version = trading_orders.instrument_version
                           AND i.contract_type = ?
                   ))
                   AND status IN ('ACCEPTED', 'PARTIALLY_FILLED')
                   AND remaining_quantity_steps > 0
                 ORDER BY created_at ASC, order_id ASC
                 LIMIT ?
                """, (rs, rowNum) -> toRecord(rs),
                userId, userId, normalizedSymbol, normalizedSymbol,
                normalizedContractType, normalizedContractType, safeLimit);
    }

    public Optional<ReduceOnlyPosition> lockedPosition(long userId,
                                                       String symbol,
                                                       MarginMode marginMode,
                                                       PositionSide positionSide) {
        return jdbcTemplate.query("""
                SELECT signed_quantity_steps, instrument_version
                  FROM account_positions
                 WHERE user_id = ?
                   AND symbol = ?
                   AND margin_mode = ?
                   AND position_side = ?
                 FOR UPDATE
                """, (rs, rowNum) -> new ReduceOnlyPosition(
                rs.getLong("signed_quantity_steps"),
                rs.getLong("instrument_version")), userId, symbol, MarginMode.defaultIfNull(marginMode).name(),
                PositionSide.defaultIfNull(positionSide).name()).stream().findFirst();
    }

    public CancelableOrderImpact adminCancelableImpact(Long userId, String symbol) {
        return adminCancelableImpact(userId, symbol, null);
    }

    public CancelableOrderImpact adminCancelableImpact(Long userId, String symbol, String contractType) {
        String normalizedSymbol = emptyToNull(symbol);
        String normalizedContractType = emptyToNull(contractType);
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)::int AS matched,
                       COALESCE(SUM(remaining_quantity_steps), 0)::bigint AS total_remaining_quantity_steps,
                       COUNT(*) FILTER (WHERE side = 'BUY')::int AS buy_orders,
                       COUNT(*) FILTER (WHERE side = 'SELL')::int AS sell_orders
                  FROM trading_orders
                 WHERE (CAST(? AS text) IS NULL OR user_id = ?)
                   AND (CAST(? AS text) IS NULL OR symbol = ?)
                   AND (CAST(? AS text) IS NULL OR EXISTS (
                        SELECT 1
                          FROM instruments i
                         WHERE i.symbol = trading_orders.symbol
                           AND i.version = trading_orders.instrument_version
                           AND i.contract_type = ?
                   ))
                   AND status IN ('ACCEPTED', 'PARTIALLY_FILLED')
                   AND remaining_quantity_steps > 0
                """, (rs, rowNum) -> new CancelableOrderImpact(
                rs.getInt("matched"),
                rs.getLong("total_remaining_quantity_steps"),
                rs.getInt("buy_orders"),
                rs.getInt("sell_orders")), userId, userId, normalizedSymbol, normalizedSymbol,
                normalizedContractType, normalizedContractType);
    }

    public List<AdminOrderEventResponse> orderEvents(long orderId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 1000));
        return jdbcTemplate.query("""
                SELECT event_id, order_id, user_id, symbol, event_type, status, reason, trace_id,
                       event_time, created_at
                  FROM trading_order_events
                 WHERE order_id = ?
                 ORDER BY event_time ASC, event_id ASC
                 LIMIT ?
                """, (rs, rowNum) -> new AdminOrderEventResponse(
                rs.getLong("event_id"),
                rs.getLong("order_id"),
                rs.getLong("user_id"),
                rs.getString("symbol"),
                OrderEventType.valueOf(rs.getString("event_type")),
                OrderStatus.valueOf(rs.getString("status")),
                rs.getString("reason"),
                rs.getString("trace_id"),
                rs.getTimestamp("event_time").toInstant(),
                rs.getTimestamp("created_at").toInstant()), orderId, safeLimit);
    }

    public List<AdminMatchResultResponse> matchResults(long orderId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 1000));
        return jdbcTemplate.query("""
                SELECT command_id, order_id, user_id, symbol, instrument_version, command_type,
                       result_code, filled_quantity_steps, order_status, trace_id, event_time, created_at
                  FROM trading_match_results
                 WHERE order_id = ?
                 ORDER BY event_time ASC, command_id ASC
                 LIMIT ?
                """, (rs, rowNum) -> new AdminMatchResultResponse(
                rs.getLong("command_id"),
                rs.getLong("order_id"),
                rs.getLong("user_id"),
                rs.getString("symbol"),
                rs.getLong("instrument_version"),
                OrderCommandType.valueOf(rs.getString("command_type")),
                rs.getString("result_code"),
                rs.getLong("filled_quantity_steps"),
                OrderStatus.valueOf(rs.getString("order_status")),
                rs.getString("trace_id"),
                rs.getTimestamp("event_time").toInstant(),
                rs.getTimestamp("created_at").toInstant()), orderId, safeLimit);
    }

    public List<AdminMatchTradeResponse> matchTrades(Long userId, Long orderId, String symbol, int limit) {
        return matchTradePage(userId, orderId, symbol, limit, null, null).items();
    }

    public List<AdminMatchTradeResponse> matchTrades(
            Long userId, Long orderId, String symbol, String contractType, int limit) {
        return matchTradePage(userId, orderId, symbol, limit, contractType, null, null).items();
    }

    public AdminCursorPage.CursorPage<AdminMatchTradeResponse> matchTradePage(Long userId,
                                                                              Long orderId,
                                                                              String symbol,
                                                                              int limit,
                                                                              String cursor,
                                                                              String sort) {
        return matchTradePage(userId, orderId, symbol, limit, null, cursor, sort);
    }

    public AdminCursorPage.CursorPage<AdminMatchTradeResponse> matchTradePage(Long userId,
                                                                              Long orderId,
                                                                              String symbol,
                                                                              int limit,
                                                                              String contractType,
                                                                              String cursor,
                                                                              String sort) {
        String normalizedSymbol = emptyToNull(symbol);
        String normalizedContractType = emptyToNull(contractType);
        int safeLimit = AdminCursorPage.limit(limit, 1000);
        AdminCursorPage.SortSpec eventTimeDesc = new AdminCursorPage.SortSpec(
                "eventTime", "event_time", "trade_id", true);
        AdminCursorPage.SortSpec eventTimeAsc = new AdminCursorPage.SortSpec(
                "eventTime", "event_time", "trade_id", false);
        AdminCursorPage.SortSpec sortSpec = AdminCursorPage.parseSort(
                sort, eventTimeDesc, List.of(eventTimeDesc, eventTimeAsc));
        AdminCursorPage.Cursor decodedCursor = AdminCursorPage.decodeCursor(cursor);
        List<Object> args = new ArrayList<>();
        args.add(userId);
        args.add(userId);
        args.add(userId);
        args.add(orderId);
        args.add(orderId);
        args.add(orderId);
        args.add(normalizedSymbol);
        args.add(normalizedSymbol);
        args.add(normalizedContractType);
        args.add(normalizedContractType);
        AdminCursorPage.addCursorArgs(args, decodedCursor);
        args.add(safeLimit + 1);
        List<AdminMatchTradeResponse> rows = jdbcTemplate.query("""
                SELECT trade_id, command_id, symbol, taker_order_id, taker_user_id, taker_side,
                       taker_margin_mode, taker_position_side, maker_order_id, maker_user_id, maker_margin_mode,
                       maker_position_side,
                       price_ticks, quantity_steps, taker_order_completed, maker_order_completed,
                       trace_id, event_time, created_at
                  FROM trading_match_trades
                 WHERE (CAST(? AS text) IS NULL OR taker_user_id = ? OR maker_user_id = ?)
                   AND (CAST(? AS text) IS NULL OR taker_order_id = ? OR maker_order_id = ?)
                   AND (CAST(? AS text) IS NULL OR symbol = ?)
                   AND (CAST(? AS text) IS NULL OR EXISTS (
                        SELECT 1
                          FROM trading_orders o
                          JOIN instruments i
                            ON i.symbol = o.symbol AND i.version = o.instrument_version
                         WHERE (o.order_id = trading_match_trades.taker_order_id
                                OR o.order_id = trading_match_trades.maker_order_id)
                           AND i.contract_type = ?
                   ))
                %s
                 ORDER BY %s %s, %s %s
                 LIMIT ?
                """.formatted(AdminCursorPage.seekCondition(sortSpec, decodedCursor),
                        sortSpec.column(), sortSpec.directionSql(), sortSpec.idColumn(), sortSpec.directionSql()),
                (rs, rowNum) -> new AdminMatchTradeResponse(
                rs.getLong("trade_id"),
                rs.getLong("command_id"),
                rs.getString("symbol"),
                rs.getLong("taker_order_id"),
                rs.getLong("taker_user_id"),
                OrderSide.valueOf(rs.getString("taker_side")),
                MarginMode.fromNullableDbValue(rs.getString("taker_margin_mode")),
                PositionSide.fromNullableDbValue(rs.getString("taker_position_side")),
                rs.getLong("maker_order_id"),
                rs.getLong("maker_user_id"),
                MarginMode.fromNullableDbValue(rs.getString("maker_margin_mode")),
                PositionSide.fromNullableDbValue(rs.getString("maker_position_side")),
                rs.getLong("price_ticks"),
                rs.getLong("quantity_steps"),
                rs.getBoolean("taker_order_completed"),
                rs.getBoolean("maker_order_completed"),
                rs.getString("trace_id"),
                rs.getTimestamp("event_time").toInstant(),
                rs.getTimestamp("created_at").toInstant()), args.toArray());
        return AdminCursorPage.page(rows, safeLimit, sortSpec, AdminMatchTradeResponse::eventTime,
                AdminMatchTradeResponse::tradeId);
    }

    private OrderRecord toRecord(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new OrderRecord(
                rs.getLong("order_id"),
                rs.getLong("user_id"),
                rs.getString("client_order_id"),
                rs.getString("symbol"),
                longOrZero(rs, "instrument_version"),
                OrderSide.valueOf(rs.getString("side")),
                OrderType.valueOf(rs.getString("order_type")),
                TimeInForce.valueOf(rs.getString("time_in_force")),
                rs.getLong("price_ticks"),
                rs.getLong("quantity_steps"),
                rs.getLong("executed_quantity_steps"),
                rs.getLong("remaining_quantity_steps"),
                MarginMode.fromNullableDbValue(rs.getString("margin_mode")),
                PositionSide.fromNullableDbValue(rs.getString("position_side")),
                rs.getLong("maker_fee_rate_ppm"),
                rs.getLong("taker_fee_rate_ppm"),
                rs.getBoolean("reduce_only"),
                rs.getBoolean("post_only"),
                OrderStatus.valueOf(rs.getString("status")),
                rs.getString("reject_reason"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Long nullableVersion(long version) {
        return version <= 0 ? null : version;
    }

    private long longOrZero(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? 0L : value;
    }

    private String tradingSequenceIdentifier(String sequenceName) {
        if (sequenceName == null || !sequenceName.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("invalid trading sequence name: " + sequenceName);
        }
        return "public.trading_" + sequenceName.toLowerCase().replace('-', '_') + "_seq";
    }

    public record CancelableOrderImpact(
            int matched,
            long totalRemainingQuantitySteps,
            int buyOrders,
            int sellOrders) {
    }
}
