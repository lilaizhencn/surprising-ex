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
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TimeInForce;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
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
        prune(userId, symbol, PositionSide.NET, position, now, null);
    }

    public void prune(long userId, String symbol, PositionState position, Instant now, String traceId) {
        prune(userId, symbol, PositionSide.NET, position, now, traceId);
    }

    public void prune(long userId, String symbol, PositionSide positionSide, PositionState position, Instant now,
                      String traceId) {
        List<OpenReduceOnlyOrder> orders = lockOpenReduceOnlyOrders(userId, symbol, positionSide);
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

    private List<OpenReduceOnlyOrder> lockOpenReduceOnlyOrders(long userId, String symbol, PositionSide positionSide) {
        StringBuilder sql = new StringBuilder("""
                SELECT o.order_id, o.user_id, o.client_order_id, o.symbol, o.instrument_version, o.side,
                       o.order_type, o.time_in_force, o.price_ticks, o.quantity_steps,
                       o.remaining_quantity_steps, o.status, o.post_only, o.created_at
                  FROM trading_orders o
                """);
        List<Object> args = new ArrayList<>();
        if (properties.getKafka().isProductTopicsEnabled()) {
            sql.append("""
                  JOIN instruments i
                    ON i.symbol = o.symbol
                   AND i.version = o.instrument_version
                """);
        }
        sql.append("""
                 WHERE o.user_id = ?
                   AND o.symbol = ?
                   AND o.position_side = ?
                   AND o.reduce_only = TRUE
                   AND o.status IN ('ACCEPTED', 'PARTIALLY_FILLED', 'CANCEL_REQUESTED')
                   AND o.remaining_quantity_steps > 0
                """);
        args.add(userId);
        args.add(symbol);
        args.add(PositionSide.defaultIfNull(positionSide).name());
        if (properties.getKafka().isProductTopicsEnabled()) {
            sql.append("   AND i.contract_type = ?\n");
            args.add(properties.getKafka().getProductLine().contractTypeCode());
        }
        sql.append("""
                 ORDER BY o.created_at ASC, o.order_id ASC
                 FOR UPDATE
                """);
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new OpenReduceOnlyOrder(
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
                rs.getTimestamp("created_at").toInstant()), args.toArray());
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
        Long value = jdbcTemplate.queryForObject("SELECT nextval(CAST(? AS regclass))", Long.class,
                tradingSequenceIdentifier(sequenceName));
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

    private String tradingSequenceIdentifier(String sequenceName) {
        if (sequenceName == null || !sequenceName.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("invalid trading sequence name: " + sequenceName);
        }
        return "public.trading_" + sequenceName.toLowerCase().replace('-', '_') + "_seq";
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
