package com.surprising.liquidation.provider.repository;

import com.surprising.liquidation.provider.config.LiquidationProperties;
import com.surprising.liquidation.provider.model.TradingOutboxRecord;
import com.surprising.trading.api.model.OrderCommandEvent;
import com.surprising.trading.api.model.OrderCommandType;
import com.surprising.trading.api.model.OrderEvent;
import com.surprising.trading.api.model.OrderEventType;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.TimeInForce;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LiquidationOrderRepository {

    private static final String REDUCE_ONLY_PREEMPT_REASON = "LIQUIDATION_PREEMPTED_REDUCE_ONLY";

    private final JdbcTemplate jdbcTemplate;
    private final LiquidationSequenceRepository sequenceRepository;
    private final LiquidationProperties properties;

    public LiquidationOrderRepository(JdbcTemplate jdbcTemplate,
                                      LiquidationSequenceRepository sequenceRepository,
                                      LiquidationProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.sequenceRepository = sequenceRepository;
        this.properties = properties;
    }

    public OrderCommandEvent createReduceOnlyMarketOrder(long candidateId,
                                                         long userId,
                                                         String symbol,
                                                         MarginMode marginMode,
                                                         long instrumentVersion,
                                                         OrderSide side,
                                                         long quantitySteps,
                                                         Instant now,
                                                         java.util.function.Function<Object, String> serializer) {
        long orderId = sequenceRepository.nextTradingSequence("order");
        long eventId = sequenceRepository.nextTradingSequence("event");
        long commandId = sequenceRepository.nextTradingSequence("command");
        String clientOrderId = "LIQ-" + candidateId;
        FeeSnapshot feeSnapshot = feeSnapshot(userId, symbol, instrumentVersion, now);
        int orderRows = jdbcTemplate.update("""
                INSERT INTO trading_orders (
                    order_id, user_id, client_order_id, symbol, instrument_version, side, order_type, time_in_force,
                    price_ticks, quantity_steps, executed_quantity_steps, remaining_quantity_steps,
                    margin_mode, maker_fee_rate_ppm, taker_fee_rate_ppm,
                    reduce_only, post_only, status, reject_reason, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'MARKET', 'IOC', 0, ?, 0, ?, ?, ?, ?,
                    TRUE, FALSE, 'ACCEPTED', NULL, ?, ?)
                """, orderId, userId, clientOrderId, symbol, instrumentVersion, side.name(), quantitySteps, quantitySteps,
                MarginMode.defaultIfNull(marginMode).name(), feeSnapshot.makerFeeRatePpm(), feeSnapshot.takerFeeRatePpm(),
                Timestamp.from(now), Timestamp.from(now));
        if (orderRows != 1) {
            throw new IllegalStateException("failed to create liquidation trading order");
        }
        OrderEvent event = new OrderEvent(eventId, orderId, userId, symbol, OrderEventType.ACCEPTED,
                OrderStatus.ACCEPTED, "LIQUIDATION", now);
        int eventRows = jdbcTemplate.update("""
                INSERT INTO trading_order_events (
                    event_id, order_id, user_id, symbol, event_type, status, reason, event_time
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """, event.eventId(), event.orderId(), event.userId(), event.symbol(), event.eventType().name(),
                event.status().name(), event.reason(), Timestamp.from(event.eventTime()));
        if (eventRows != 1) {
            throw new IllegalStateException("failed to create liquidation order event");
        }
        OrderCommandEvent command = new OrderCommandEvent(OrderCommandType.PLACE, commandId, orderId, userId,
                clientOrderId, symbol, instrumentVersion, side, OrderType.MARKET, TimeInForce.IOC, 0L, quantitySteps,
                MarginMode.defaultIfNull(marginMode), true, false, now, null);
        enqueue("LIQUIDATION_ORDER", event.orderId(), properties.getKafka().getOrderEventsTopic(), symbol,
                OrderEventType.ACCEPTED.name(), serializer.apply(event), now);
        enqueue("LIQUIDATION_ORDER", orderId, properties.getKafka().getOrderCommandsTopic(), symbol,
                OrderCommandType.PLACE.name(), serializer.apply(command), now);
        return command;
    }

    public OrderCommandEvent createReduceOnlyMarketOrder(long candidateId,
                                                         long userId,
                                                         String symbol,
                                                         long instrumentVersion,
                                                         OrderSide side,
                                                         long quantitySteps,
                                                         Instant now,
                                                         java.util.function.Function<Object, String> serializer) {
        return createReduceOnlyMarketOrder(candidateId, userId, symbol, MarginMode.CROSS, instrumentVersion, side,
                quantitySteps, now, serializer);
    }

    private FeeSnapshot feeSnapshot(long userId, String symbol, long instrumentVersion, Instant now) {
        return jdbcTemplate.query("""
                WITH instrument_fee AS (
                    SELECT maker_fee_rate_ppm, taker_fee_rate_ppm
                      FROM instruments
                     WHERE symbol = ?
                       AND version = ?
                ),
                active_user_fee AS (
                    SELECT maker_fee_rate_ppm,
                           taker_fee_rate_ppm,
                           CASE WHEN symbol = ? THEN 0 ELSE 1 END AS priority,
                           effective_time,
                           fee_schedule_id
                      FROM trading_fee_schedules
                     WHERE user_id = ?
                       AND status = 'ACTIVE'
                       AND (symbol = ? OR symbol IS NULL)
                       AND effective_time <= ?
                       AND (expire_time IS NULL OR expire_time > ?)
                     ORDER BY priority ASC, effective_time DESC, fee_schedule_id DESC
                     LIMIT 1
                )
                SELECT COALESCE(u.maker_fee_rate_ppm, i.maker_fee_rate_ppm) AS maker_fee_rate_ppm,
                       COALESCE(u.taker_fee_rate_ppm, i.taker_fee_rate_ppm) AS taker_fee_rate_ppm
                  FROM instrument_fee i
             LEFT JOIN active_user_fee u ON TRUE
                """, (rs, rowNum) -> new FeeSnapshot(
                rs.getLong("maker_fee_rate_ppm"),
                rs.getLong("taker_fee_rate_ppm")), symbol, instrumentVersion, symbol, userId, symbol,
                Timestamp.from(now), Timestamp.from(now)).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("fee schedule unavailable for liquidation order"));
    }

    public int cancelOpenReduceOnlyCloseOrders(long userId,
                                               String symbol,
                                               MarginMode marginMode,
                                               long instrumentVersion,
                                               OrderSide closeSide,
                                               Instant now,
                                               java.util.function.Function<Object, String> serializer) {
        List<OpenReduceOnlyOrder> orders = lockOpenReduceOnlyCloseOrders(userId, symbol, marginMode,
                instrumentVersion, closeSide);
        for (OpenReduceOnlyOrder order : orders) {
            if (order.status() != OrderStatus.CANCEL_REQUESTED) {
                requestCancel(order, now, serializer);
            }
            enqueueCancelCommand(order, now, serializer);
        }
        return orders.size();
    }

    public int cancelOpenReduceOnlyCloseOrders(long userId,
                                               String symbol,
                                               long instrumentVersion,
                                               OrderSide closeSide,
                                               Instant now,
                                               java.util.function.Function<Object, String> serializer) {
        return cancelOpenReduceOnlyCloseOrders(userId, symbol, MarginMode.CROSS, instrumentVersion, closeSide, now,
                serializer);
    }

    private List<OpenReduceOnlyOrder> lockOpenReduceOnlyCloseOrders(long userId,
                                                                    String symbol,
                                                                    MarginMode marginMode,
                                                                    long instrumentVersion,
                                                                    OrderSide closeSide) {
        return jdbcTemplate.query("""
                SELECT order_id, user_id, client_order_id, symbol, instrument_version, side, order_type,
                       time_in_force, price_ticks, quantity_steps, remaining_quantity_steps, margin_mode, status, post_only
                  FROM trading_orders
                 WHERE user_id = ?
                   AND symbol = ?
                   AND margin_mode = ?
                   AND instrument_version = ?
                   AND side = ?
                   AND reduce_only = TRUE
                   AND status IN ('ACCEPTED', 'PARTIALLY_FILLED', 'CANCEL_REQUESTED')
                   AND remaining_quantity_steps > 0
                 ORDER BY created_at ASC, order_id ASC
                 FOR UPDATE
                """, (rs, rowNum) -> new OpenReduceOnlyOrder(
                rs.getLong("order_id"),
                rs.getLong("user_id"),
                rs.getString("client_order_id"),
                rs.getString("symbol"),
                rs.getLong("instrument_version"),
                OrderSide.valueOf(rs.getString("side")),
                OrderType.valueOf(rs.getString("order_type")),
                TimeInForce.valueOf(rs.getString("time_in_force")),
                rs.getLong("price_ticks"),
                rs.getLong("quantity_steps"),
                MarginMode.fromNullableDbValue(rs.getString("margin_mode")),
                OrderStatus.valueOf(rs.getString("status")),
                rs.getBoolean("post_only")), userId, symbol, MarginMode.defaultIfNull(marginMode).name(),
                instrumentVersion, closeSide.name());
    }

    private void requestCancel(OpenReduceOnlyOrder order,
                               Instant now,
                               java.util.function.Function<Object, String> serializer) {
        int rows = jdbcTemplate.update("""
                UPDATE trading_orders
                   SET status = 'CANCEL_REQUESTED',
                       reject_reason = ?,
                       updated_at = ?
                 WHERE order_id = ?
                   AND status IN ('ACCEPTED', 'PARTIALLY_FILLED')
                """, REDUCE_ONLY_PREEMPT_REASON, Timestamp.from(now), order.orderId());
        requireSingleRow(rows, "liquidation reduce-only preempt cancel request");

        OrderEvent event = new OrderEvent(sequenceRepository.nextTradingSequence("event"), order.orderId(),
                order.userId(), order.symbol(), OrderEventType.CANCEL_REQUESTED, OrderStatus.CANCEL_REQUESTED,
                REDUCE_ONLY_PREEMPT_REASON, now);
        int eventRows = jdbcTemplate.update("""
                INSERT INTO trading_order_events (
                    event_id, order_id, user_id, symbol, event_type, status, reason, event_time
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """, event.eventId(), event.orderId(), event.userId(), event.symbol(), event.eventType().name(),
                event.status().name(), event.reason(), Timestamp.from(event.eventTime()));
        requireSingleRow(eventRows, "liquidation reduce-only preempt order event");
        enqueue("ORDER", order.orderId(), properties.getKafka().getOrderEventsTopic(), order.symbol(),
                OrderEventType.CANCEL_REQUESTED.name(), serializer.apply(event), now);
    }

    private void enqueueCancelCommand(OpenReduceOnlyOrder order,
                                      Instant now,
                                      java.util.function.Function<Object, String> serializer) {
        OrderCommandEvent command = new OrderCommandEvent(OrderCommandType.CANCEL,
                sequenceRepository.nextTradingSequence("command"), order.orderId(), order.userId(),
                order.clientOrderId(), order.symbol(), order.instrumentVersion(), order.side(), order.orderType(),
                order.timeInForce(), order.priceTicks(), order.quantitySteps(), order.marginMode(), true,
                order.postOnly(), now, null);
        enqueue("ORDER", order.orderId(), properties.getKafka().getOrderCommandsTopic(), order.symbol(),
                OrderCommandType.CANCEL.name(), serializer.apply(command), now);
    }

    public List<TradingOutboxRecord> lockPending(int limit) {
        return jdbcTemplate.query("""
                SELECT id, topic, event_key, payload::text AS payload
                  FROM trading_outbox_events
                 WHERE published_at IS NULL
                   AND next_attempt_at <= now()
                 ORDER BY next_attempt_at ASC, id ASC
                 LIMIT ?
                 FOR UPDATE SKIP LOCKED
                """, (rs, rowNum) -> new TradingOutboxRecord(
                rs.getLong("id"),
                rs.getString("topic"),
                rs.getString("event_key"),
                rs.getString("payload")), limit);
    }

    public void markPublished(long id, Instant now) {
        int rows = jdbcTemplate.update("""
                UPDATE trading_outbox_events
                   SET published_at = ?,
                       updated_at = ?,
                       last_error = NULL
                 WHERE id = ?
                """, Timestamp.from(now), Timestamp.from(now), id);
        requireSingleRow(rows, "liquidation outbox publish mark");
    }

    public void markFailed(long id, String error, Instant now) {
        int rows = jdbcTemplate.update("""
                UPDATE trading_outbox_events
                   SET attempts = attempts + 1,
                       last_error = ?,
                       next_attempt_at = ? + (CAST(power(2, LEAST(attempts, 6)) AS INTEGER) * INTERVAL '1 second'),
                       updated_at = ?
                 WHERE id = ?
                """, truncate(error), Timestamp.from(now), Timestamp.from(now), id);
        requireSingleRow(rows, "liquidation outbox failure mark");
    }

    private void enqueue(String aggregateType,
                         long aggregateId,
                         String topic,
                         String key,
                         String eventType,
                         String payload,
                         Instant now) {
        long outboxId = sequenceRepository.nextTradingSequence("outbox");
        int rows = jdbcTemplate.update("""
                INSERT INTO trading_outbox_events (
                    id, aggregate_type, aggregate_id, topic, event_key, event_type,
                    payload, next_attempt_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """, outboxId, aggregateType, aggregateId, topic, key, eventType, payload,
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        requireSingleRow(rows, "liquidation outbox enqueue");
    }

    private record OpenReduceOnlyOrder(
            long orderId,
            long userId,
            String clientOrderId,
            String symbol,
            long instrumentVersion,
            OrderSide side,
            OrderType orderType,
            TimeInForce timeInForce,
            long priceTicks,
            long quantitySteps,
            MarginMode marginMode,
            OrderStatus status,
            boolean postOnly) {
        private OpenReduceOnlyOrder {
            marginMode = MarginMode.defaultIfNull(marginMode);
        }
    }

    private record FeeSnapshot(long makerFeeRatePpm, long takerFeeRatePpm) {
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    private void requireSingleRow(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException("failed to write " + operation);
        }
    }
}
