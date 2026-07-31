package com.surprising.trading.matching.repository;

import com.surprising.trading.api.model.MatchResultEvent;
import com.surprising.trading.api.model.MatchTradeEvent;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderCommandType;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.matching.model.MatchedOrderSnapshot;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

/** 只负责 {@code trading_orders} 表。 */
@Repository
public class MatchingOrderRepository {

    private final JdbcTemplate jdbcTemplate;

    public MatchingOrderRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean exists(long orderId) {
        Boolean exists = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM trading_orders WHERE order_id = ?)",
                Boolean.class, orderId);
        return Boolean.TRUE.equals(exists);
    }

    public Set<Long> existingOrderIds(Collection<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return Set.of();
        }
        List<Long> uniqueIds = orderIds.stream().distinct().toList();
        Set<Long> existing = new LinkedHashSet<>();
        for (int offset = 0; offset < uniqueIds.size(); offset += 1_000) {
            List<Long> batch = uniqueIds.subList(offset, Math.min(offset + 1_000, uniqueIds.size()));
            String placeholders = String.join(", ", Collections.nCopies(batch.size(), "?"));
            jdbcTemplate.query("""
                    SELECT order_id
                      FROM trading_orders
                     WHERE order_id IN (%s)
                    """.formatted(placeholders),
                    (RowCallbackHandler) rs -> existing.add(rs.getLong("order_id")), batch.toArray());
        }
        return Set.copyOf(existing);
    }

    public long instrumentVersion(long orderId) {
        Long version = jdbcTemplate.query("""
                SELECT instrument_version FROM trading_orders WHERE order_id = ?
                """, (rs, rowNum) -> rs.getLong("instrument_version"), orderId).stream().findFirst().orElse(null);
        if (version == null || version <= 0) {
            throw new IllegalStateException("instrument version not found for order " + orderId);
        }
        return version;
    }

    public MarginMode marginMode(long orderId) {
        String value = jdbcTemplate.query("""
                SELECT margin_mode FROM trading_orders WHERE order_id = ?
                """, (rs, rowNum) -> rs.getString("margin_mode"), orderId).stream().findFirst().orElse(null);
        if (value == null) {
            throw new IllegalStateException("margin mode not found for order " + orderId);
        }
        return MarginMode.fromNullableDbValue(value);
    }

    public PositionSide positionSide(long orderId) {
        String value = jdbcTemplate.query("""
                SELECT position_side FROM trading_orders WHERE order_id = ?
                """, (rs, rowNum) -> rs.getString("position_side"), orderId).stream().findFirst().orElse(null);
        if (value == null) {
            throw new IllegalStateException("position side not found for order " + orderId);
        }
        return PositionSide.fromNullableDbValue(value);
    }

    public MatchedOrderSnapshot snapshot(long orderId) {
        MatchedOrderSnapshot snapshot = snapshots(List.of(orderId)).get(orderId);
        if (snapshot == null) {
            throw new IllegalStateException("order snapshot not found for order " + orderId);
        }
        return snapshot;
    }

    public Map<Long, MatchedOrderSnapshot> snapshots(Collection<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return Map.of();
        }
        List<Long> uniqueIds = orderIds.stream().distinct().toList();
        Map<Long, MatchedOrderSnapshot> snapshots = new LinkedHashMap<>(uniqueIds.size());
        for (int offset = 0; offset < uniqueIds.size(); offset += 1_000) {
            List<Long> batch = uniqueIds.subList(offset, Math.min(offset + 1_000, uniqueIds.size()));
            String placeholders = String.join(", ", Collections.nCopies(batch.size(), "?"));
            jdbcTemplate.query("""
                    SELECT order_id, instrument_version, margin_mode, position_side,
                           maker_fee_rate_ppm, taker_fee_rate_ppm, quantity_steps,
                           remaining_quantity_steps, reduce_only, reservation_account_type,
                           reservation_asset, reserved_units
                      FROM trading_orders
                     WHERE order_id IN (%s)
                    """.formatted(placeholders), (RowCallbackHandler) rs -> snapshots.put(
                    rs.getLong("order_id"),
                    new MatchedOrderSnapshot(
                            rs.getLong("instrument_version"),
                            MarginMode.fromNullableDbValue(rs.getString("margin_mode")),
                            PositionSide.fromNullableDbValue(rs.getString("position_side")),
                            rs.getLong("maker_fee_rate_ppm"),
                            rs.getLong("taker_fee_rate_ppm"),
                            rs.getLong("quantity_steps"),
                            rs.getLong("remaining_quantity_steps"),
                            rs.getBoolean("reduce_only"),
                            rs.getString("reservation_account_type"),
                            rs.getString("reservation_asset"),
                            rs.getLong("reserved_units"))), batch.toArray());
        }
        if (snapshots.size() != uniqueIds.size()) {
            List<Long> missing = uniqueIds.stream().filter(id -> !snapshots.containsKey(id)).limit(10).toList();
            throw new IllegalStateException("maker order snapshots missing for orderIds=" + missing);
        }
        return Map.copyOf(snapshots);
    }

    public void applyActiveStatus(MatchResultEvent result) {
        if (result.commandType() == OrderCommandType.CANCEL) {
            if ("SUCCESS".equals(result.resultCode())) {
                updateStatus(result.orderId(), OrderStatus.CANCELED, result.eventTime());
                clearRemainingQuantity(result.orderId(), result.eventTime());
            }
            return;
        }
        if (result.orderStatus() == OrderStatus.REJECTED) {
            int rows = jdbcTemplate.update("""
                    UPDATE trading_orders
                       SET status = 'REJECTED', reject_reason = ?, remaining_quantity_steps = 0,
                           updated_at = ?, revision = revision + 1
                     WHERE order_id = ?
                    """, result.resultCode(), Timestamp.from(result.eventTime()), result.orderId());
            requireSingleRow(rows, "rejected order update");
            return;
        }
        if (result.filledQuantitySteps() > 0) {
            incrementFill(result.orderId(), result.filledQuantitySteps(), result.orderStatus(), result.eventTime());
        } else {
            updateStatus(result.orderId(), result.orderStatus(), result.eventTime());
        }
        if (result.orderStatus() == OrderStatus.CANCELED) {
            clearRemainingQuantity(result.orderId(), result.eventTime());
        }
    }

    public void applyMakerFills(List<MatchTradeEvent> trades) {
        if (trades == null || trades.isEmpty()) {
            return;
        }
        Map<Long, MakerFill> fills = new LinkedHashMap<>();
        for (MatchTradeEvent trade : trades) {
            fills.merge(trade.makerOrderId(),
                    new MakerFill(trade.quantitySteps(), trade.makerOrderCompleted(), trade.eventTime()),
                    (current, next) -> new MakerFill(
                            Math.addExact(current.quantitySteps(), next.quantitySteps()),
                            current.completed() || next.completed(),
                            current.eventTime().isAfter(next.eventTime()) ? current.eventTime() : next.eventTime()));
        }
        StringBuilder values = new StringBuilder();
        List<Object> args = new ArrayList<>(fills.size() * 4);
        for (Map.Entry<Long, MakerFill> entry : fills.entrySet()) {
            if (!values.isEmpty()) {
                values.append(", ");
            }
            values.append("(?::BIGINT, ?::BIGINT, ?::TEXT, ?::TIMESTAMPTZ)");
            args.add(entry.getKey());
            args.add(entry.getValue().quantitySteps());
            args.add(entry.getValue().completed()
                    ? OrderStatus.FILLED.name()
                    : OrderStatus.PARTIALLY_FILLED.name());
            args.add(Timestamp.from(entry.getValue().eventTime()));
        }
        Integer updated = jdbcTemplate.queryForObject("""
                WITH input(order_id, quantity_steps, status, event_time) AS (
                    VALUES %s
                ),
                updated AS (
                    UPDATE trading_orders o
                       SET executed_quantity_steps = o.executed_quantity_steps + i.quantity_steps,
                           remaining_quantity_steps = o.remaining_quantity_steps - i.quantity_steps,
                           status = i.status, updated_at = i.event_time, revision = o.revision + 1
                      FROM input i
                     WHERE o.order_id = i.order_id
                       AND o.status IN ('ACCEPTED', 'PARTIALLY_FILLED', 'CANCEL_REQUESTED')
                       AND o.quantity_steps = o.executed_quantity_steps + o.remaining_quantity_steps
                       AND o.remaining_quantity_steps >= i.quantity_steps
                    RETURNING o.order_id
                )
                SELECT count(*)::INTEGER FROM updated
                """.formatted(values), Integer.class, args.toArray());
        if (updated == null || updated != fills.size()) {
            throw new IllegalStateException("failed to apply all maker fills: expected="
                    + fills.size() + " actual=" + updated);
        }
    }

    private void incrementFill(long orderId, long quantitySteps, OrderStatus status, Instant now) {
        int rows = jdbcTemplate.update("""
                UPDATE trading_orders
                   SET executed_quantity_steps = executed_quantity_steps + ?,
                       remaining_quantity_steps = remaining_quantity_steps - ?,
                       status = ?, updated_at = ?, revision = revision + 1
                 WHERE order_id = ?
                   AND status IN ('ACCEPTED', 'PARTIALLY_FILLED', 'CANCEL_REQUESTED')
                   AND quantity_steps = executed_quantity_steps + remaining_quantity_steps
                   AND remaining_quantity_steps >= ?
                """, quantitySteps, quantitySteps, status.name(), Timestamp.from(now), orderId, quantitySteps);
        requireSingleRow(rows, "order fill update");
    }

    private void updateStatus(long orderId, OrderStatus status, Instant now) {
        int rows = jdbcTemplate.update("""
                UPDATE trading_orders
                   SET status = ?, updated_at = ?, revision = revision + 1
                 WHERE order_id = ?
                """, status.name(), Timestamp.from(now), orderId);
        requireSingleRow(rows, "order status update");
    }

    private void clearRemainingQuantity(long orderId, Instant now) {
        int rows = jdbcTemplate.update("""
                UPDATE trading_orders
                   SET remaining_quantity_steps = 0, updated_at = ?, revision = revision + 1
                 WHERE order_id = ?
                """, Timestamp.from(now), orderId);
        requireSingleRow(rows, "order remaining quantity clear");
    }

    private void requireSingleRow(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException("failed to write " + operation);
        }
    }

    private record MakerFill(long quantitySteps, boolean completed, Instant eventTime) {
    }
}
