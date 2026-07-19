package com.surprising.trading.matching.repository;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MatchResultEvent;
import com.surprising.trading.api.model.MatchTradeEvent;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderCommandType;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.matching.config.MatchingProperties;
import com.surprising.trading.matching.model.MatchedOrderSnapshot;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

@Repository
public class MatchingResultRepository {

    private final JdbcTemplate jdbcTemplate;
    private final MatchingProperties properties;

    public MatchingResultRepository(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new MatchingProperties());
    }

    @Autowired
    public MatchingResultRepository(JdbcTemplate jdbcTemplate,
                                    MatchingProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    public CommandState commandState(long commandId, long orderId) {
        return jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                           SELECT 1 FROM trading_match_results WHERE command_id = ?
                       ) AS result_exists,
                       EXISTS (
                           SELECT 1 FROM trading_orders WHERE order_id = ?
                       ) AS order_exists
                """, (rs, rowNum) -> new CommandState(
                rs.getBoolean("result_exists"), rs.getBoolean("order_exists")), commandId, orderId);
    }

    public Map<Long, CommandState> commandStates(Map<Long, Long> commandOrderIds) {
        if (commandOrderIds == null || commandOrderIds.isEmpty()) {
            return Map.of();
        }
        List<Map.Entry<Long, Long>> identities = List.copyOf(commandOrderIds.entrySet());
        String values = String.join(", ", Collections.nCopies(identities.size(), "(?::bigint, ?::bigint)"));
        List<Object> args = new ArrayList<>(identities.size() * 2);
        for (Map.Entry<Long, Long> identity : identities) {
            args.add(identity.getKey());
            args.add(identity.getValue());
        }
        Map<Long, CommandState> states = new LinkedHashMap<>(identities.size());
        jdbcTemplate.query("""
                WITH input(command_id, order_id) AS (
                    VALUES %s
                )
                SELECT input.command_id,
                       result.command_id IS NOT NULL AS result_exists,
                       orders.order_id IS NOT NULL AS order_exists
                  FROM input
                  LEFT JOIN trading_match_results result
                    ON result.command_id = input.command_id
                  LEFT JOIN trading_orders orders
                    ON orders.order_id = input.order_id
                 ORDER BY input.command_id
                """.formatted(values), (RowCallbackHandler) rs -> states.put(
                rs.getLong("command_id"),
                new CommandState(rs.getBoolean("result_exists"), rs.getBoolean("order_exists"))),
                args.toArray());
        if (states.size() != identities.size()) {
            throw new IllegalStateException("failed to read all matching command states");
        }
        return Map.copyOf(states);
    }

    public long orderInstrumentVersion(long orderId) {
        Long version = jdbcTemplate.query("""
                SELECT instrument_version
                  FROM trading_orders
                 WHERE order_id = ?
                """, (rs, rowNum) -> rs.getLong("instrument_version"), orderId).stream().findFirst().orElse(null);
        if (version == null || version <= 0) {
            throw new IllegalStateException("instrument version not found for order " + orderId);
        }
        return version;
    }

    public MarginMode orderMarginMode(long orderId) {
        String marginMode = jdbcTemplate.query("""
                SELECT margin_mode
                  FROM trading_orders
                 WHERE order_id = ?
                """, (rs, rowNum) -> rs.getString("margin_mode"), orderId).stream().findFirst().orElse(null);
        if (marginMode == null) {
            throw new IllegalStateException("margin mode not found for order " + orderId);
        }
        return MarginMode.fromNullableDbValue(marginMode);
    }

    public PositionSide orderPositionSide(long orderId) {
        String positionSide = jdbcTemplate.query("""
                SELECT position_side
                  FROM trading_orders
                 WHERE order_id = ?
                """, (rs, rowNum) -> rs.getString("position_side"), orderId).stream().findFirst().orElse(null);
        if (positionSide == null) {
            throw new IllegalStateException("position side not found for order " + orderId);
        }
        return PositionSide.fromNullableDbValue(positionSide);
    }

    public MatchedOrderSnapshot orderSnapshot(long orderId) {
        MatchedOrderSnapshot snapshot = orderSnapshots(List.of(orderId)).get(orderId);
        if (snapshot == null) {
            throw new IllegalStateException("order snapshot not found for order " + orderId);
        }
        return snapshot;
    }

    public Map<Long, MatchedOrderSnapshot> orderSnapshots(Collection<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return Map.of();
        }
        List<Long> uniqueIds = orderIds.stream().distinct().toList();
        Map<Long, MatchedOrderSnapshot> snapshots = new LinkedHashMap<>(uniqueIds.size());
        for (int offset = 0; offset < uniqueIds.size(); offset += 1_000) {
            List<Long> batch = uniqueIds.subList(offset, Math.min(offset + 1_000, uniqueIds.size()));
            String placeholders = String.join(", ", Collections.nCopies(batch.size(), "?"));
            jdbcTemplate.query("""
                    SELECT order_id,
                           instrument_version,
                           margin_mode,
                           position_side,
                           maker_fee_rate_ppm,
                           taker_fee_rate_ppm,
                           quantity_steps,
                           remaining_quantity_steps,
                           reduce_only
                      FROM trading_orders
                     WHERE order_id IN (%s)
                    """.formatted(placeholders), rs -> {
                snapshots.put(rs.getLong("order_id"), new MatchedOrderSnapshot(
                        rs.getLong("instrument_version"),
                        MarginMode.fromNullableDbValue(rs.getString("margin_mode")),
                        PositionSide.fromNullableDbValue(rs.getString("position_side")),
                        rs.getLong("maker_fee_rate_ppm"),
                        rs.getLong("taker_fee_rate_ppm"),
                        rs.getLong("quantity_steps"),
                        rs.getLong("remaining_quantity_steps"),
                        rs.getBoolean("reduce_only")));
            }, batch.toArray());
        }
        if (snapshots.size() != uniqueIds.size()) {
            List<Long> missing = uniqueIds.stream().filter(id -> !snapshots.containsKey(id)).limit(10).toList();
            throw new IllegalStateException("maker order snapshots missing for orderIds=" + missing);
        }
        return Map.copyOf(snapshots);
    }

    public boolean saveResult(MatchResultEvent event) {
        int rows = jdbcTemplate.update("""
                INSERT INTO trading_match_results (
                    command_id, product_line, order_id, user_id, symbol, instrument_version, command_type, result_code,
                    filled_quantity_steps, order_status, trace_id, event_time, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (command_id) DO NOTHING
                """, event.commandId(), productLine(), event.orderId(), event.userId(), event.symbol(),
                event.instrumentVersion(), event.commandType().name(), event.resultCode(), event.filledQuantitySteps(),
                event.orderStatus().name(), event.traceId(), Timestamp.from(event.eventTime()));
        return rows == 1;
    }

    public void saveTrades(List<MatchTradeEvent> trades) {
        if (trades == null || trades.isEmpty()) {
            return;
        }
        int[] rows = jdbcTemplate.batchUpdate("""
                INSERT INTO trading_match_trades (
                    trade_id, command_id, product_line, symbol, taker_order_id, taker_instrument_version,
                    taker_user_id, taker_side, taker_margin_mode, taker_position_side,
                    maker_order_id, maker_instrument_version,
                    maker_user_id, maker_margin_mode, maker_position_side,
                    taker_fee_rate_ppm, maker_fee_rate_ppm, price_ticks, quantity_steps,
                    taker_order_completed, maker_order_completed, trace_id, event_time, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (product_line, symbol, trade_id) DO NOTHING
                """, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int index) throws java.sql.SQLException {
                MatchTradeEvent trade = trades.get(index);
                statement.setLong(1, trade.tradeId());
                statement.setLong(2, trade.commandId());
                statement.setString(3, productLine());
                statement.setString(4, trade.symbol());
                statement.setLong(5, trade.takerOrderId());
                statement.setLong(6, trade.takerInstrumentVersion());
                statement.setLong(7, trade.takerUserId());
                statement.setString(8, trade.takerSide().name());
                statement.setString(9, trade.takerMarginMode().name());
                statement.setString(10, trade.takerPositionSide().name());
                statement.setLong(11, trade.makerOrderId());
                statement.setLong(12, trade.makerInstrumentVersion());
                statement.setLong(13, trade.makerUserId());
                statement.setString(14, trade.makerMarginMode().name());
                statement.setString(15, trade.makerPositionSide().name());
                statement.setLong(16, trade.takerFeeRatePpm());
                statement.setLong(17, trade.makerFeeRatePpm());
                statement.setLong(18, trade.priceTicks());
                statement.setLong(19, trade.quantitySteps());
                statement.setBoolean(20, trade.takerOrderCompleted());
                statement.setBoolean(21, trade.makerOrderCompleted());
                statement.setString(22, trade.traceId());
                statement.setTimestamp(23, Timestamp.from(trade.eventTime()));
            }

            @Override
            public int getBatchSize() {
                return trades.size();
            }
        });
        requireCompleteBatch(rows, trades.size(), "match trades");
    }

    public void applyActiveOrderStatus(MatchResultEvent result) {
        if (result.commandType() == OrderCommandType.CANCEL) {
            if ("SUCCESS".equals(result.resultCode())) {
                updateOrderStatus(result.orderId(), OrderStatus.CANCELED, result.eventTime());
                clearRemainingQuantity(result.orderId(), result.eventTime());
            }
            return;
        }
        if (result.orderStatus() == OrderStatus.REJECTED) {
            int rows = jdbcTemplate.update("""
                    UPDATE trading_orders
                       SET status = 'REJECTED',
                           reject_reason = ?,
                           remaining_quantity_steps = 0,
                           updated_at = ?,
                           revision = revision + 1
                     WHERE order_id = ?
                    """, result.resultCode(), Timestamp.from(result.eventTime()), result.orderId());
            requireSingleRow(rows, "rejected order update");
            return;
        }
        if (result.filledQuantitySteps() > 0) {
            incrementOrderFill(result.orderId(), result.filledQuantitySteps(), result.orderStatus(), result.eventTime());
        } else {
            updateOrderStatus(result.orderId(), result.orderStatus(), result.eventTime());
        }
        if (isTerminal(result.orderStatus())) {
            if (result.orderStatus() == OrderStatus.CANCELED) {
                clearRemainingQuantity(result.orderId(), result.eventTime());
            }
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
                           status = i.status,
                           updated_at = i.event_time,
                           revision = o.revision + 1
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

    private void incrementOrderFill(long orderId, long quantitySteps, OrderStatus status, Instant now) {
        int rows = jdbcTemplate.update("""
                UPDATE trading_orders
                   SET executed_quantity_steps = executed_quantity_steps + ?,
                       remaining_quantity_steps = remaining_quantity_steps - ?,
                       status = ?,
                       updated_at = ?,
                       revision = revision + 1
                 WHERE order_id = ?
                   AND status IN ('ACCEPTED', 'PARTIALLY_FILLED', 'CANCEL_REQUESTED')
                   AND quantity_steps = executed_quantity_steps + remaining_quantity_steps
                   AND remaining_quantity_steps >= ?
                """, quantitySteps, quantitySteps, status.name(), Timestamp.from(now), orderId, quantitySteps);
        requireSingleRow(rows, "order fill update");
    }

    private void updateOrderStatus(long orderId, OrderStatus status, Instant now) {
        int rows = jdbcTemplate.update("""
                UPDATE trading_orders
                   SET status = ?,
                       updated_at = ?,
                       revision = revision + 1
                 WHERE order_id = ?
                """, status.name(), Timestamp.from(now), orderId);
        requireSingleRow(rows, "order status update");
    }

    private void clearRemainingQuantity(long orderId, Instant now) {
        int rows = jdbcTemplate.update("""
                UPDATE trading_orders
                   SET remaining_quantity_steps = 0,
                       updated_at = ?,
                       revision = revision + 1
                 WHERE order_id = ?
                """, Timestamp.from(now), orderId);
        requireSingleRow(rows, "order remaining quantity clear");
    }

    private boolean isTerminal(OrderStatus status) {
        return status == OrderStatus.CANCELED || status == OrderStatus.FILLED;
    }

    private String productLine() {
        MatchingProperties.Kafka kafka = properties.getKafka();
        return kafka.isProductTopicsEnabled()
                ? kafka.getProductLine().name()
                : ProductLine.LINEAR_PERPETUAL.name();
    }

    private void requireSingleRow(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException("failed to write " + operation);
        }
    }

    private void requireCompleteBatch(int[] rows, int expected, String operation) {
        if (rows == null || rows.length != expected) {
            throw new IllegalStateException("failed to write " + operation);
        }
        for (int row : rows) {
            if (row != 1 && row != Statement.SUCCESS_NO_INFO) {
                throw new IllegalStateException("failed to write " + operation);
            }
        }
    }

    public record CommandState(boolean resultExists, boolean orderExists) {
    }

    private record MakerFill(long quantitySteps, boolean completed, Instant eventTime) {
    }
}
