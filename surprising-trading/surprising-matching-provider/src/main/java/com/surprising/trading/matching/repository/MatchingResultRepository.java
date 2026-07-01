package com.surprising.trading.matching.repository;

import com.surprising.trading.api.model.MatchResultEvent;
import com.surprising.trading.api.model.MatchTradeEvent;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderCommandType;
import com.surprising.trading.api.model.OrderStatus;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MatchingResultRepository {

    private final JdbcTemplate jdbcTemplate;
    private final MatchingMarginRepository marginRepository;

    public MatchingResultRepository(JdbcTemplate jdbcTemplate, MatchingMarginRepository marginRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.marginRepository = marginRepository;
    }

    public boolean commandResultExists(long commandId) {
        Boolean exists = jdbcTemplate.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM trading_match_results WHERE command_id = ?)
                """, Boolean.class, commandId);
        return Boolean.TRUE.equals(exists);
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

    public boolean saveResult(MatchResultEvent event) {
        int rows = jdbcTemplate.update("""
                INSERT INTO trading_match_results (
                    command_id, order_id, user_id, symbol, instrument_version, command_type, result_code,
                    filled_quantity_steps, order_status, trace_id, event_time, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (command_id) DO NOTHING
                """, event.commandId(), event.orderId(), event.userId(), event.symbol(),
                event.instrumentVersion(), event.commandType().name(), event.resultCode(), event.filledQuantitySteps(),
                event.orderStatus().name(), event.traceId(), Timestamp.from(event.eventTime()));
        return rows == 1;
    }

    public boolean saveTrade(MatchTradeEvent trade) {
        int rows = jdbcTemplate.update("""
                INSERT INTO trading_match_trades (
                    trade_id, command_id, symbol, taker_order_id, taker_instrument_version,
                    taker_user_id, taker_side, taker_margin_mode, maker_order_id, maker_instrument_version,
                    maker_user_id, maker_margin_mode, price_ticks, quantity_steps,
                    taker_order_completed, maker_order_completed, trace_id, event_time, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (symbol, trade_id) DO NOTHING
                """, trade.tradeId(), trade.commandId(), trade.symbol(), trade.takerOrderId(),
                trade.takerInstrumentVersion(), trade.takerUserId(), trade.takerSide().name(),
                trade.takerMarginMode().name(), trade.makerOrderId(), trade.makerInstrumentVersion(),
                trade.makerUserId(), trade.makerMarginMode().name(), trade.priceTicks(),
                trade.quantitySteps(), trade.takerOrderCompleted(), trade.makerOrderCompleted(),
                trade.traceId(), Timestamp.from(trade.eventTime()));
        return rows == 1;
    }

    public void applyActiveOrderStatus(MatchResultEvent result) {
        if (result.commandType() == OrderCommandType.CANCEL) {
            if ("SUCCESS".equals(result.resultCode())) {
                updateOrderStatus(result.orderId(), OrderStatus.CANCELED, result.eventTime());
                marginRepository.releaseUnused(result.orderId(), "ORDER_CANCELED", result.eventTime());
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
                           updated_at = ?
                     WHERE order_id = ?
                    """, result.resultCode(), Timestamp.from(result.eventTime()), result.orderId());
            requireSingleRow(rows, "rejected order update");
            marginRepository.releaseAll(result.orderId(), "ORDER_REJECTED", result.eventTime());
            return;
        }
        if (result.filledQuantitySteps() > 0) {
            incrementOrderFill(result.orderId(), result.filledQuantitySteps(), result.orderStatus(), result.eventTime());
        } else {
            updateOrderStatus(result.orderId(), result.orderStatus(), result.eventTime());
        }
        if (isTerminal(result.orderStatus())) {
            marginRepository.releaseUnused(result.orderId(), "ORDER_TERMINAL", result.eventTime());
            if (result.orderStatus() == OrderStatus.CANCELED) {
                clearRemainingQuantity(result.orderId(), result.eventTime());
            }
        }
    }

    public void applyMakerFill(MatchTradeEvent trade) {
        OrderStatus status = trade.makerOrderCompleted() ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
        incrementOrderFill(trade.makerOrderId(), trade.quantitySteps(), status, trade.eventTime());
    }

    private void incrementOrderFill(long orderId, long quantitySteps, OrderStatus status, Instant now) {
        int rows = jdbcTemplate.update("""
                UPDATE trading_orders
                   SET executed_quantity_steps = executed_quantity_steps + ?,
                       remaining_quantity_steps = remaining_quantity_steps - ?,
                       status = ?,
                       updated_at = ?
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
                       updated_at = ?
                 WHERE order_id = ?
                """, status.name(), Timestamp.from(now), orderId);
        requireSingleRow(rows, "order status update");
    }

    private void clearRemainingQuantity(long orderId, Instant now) {
        int rows = jdbcTemplate.update("""
                UPDATE trading_orders
                   SET remaining_quantity_steps = 0,
                       updated_at = ?
                 WHERE order_id = ?
                """, Timestamp.from(now), orderId);
        requireSingleRow(rows, "order remaining quantity clear");
    }

    private boolean isTerminal(OrderStatus status) {
        return status == OrderStatus.CANCELED || status == OrderStatus.FILLED;
    }

    private void requireSingleRow(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException("failed to write " + operation);
        }
    }
}
