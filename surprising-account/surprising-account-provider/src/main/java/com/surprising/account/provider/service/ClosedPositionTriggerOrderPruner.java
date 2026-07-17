package com.surprising.account.provider.service;

import com.surprising.account.provider.config.AccountProperties;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.api.model.TriggerCondition;
import com.surprising.trading.api.model.TriggerOrderResponse;
import com.surprising.trading.api.model.TriggerOrderStatus;
import com.surprising.trading.api.model.TriggerOrderType;
import com.surprising.trading.api.model.TriggerOrderUpdatedEvent;
import com.surprising.trading.api.model.TriggerPriceType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Cancels passive close triggers in the same database transaction that closes their position. */
@Service
public class ClosedPositionTriggerOrderPruner {

    public static final String REASON = "POSITION_CLOSED";

    private final JdbcTemplate jdbcTemplate;
    private final AccountProperties properties;
    private final ObjectMapper objectMapper;

    public ClosedPositionTriggerOrderPruner(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new AccountProperties(), null);
    }

    @Autowired
    public ClosedPositionTriggerOrderPruner(JdbcTemplate jdbcTemplate,
                                            AccountProperties properties,
                                            ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public int prune(ProductLine productLine,
                     long userId,
                     String symbol,
                     MarginMode marginMode,
                     PositionSide positionSide,
                     Instant closedAt) {
        List<TriggerOrderResponse> canceled = jdbcTemplate.query("""
                UPDATE trading_trigger_orders
                   SET status = 'CANCELED',
                       reject_reason = ?,
                       updated_at = ?
                 WHERE product_line = ?
                   AND user_id = ?
                   AND symbol = ?
                   AND margin_mode = ?
                   AND position_side = ?
                   AND status = 'PENDING'
             RETURNING *
                """, (rs, rowNum) -> toResponse(rs), REASON, Timestamp.from(closedAt), productLine.name(), userId, symbol,
                MarginMode.defaultIfNull(marginMode).name(), PositionSide.defaultIfNull(positionSide).name());
        for (TriggerOrderResponse order : canceled) {
            enqueue(productLine, order, closedAt);
        }
        return canceled.size();
    }

    private void enqueue(ProductLine productLine, TriggerOrderResponse order, Instant eventTime) {
        if (objectMapper == null) {
            throw new IllegalStateException("objectMapper is required to publish closed-position trigger updates");
        }
        long eventId = nextTradingSequence("event");
        TriggerOrderUpdatedEvent event = new TriggerOrderUpdatedEvent(
                eventId, productLine, order, eventTime, order.traceId());
        long outboxId = nextTradingSequence("outbox");
        int rows = jdbcTemplate.update("""
                INSERT INTO trading_outbox_events (
                    id, aggregate_type, aggregate_id, topic, event_key, event_type,
                    payload, next_attempt_at, created_at, updated_at
                ) VALUES (?, 'TRIGGER_ORDER', ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """, outboxId, order.triggerOrderId(), properties.getKafka().getTriggerOrderEventsTopic(),
                order.symbol(), order.status().name(), payload(event), Timestamp.from(eventTime),
                Timestamp.from(eventTime), Timestamp.from(eventTime));
        if (rows != 1) {
            throw new IllegalStateException("failed to enqueue closed-position trigger update "
                    + order.triggerOrderId());
        }
    }

    private long nextTradingSequence(String sequenceName) {
        Long value = jdbcTemplate.queryForObject("SELECT nextval(CAST(? AS regclass))", Long.class,
                "public.trading_" + sequenceName + "_seq");
        if (value == null) {
            throw new IllegalStateException("failed to allocate trading sequence " + sequenceName);
        }
        return value;
    }

    private String payload(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new IllegalStateException("failed to serialize closed-position trigger update", ex);
        }
    }

    private TriggerOrderResponse toResponse(ResultSet rs) throws SQLException {
        return new TriggerOrderResponse(
                rs.getLong("trigger_order_id"),
                rs.getLong("user_id"),
                stringOrNull(rs, "client_trigger_order_id"),
                stringOrNull(rs, "oco_group_id"),
                rs.getString("symbol"),
                OrderSide.valueOf(rs.getString("side")),
                TriggerOrderType.valueOf(rs.getString("trigger_type")),
                TriggerPriceType.valueOf(rs.getString("trigger_price_type")),
                TriggerCondition.valueOf(rs.getString("trigger_condition")),
                rs.getLong("trigger_price_ticks"),
                longOrNull(rs, "activation_price_ticks"),
                longOrNull(rs, "callback_rate_ppm"),
                longOrNull(rs, "highest_price_ticks"),
                longOrNull(rs, "lowest_price_ticks"),
                instantOrNull(rs, "activated_at"),
                OrderType.valueOf(rs.getString("order_type")),
                TimeInForce.valueOf(rs.getString("time_in_force")),
                rs.getLong("price_ticks"),
                rs.getLong("quantity_steps"),
                MarginMode.fromNullableDbValue(rs.getString("margin_mode")),
                PositionSide.fromNullableDbValue(rs.getString("position_side")),
                TriggerOrderStatus.valueOf(rs.getString("status")),
                longOrNull(rs, "placed_order_id"),
                longOrNull(rs, "trigger_sequence"),
                longOrNull(rs, "triggered_price_ticks"),
                stringOrNull(rs, "reject_reason"),
                stringOrNull(rs, "trace_id"),
                instantOrNull(rs, "expires_at"),
                instantOrNull(rs, "triggered_at"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static Instant instantOrNull(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static String stringOrNull(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        return rs.wasNull() ? null : value;
    }

    private static Long longOrNull(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
