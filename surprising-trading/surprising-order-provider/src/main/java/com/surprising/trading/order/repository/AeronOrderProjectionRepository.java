package com.surprising.trading.order.repository;

import com.surprising.aeron.protocol.CoreStateQueryCodec;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderResponse;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TimeInForce;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AeronOrderProjectionRepository {

    private final JdbcTemplate jdbcTemplate;

    public AeronOrderProjectionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<OrderResponse> query(ProductLine productLine, Long userId, String symbol, OrderStatus status,
                                     Long orderId, Long beforeOrderId, Long minimumOrderId,
                                     Instant startTime, Instant endTime, int limit, boolean ascending) {
        StringBuilder sql = new StringBuilder("""
                SELECT raw_order_state FROM core_order_projection WHERE product_line = ?
                """);
        List<Object> arguments = new ArrayList<>();
        arguments.add(productLine.name());
        append(sql, arguments, " AND user_id = ?", userId);
        append(sql, arguments, " AND symbol = ?", symbol);
        append(sql, arguments, " AND status = ?", status == null ? null : coreStatus(status));
        append(sql, arguments, " AND order_id = ?", orderId);
        append(sql, arguments, " AND order_id < ?", beforeOrderId);
        append(sql, arguments, " AND order_id >= ?", minimumOrderId);
        append(sql, arguments, " AND created_at_epoch_ms >= ?", startTime == null ? null : startTime.toEpochMilli());
        append(sql, arguments, " AND created_at_epoch_ms <= ?", endTime == null ? null : endTime.toEpochMilli());
        sql.append(" ORDER BY order_id ").append(ascending ? "ASC" : "DESC").append(" LIMIT ?");
        arguments.add(limit);
        return jdbcTemplate.query(sql.toString(), (result, row) -> map(result.getBytes(1)), arguments.toArray());
    }

    private static void append(StringBuilder sql, List<Object> arguments, String clause, Object value) {
        if (value != null) {
            sql.append(clause);
            arguments.add(value);
        }
    }

    private static String coreStatus(OrderStatus status) {
        return switch (status) {
            case ACCEPTED, PARTIALLY_FILLED -> "OPEN";
            default -> status.name();
        };
    }

    private static OrderResponse map(byte[] raw) {
        var view = CoreStateQueryCodec.decodeOrderState(raw);
        OrderStatus status = "OPEN".equals(view.status())
                ? (view.executedQuantitySteps() == 0 ? OrderStatus.ACCEPTED : OrderStatus.PARTIALLY_FILLED)
                : OrderStatus.valueOf(view.status());
        return new OrderResponse(view.orderId(), view.userId(), emptyToNull(view.clientOrderId()), view.symbol(),
                view.instrumentVersion(), OrderSide.valueOf(view.side().name()), OrderType.valueOf(view.orderType().name()),
                TimeInForce.valueOf(view.timeInForce().name()), view.priceTicks(), view.quantitySteps(),
                view.executedQuantitySteps(), view.remainingQuantitySteps(), MarginMode.valueOf(view.marginMode().name()),
                PositionSide.valueOf(view.positionSide().name()), view.makerFeeRatePpm(), view.takerFeeRatePpm(),
                view.reduceOnly(), view.postOnly(), status, null, Instant.ofEpochMilli(view.createdAtEpochMillis()),
                Instant.ofEpochMilli(view.updatedAtEpochMillis()));
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
