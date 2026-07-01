package com.surprising.account.provider.service;

import com.surprising.account.provider.config.AccountProperties;
import com.surprising.account.provider.model.PositionState;
import com.surprising.trading.api.model.OrderCommandEvent;
import com.surprising.trading.api.model.OrderCommandType;
import com.surprising.trading.api.model.OrderEvent;
import com.surprising.trading.api.model.OrderEventType;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.TimeInForce;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class ReduceOnlyOrderPruner {

    private static final String REASON = "REDUCE_ONLY_POSITION_REDUCED";

    private final JdbcTemplate jdbcTemplate;
    private final AccountProperties properties;
    private final ObjectMapper objectMapper;

    public ReduceOnlyOrderPruner(JdbcTemplate jdbcTemplate, AccountProperties properties, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public void prune(long userId, String symbol, PositionState position, Instant now) {
        prune(userId, symbol, position, now, null);
    }

    public void prune(long userId, String symbol, PositionState position, Instant now, String traceId) {
        List<OpenReduceOnlyOrder> orders = lockOpenReduceOnlyOrders(userId, symbol);
        if (orders.isEmpty()) {
            return;
        }
        long capacity = Math.absExact(position.signedQuantitySteps());
        OrderSide closeSide = position.signedQuantitySteps() > 0
                ? OrderSide.SELL
                : position.signedQuantitySteps() < 0 ? OrderSide.BUY : null;
        long consumedCapacity = 0L;
        for (OpenReduceOnlyOrder order : orders) {
            boolean validCloseSide = closeSide != null
                    && order.side() == closeSide
                    && order.instrumentVersion() == position.instrumentVersion();
            boolean excessQuantity = false;
            if (validCloseSide) {
                consumedCapacity = Math.addExact(consumedCapacity, order.remainingQuantitySteps());
                excessQuantity = consumedCapacity > capacity;
            }
            if (!validCloseSide || excessQuantity) {
                requestReduceOnlyCancel(order, now, traceId);
            }
        }
    }

    private List<OpenReduceOnlyOrder> lockOpenReduceOnlyOrders(long userId, String symbol) {
        return jdbcTemplate.query("""
                SELECT order_id, user_id, client_order_id, symbol, instrument_version, side, order_type,
                       time_in_force, price_ticks, quantity_steps, remaining_quantity_steps, status, post_only,
                       created_at
                  FROM trading_orders
                 WHERE user_id = ?
                   AND symbol = ?
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
                rs.getLong("remaining_quantity_steps"),
                OrderStatus.valueOf(rs.getString("status")),
                rs.getBoolean("post_only"),
                rs.getTimestamp("created_at").toInstant()), userId, symbol);
    }

    private void requestReduceOnlyCancel(OpenReduceOnlyOrder order, Instant now, String traceId) {
        if (order.status() == OrderStatus.CANCEL_REQUESTED) {
            return;
        }
        int rows = jdbcTemplate.update("""
                UPDATE trading_orders
                   SET status = 'CANCEL_REQUESTED',
                       reject_reason = ?,
                       updated_at = ?
                 WHERE order_id = ?
                   AND status IN ('ACCEPTED', 'PARTIALLY_FILLED')
                """, REASON, Timestamp.from(now), order.orderId());
        if (rows != 1) {
            throw new IllegalStateException("failed to request reduce-only prune cancel " + order.orderId());
        }
        OrderEvent event = new OrderEvent(nextTradingSequence("event"), order.orderId(), order.userId(),
                order.symbol(), OrderEventType.CANCEL_REQUESTED, OrderStatus.CANCEL_REQUESTED, REASON, now, traceId);
        insertOrderEvent(event);
        enqueue("ORDER", order.orderId(), properties.getKafka().getOrderEventsTopic(), order.symbol(),
                OrderEventType.CANCEL_REQUESTED.name(), payload(event), now);
        OrderCommandEvent command = new OrderCommandEvent(OrderCommandType.CANCEL, nextTradingSequence("command"),
                order.orderId(), order.userId(), order.clientOrderId(), order.symbol(), order.instrumentVersion(),
                order.side(), order.orderType(), order.timeInForce(), order.priceTicks(), order.quantitySteps(),
                true, order.postOnly(), now, traceId);
        enqueue("ORDER", order.orderId(), properties.getKafka().getOrderCommandsTopic(), order.symbol(),
                OrderCommandType.CANCEL.name(), payload(command), now);
    }

    private long nextTradingSequence(String sequenceName) {
        Long value = jdbcTemplate.queryForObject("""
                INSERT INTO trading_sequences (sequence_name, sequence_value, updated_at)
                VALUES (?, 1, now())
                ON CONFLICT (sequence_name) DO UPDATE SET
                    sequence_value = trading_sequences.sequence_value + 1,
                    updated_at = now()
                RETURNING sequence_value
                """, Long.class, sequenceName);
        if (value == null) {
            throw new IllegalStateException("failed to allocate trading sequence " + sequenceName);
        }
        return value;
    }

    private void insertOrderEvent(OrderEvent event) {
        int rows = jdbcTemplate.update("""
                INSERT INTO trading_order_events (
                    event_id, order_id, user_id, symbol, event_type, status, reason, trace_id, event_time
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """, event.eventId(), event.orderId(), event.userId(), event.symbol(), event.eventType().name(),
                event.status().name(), event.reason(), event.traceId(), Timestamp.from(event.eventTime()));
        if (rows != 1) {
            throw new IllegalStateException("failed to insert reduce-only prune order event " + event.eventId());
        }
    }

    private void enqueue(String aggregateType,
                         long aggregateId,
                         String topic,
                         String eventKey,
                         String eventType,
                         String payload,
                         Instant now) {
        long outboxId = nextTradingSequence("outbox");
        int rows = jdbcTemplate.update("""
                INSERT INTO trading_outbox_events (
                    id, aggregate_type, aggregate_id, topic, event_key, event_type,
                    payload, next_attempt_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """, outboxId, aggregateType, aggregateId, topic, eventKey, eventType, payload,
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        if (rows != 1) {
            throw new IllegalStateException("failed to enqueue reduce-only prune outbox event " + outboxId);
        }
    }

    private String payload(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new IllegalStateException("failed to serialize reduce-only prune event", ex);
        }
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
            long remainingQuantitySteps,
            OrderStatus status,
            boolean postOnly,
            Instant createdAt) {
    }
}
