package com.surprising.trading.order.repository;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.AlgoOrderStatus;
import com.surprising.trading.api.model.AlgoOrderType;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderResponse;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.order.model.AlgoOrderChildRecord;
import com.surprising.trading.order.model.AlgoOrderProgress;
import com.surprising.trading.order.model.AlgoOrderRecord;
import com.surprising.trading.order.model.OrderRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AlgoOrderRepository {

    private static final String INSERT_ALGO_SQL = """
            INSERT INTO trading_algo_orders (
                algo_order_id, product_line, user_id, client_algo_order_id, symbol, algo_type, side, price_ticks,
                quantity_steps, child_quantity_steps, interval_seconds, duration_seconds, margin_mode,
                position_side, reduce_only, post_only, time_in_force, status, current_order_id,
                reject_reason, trace_id, start_at, next_slice_at, completed_at, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (product_line, user_id, client_algo_order_id) WHERE client_algo_order_id IS NOT NULL DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;

    public AlgoOrderRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long nextAlgoOrderId() {
        Long value = jdbcTemplate.queryForObject(
                "SELECT nextval('public.trading_algo_order_seq')", Long.class);
        if (value == null) {
            throw new IllegalStateException("failed to allocate algo order id");
        }
        return value;
    }

    public boolean insert(AlgoOrderRecord order) {
        int rows = jdbcTemplate.update(INSERT_ALGO_SQL,
                order.algoOrderId(), order.productLine().name(), order.userId(), emptyToNull(order.clientAlgoOrderId()), order.symbol(),
                order.algoType().name(), order.side().name(), order.priceTicks(), order.quantitySteps(),
                order.childQuantitySteps(), order.intervalSeconds(), order.durationSeconds(),
                order.marginMode().name(), order.positionSide().name(), order.reduceOnly(), order.postOnly(),
                order.timeInForce().name(), order.status().name(), order.currentOrderId(), order.rejectReason(),
                order.traceId(), Timestamp.from(order.startAt()), timestampOrNull(order.nextSliceAt()),
                timestampOrNull(order.completedAt()), Timestamp.from(order.createdAt()),
                Timestamp.from(order.updatedAt()));
        return rows == 1;
    }

    public Optional<AlgoOrderRecord> findByAlgoOrderId(long algoOrderId) {
        return jdbcTemplate.query("SELECT * FROM trading_algo_orders WHERE algo_order_id = ?",
                (rs, rowNum) -> toAlgoRecord(rs), algoOrderId).stream().findFirst();
    }

    public Optional<AlgoOrderRecord> findByClientAlgoOrderId(long userId, String clientAlgoOrderId) {
        return findByClientAlgoOrderId(ProductLine.LINEAR_PERPETUAL, userId, clientAlgoOrderId);
    }

    public Optional<AlgoOrderRecord> findByClientAlgoOrderId(ProductLine productLine, long userId,
                                                             String clientAlgoOrderId) {
        return jdbcTemplate.query("""
                SELECT *
                  FROM trading_algo_orders
                 WHERE product_line = ? AND user_id = ? AND client_algo_order_id = ?
                """, (rs, rowNum) -> toAlgoRecord(rs), productLine(productLine).name(), userId, clientAlgoOrderId)
                .stream().findFirst();
    }

    public boolean algoOrderMatchesContractType(long algoOrderId, String contractType) {
        String productLine = productLineNameFromContractType(contractType);
        if (productLine == null) {
            return true;
        }
        Boolean matched = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM trading_algo_orders
                     WHERE algo_order_id = ?
                       AND product_line = ?
                )
                """, Boolean.class, algoOrderId, productLine);
        return Boolean.TRUE.equals(matched);
    }

    public List<AlgoOrderRecord> openOrders(long userId, String symbol, int limit) {
        return openOrders(userId, symbol, limit, null);
    }

    public List<AlgoOrderRecord> openOrders(long userId, String symbol, int limit, String contractType) {
        String normalizedSymbol = emptyToNull(symbol);
        String productLine = productLineNameFromContractType(contractType);
        return jdbcTemplate.query("""
                SELECT *
                  FROM trading_algo_orders
                 WHERE user_id = ?
                   AND (CAST(? AS text) IS NULL OR symbol = ?)
                   AND (CAST(? AS text) IS NULL OR product_line = ?)
                   AND status IN ('PENDING', 'RUNNING', 'CANCEL_REQUESTED')
                 ORDER BY created_at DESC, algo_order_id DESC
                 LIMIT ?
                """, (rs, rowNum) -> toAlgoRecord(rs),
                userId, normalizedSymbol, normalizedSymbol, productLine, productLine, limit);
    }

    public List<AlgoOrderRecord> dueOrders(Instant now, int limit) {
        return dueOrders(ProductLine.LINEAR_PERPETUAL, now, limit);
    }

    public List<AlgoOrderRecord> dueOrders(ProductLine productLine, Instant now, int limit) {
        return jdbcTemplate.query("""
                SELECT *
                  FROM trading_algo_orders
                 WHERE product_line = ?
                   AND status IN ('PENDING', 'RUNNING')
                   AND next_slice_at <= ?
                 ORDER BY next_slice_at ASC, algo_order_id ASC
                 LIMIT ?
                 FOR UPDATE SKIP LOCKED
                """, (rs, rowNum) -> toAlgoRecord(rs), productLine(productLine).name(), Timestamp.from(now), limit);
    }

    /** Atomically leases a due parent before execution; caller must still execute under a DB transaction. */
    public Optional<AlgoOrderRecord> claimDueOrder(ProductLine productLine,
                                                    long algoOrderId,
                                                    Instant now,
                                                    Instant leaseUntil) {
        return jdbcTemplate.query("""
                UPDATE trading_algo_orders
                   SET next_slice_at = ?, updated_at = ?
                 WHERE product_line = ? AND algo_order_id = ?
                   AND status IN ('PENDING', 'RUNNING') AND next_slice_at <= ?
                RETURNING *
                """, (rs, rowNum) -> toAlgoRecord(rs), Timestamp.from(leaseUntil), Timestamp.from(now),
                productLine(productLine).name(), algoOrderId, Timestamp.from(now)).stream().findFirst();
    }

    public List<AlgoOrderRecord> scheduledOrdersForIndex(ProductLine productLine, long afterAlgoOrderId, int limit) {
        return jdbcTemplate.query("""
                SELECT * FROM trading_algo_orders
                 WHERE product_line = ? AND algo_order_id > ?
                   AND status IN ('PENDING', 'RUNNING') AND next_slice_at IS NOT NULL
                 ORDER BY algo_order_id ASC
                 LIMIT ?
                """, (rs, rowNum) -> toAlgoRecord(rs), productLine(productLine).name(), afterAlgoOrderId, limit);
    }

    public List<AlgoOrderRecord> cancelableOpenOrders(long userId, String symbol, AlgoOrderType algoType, int limit) {
        return cancelableOpenOrders(userId, symbol, algoType, limit, null);
    }

    public List<AlgoOrderRecord> cancelableOpenOrders(
            long userId, String symbol, AlgoOrderType algoType, int limit, String contractType) {
        String normalizedSymbol = emptyToNull(symbol);
        String normalizedType = algoType == null ? null : algoType.name();
        String productLine = productLineNameFromContractType(contractType);
        return jdbcTemplate.query("""
                SELECT *
                  FROM trading_algo_orders
                 WHERE user_id = ?
                   AND (CAST(? AS text) IS NULL OR symbol = ?)
                   AND (CAST(? AS text) IS NULL OR algo_type = ?)
                   AND (CAST(? AS text) IS NULL OR product_line = ?)
                   AND status IN ('PENDING', 'RUNNING')
                 ORDER BY created_at ASC, algo_order_id ASC
                 LIMIT ?
                 FOR UPDATE SKIP LOCKED
                """, (rs, rowNum) -> toAlgoRecord(rs), userId, normalizedSymbol, normalizedSymbol,
                normalizedType, normalizedType, productLine, productLine, limit);
    }

    public boolean markCancelRequested(long algoOrderId, Instant now) {
        int rows = jdbcTemplate.update("""
                UPDATE trading_algo_orders
                   SET status = 'CANCEL_REQUESTED',
                       next_slice_at = NULL,
                       updated_at = ?
                 WHERE algo_order_id = ?
                   AND status IN ('PENDING', 'RUNNING')
                """, Timestamp.from(now), algoOrderId);
        return rows == 1;
    }

    public void markCanceled(long algoOrderId, Instant now) {
        jdbcTemplate.update("""
                UPDATE trading_algo_orders
                   SET status = 'CANCELED',
                       next_slice_at = NULL,
                       completed_at = COALESCE(completed_at, ?),
                       updated_at = ?
                 WHERE algo_order_id = ?
                   AND status IN ('PENDING', 'RUNNING', 'CANCEL_REQUESTED')
                """, Timestamp.from(now), Timestamp.from(now), algoOrderId);
    }

    public void markFailed(long algoOrderId, String reason, Instant now) {
        jdbcTemplate.update("""
                UPDATE trading_algo_orders
                   SET status = 'FAILED',
                       next_slice_at = NULL,
                       reject_reason = ?,
                       completed_at = ?,
                       updated_at = ?
                 WHERE algo_order_id = ?
                   AND status IN ('PENDING', 'RUNNING')
                """, truncate(reason), Timestamp.from(now), Timestamp.from(now), algoOrderId);
    }

    public void markCompleted(long algoOrderId, Instant now) {
        jdbcTemplate.update("""
                UPDATE trading_algo_orders
                   SET status = 'COMPLETED',
                       next_slice_at = NULL,
                       completed_at = COALESCE(completed_at, ?),
                       updated_at = ?
                 WHERE algo_order_id = ?
                   AND status IN ('PENDING', 'RUNNING')
                """, Timestamp.from(now), Timestamp.from(now), algoOrderId);
    }

    public void scheduleNext(long algoOrderId, AlgoOrderStatus status, Instant nextSliceAt, Instant now) {
        jdbcTemplate.update("""
                UPDATE trading_algo_orders
                   SET status = ?,
                       next_slice_at = ?,
                       updated_at = ?
                 WHERE algo_order_id = ?
                   AND status IN ('PENDING', 'RUNNING')
                """, status.name(), Timestamp.from(nextSliceAt), Timestamp.from(now), algoOrderId);
    }

    public void markChildPlaced(AlgoOrderRecord algo,
                                int sliceIndex,
                                OrderResponse child,
                                Instant now,
                                Instant nextSliceAt) {
        jdbcTemplate.update("""
                INSERT INTO trading_algo_order_children (
                    algo_order_id, slice_index, order_id, client_order_id, quantity_steps, price_ticks,
                    order_type, time_in_force, status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (algo_order_id, slice_index) DO NOTHING
                """, algo.algoOrderId(), sliceIndex, child.orderId(), child.clientOrderId(),
                child.quantitySteps(), child.priceTicks(), child.orderType().name(), child.timeInForce().name(),
                child.status().name(), Timestamp.from(now), Timestamp.from(now));
        jdbcTemplate.update("""
                UPDATE trading_algo_orders
                   SET status = 'RUNNING',
                       current_order_id = ?,
                       next_slice_at = ?,
                       updated_at = ?
                 WHERE algo_order_id = ?
                   AND status IN ('PENDING', 'RUNNING')
                """, child.orderId(), Timestamp.from(nextSliceAt), Timestamp.from(now), algo.algoOrderId());
    }

    public void refreshChildStatuses(long algoOrderId, Instant now) {
        jdbcTemplate.update("""
                UPDATE trading_algo_order_children c
                   SET status = o.status,
                       updated_at = ?
                  FROM trading_orders o
                 WHERE c.order_id = o.order_id
                   AND c.algo_order_id = ?
                   AND c.status <> o.status
                """, Timestamp.from(now), algoOrderId);
    }

    public AlgoOrderProgress progress(long algoOrderId) {
        return jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(o.executed_quantity_steps), 0)::bigint AS executed_quantity_steps,
                       COALESCE(SUM(
                           CASE WHEN o.status IN ('ACCEPTED', 'PARTIALLY_FILLED', 'CANCEL_REQUESTED')
                                THEN o.remaining_quantity_steps ELSE 0 END
                       ), 0)::bigint AS active_quantity_steps,
                       COUNT(c.*)::int AS child_order_count,
                       COUNT(c.*) FILTER (
                           WHERE o.status IN ('ACCEPTED', 'PARTIALLY_FILLED', 'CANCEL_REQUESTED')
                             AND o.remaining_quantity_steps > 0
                       )::int AS active_child_order_count,
                       (COALESCE(MAX(c.slice_index), 0) + 1)::int AS next_slice_index
                  FROM trading_algo_order_children c
                  JOIN trading_orders o ON o.order_id = c.order_id
                 WHERE c.algo_order_id = ?
                """, (rs, rowNum) -> new AlgoOrderProgress(
                rs.getLong("executed_quantity_steps"),
                rs.getLong("active_quantity_steps"),
                rs.getInt("child_order_count"),
                rs.getInt("active_child_order_count"),
                rs.getInt("next_slice_index")), algoOrderId);
    }

    public List<OrderRecord> activeChildOrders(long algoOrderId) {
        return jdbcTemplate.query("""
                SELECT o.*
                  FROM trading_algo_order_children c
                  JOIN trading_orders o ON o.order_id = c.order_id
                 WHERE c.algo_order_id = ?
                   AND o.status IN ('ACCEPTED', 'PARTIALLY_FILLED', 'CANCEL_REQUESTED')
                   AND o.remaining_quantity_steps > 0
                 ORDER BY c.slice_index ASC
                """, (rs, rowNum) -> toOrderRecord(rs), algoOrderId);
    }

    private AlgoOrderRecord toAlgoRecord(ResultSet rs) throws SQLException {
        return new AlgoOrderRecord(
                rs.getLong("algo_order_id"),
                ProductLine.valueOf(rs.getString("product_line")),
                rs.getLong("user_id"),
                rs.getString("client_algo_order_id"),
                rs.getString("symbol"),
                AlgoOrderType.valueOf(rs.getString("algo_type")),
                OrderSide.valueOf(rs.getString("side")),
                rs.getLong("price_ticks"),
                rs.getLong("quantity_steps"),
                rs.getLong("child_quantity_steps"),
                rs.getLong("interval_seconds"),
                rs.getLong("duration_seconds"),
                MarginMode.fromNullableDbValue(rs.getString("margin_mode")),
                PositionSide.fromNullableDbValue(rs.getString("position_side")),
                rs.getBoolean("reduce_only"),
                rs.getBoolean("post_only"),
                TimeInForce.valueOf(rs.getString("time_in_force")),
                AlgoOrderStatus.valueOf(rs.getString("status")),
                longObjectOrNull(rs, "current_order_id"),
                rs.getString("reject_reason"),
                rs.getString("trace_id"),
                rs.getTimestamp("start_at").toInstant(),
                instantOrNull(rs, "next_slice_at"),
                instantOrNull(rs, "completed_at"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    @SuppressWarnings("unused")
    private AlgoOrderChildRecord toChildRecord(ResultSet rs) throws SQLException {
        return new AlgoOrderChildRecord(
                rs.getLong("algo_order_id"),
                rs.getInt("slice_index"),
                rs.getLong("order_id"),
                rs.getString("client_order_id"),
                rs.getLong("quantity_steps"),
                rs.getLong("price_ticks"),
                OrderType.valueOf(rs.getString("order_type")),
                TimeInForce.valueOf(rs.getString("time_in_force")),
                OrderStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private OrderRecord toOrderRecord(ResultSet rs) throws SQLException {
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

    private String productLineNameFromContractType(String contractType) {
        String normalizedFilter = emptyToNull(contractType);
        return normalizedFilter == null
                ? null
                : ProductLine.requireExternalCode(normalizedFilter).name();
    }

    private ProductLine productLine(ProductLine productLine) {
        return productLine == null ? ProductLine.LINEAR_PERPETUAL : productLine;
    }

    private Timestamp timestampOrNull(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private Instant instantOrNull(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private Long longObjectOrNull(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private long longOrZero(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? 0L : value;
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
