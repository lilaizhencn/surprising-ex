package com.surprising.trading.order.repository;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderResponse;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.order.model.AlgoOrderProgress;
import com.surprising.trading.order.model.AlgoOrderRecord;
import com.surprising.trading.order.model.OrderRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 算法子单仓储，写操作只负责 {@code trading_algo_order_children} 表。
 *
 * <p>不可拆原因：子单状态刷新、执行进度和待撤子单必须把子单映射与
 * {@code trading_orders} 的实时终态放在同一条 SQL 快照内读取或更新。
 * 若先读子单编号再查普通订单，并发成交可能导致重复切片或漏撤单。</p>
 */
@Repository
public class AlgoOrderChildRepository {

    private final JdbcTemplate jdbcTemplate;

    public AlgoOrderChildRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean insert(AlgoOrderRecord algo, int sliceIndex, OrderResponse child, Instant now) {
        return jdbcTemplate.update("""
                INSERT INTO trading_algo_order_children (
                    algo_order_id, slice_index, order_id, client_order_id, quantity_steps, price_ticks,
                    order_type, time_in_force, status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (algo_order_id, slice_index) DO NOTHING
                """, algo.algoOrderId(), sliceIndex, child.orderId(), child.clientOrderId(),
                child.quantitySteps(), child.priceTicks(), child.orderType().name(), child.timeInForce().name(),
                child.status().name(), Timestamp.from(now), Timestamp.from(now)) == 1;
    }

    public void refreshStatuses(long algoOrderId, Instant now) {
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
                       COALESCE(SUM(CASE
                           WHEN o.status IN ('ACCEPTED', 'PARTIALLY_FILLED', 'CANCEL_REQUESTED')
                           THEN o.remaining_quantity_steps ELSE 0 END), 0)::bigint AS active_quantity_steps,
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
                rs.getLong("executed_quantity_steps"), rs.getLong("active_quantity_steps"),
                rs.getInt("child_order_count"), rs.getInt("active_child_order_count"),
                rs.getInt("next_slice_index")), algoOrderId);
    }

    public List<OrderRecord> activeOrders(long algoOrderId) {
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

    private OrderRecord toOrderRecord(ResultSet rs) throws SQLException {
        return new OrderRecord(
                rs.getLong("order_id"), ProductLine.valueOf(rs.getString("product_line")), rs.getLong("user_id"),
                rs.getString("client_order_id"), rs.getString("symbol"), longOrZero(rs, "instrument_version"),
                OrderSide.valueOf(rs.getString("side")), OrderType.valueOf(rs.getString("order_type")),
                TimeInForce.valueOf(rs.getString("time_in_force")), rs.getLong("price_ticks"),
                rs.getLong("quantity_steps"), rs.getLong("executed_quantity_steps"),
                rs.getLong("remaining_quantity_steps"), MarginMode.fromNullableDbValue(rs.getString("margin_mode")),
                PositionSide.fromNullableDbValue(rs.getString("position_side")),
                rs.getLong("maker_fee_rate_ppm"), rs.getLong("taker_fee_rate_ppm"),
                rs.getBoolean("reduce_only"), rs.getBoolean("post_only"),
                rs.getString("reservation_account_type"), rs.getString("reservation_asset"),
                rs.getLong("reserved_units"), OrderStatus.valueOf(rs.getString("status")),
                rs.getString("reject_reason"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), rs.getLong("revision"));
    }

    private long longOrZero(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? 0L : value;
    }
}
