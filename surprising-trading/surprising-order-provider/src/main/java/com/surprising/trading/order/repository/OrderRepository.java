package com.surprising.trading.order.repository;

import com.surprising.trading.api.model.OrderEvent;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.order.model.OrderRecord;
import java.sql.Timestamp;
import java.time.Instant;
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
                margin_mode, maker_fee_rate_ppm, taker_fee_rate_ppm,
                reduce_only, post_only, status, reject_reason, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (user_id, client_order_id) WHERE client_order_id IS NOT NULL DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;

    public OrderRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long nextSequence(String sequenceName) {
        // A single atomic PostgreSQL statement keeps IDs monotonic across all order-provider nodes.
        Long value = jdbcTemplate.queryForObject("""
                INSERT INTO trading_sequences (sequence_name, sequence_value, updated_at)
                VALUES (?, 1, now())
                ON CONFLICT (sequence_name) DO UPDATE SET
                    sequence_value = trading_sequences.sequence_value + 1,
                    updated_at = now()
                RETURNING sequence_value
                """, Long.class, sequenceName);
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
                order.marginMode().name(), order.makerFeeRatePpm(), order.takerFeeRatePpm(),
                order.reduceOnly(), order.postOnly(), order.status().name(), order.rejectReason(),
                Timestamp.from(order.createdAt()), Timestamp.from(order.updatedAt()));
        return rows == 1;
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

    public Optional<OrderRecord> findByClientOrderId(long userId, String clientOrderId) {
        return jdbcTemplate.query("""
                SELECT *
                  FROM trading_orders
                 WHERE user_id = ? AND client_order_id = ?
                """, (rs, rowNum) -> toRecord(rs), userId, clientOrderId).stream().findFirst();
    }

    public List<OrderRecord> openOrders(long userId, String symbol, int limit) {
        String normalizedSymbol = emptyToNull(symbol);
        String sql = """
                SELECT *
                  FROM trading_orders
                 WHERE user_id = ?
                   AND (? IS NULL OR symbol = ?)
                   AND status IN ('ACCEPTED', 'PARTIALLY_FILLED', 'CANCEL_REQUESTED')
                 ORDER BY created_at DESC
                 LIMIT ?
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> toRecord(rs), userId, normalizedSymbol, normalizedSymbol, limit);
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
}
