package com.surprising.liquidation.provider.repository;

import com.surprising.liquidation.provider.config.LiquidationProperties;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TimeInForce;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 强平交易订单仓储，只负责 {@code trading_orders} 表。 */
@Repository
public class LiquidationOrderRepository {

    private final JdbcTemplate jdbcTemplate;
    private final LiquidationProperties properties;

    public LiquidationOrderRepository(JdbcTemplate jdbcTemplate, LiquidationProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties == null ? new LiquidationProperties() : properties;
    }

    public void insertAll(List<NewLiquidationOrder> orders) {
        if (orders == null || orders.isEmpty()) {
            return;
        }
        int[] rows = jdbcTemplate.batchUpdate("""
                INSERT INTO trading_orders (
                    order_id, product_line, user_id, client_order_id, symbol, instrument_version, side,
                    order_type, time_in_force, price_ticks, quantity_steps, executed_quantity_steps,
                    remaining_quantity_steps, margin_mode, position_side, maker_fee_rate_ppm,
                    taker_fee_rate_ppm, reduce_only, post_only, status, reject_reason, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'MARKET', 'IOC', 0, ?, 0, ?, ?, ?, ?, ?,
                    TRUE, FALSE, 'ACCEPTED', NULL, ?, ?)
                """, orders.stream().map(order -> new Object[]{
                order.orderId(), order.productLine().name(), order.userId(), order.clientOrderId(), order.symbol(),
                order.instrumentVersion(), order.side().name(), order.quantitySteps(), order.quantitySteps(),
                order.marginMode().name(), order.positionSide().name(), order.makerFeeRatePpm(),
                order.takerFeeRatePpm(), Timestamp.from(order.createdAt()), Timestamp.from(order.createdAt())
        }).toList());
        requireBatchRows(rows, orders.size(), "批量写入强平交易订单");
    }

    public List<OpenReduceOnlyOrder> lockOpenReduceOnlyCloseOrders(List<OrderScope> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return List.of();
        }
        String values = String.join(", ", Collections.nCopies(scopes.size(),
                "(?, ?, ?, ?, ?, ?, CAST(? AS timestamptz))"));
        List<Object> args = new ArrayList<>(scopes.size() * 7 + 1);
        for (OrderScope scope : scopes) {
            args.add(scope.userId());
            args.add(scope.symbol());
            args.add(scope.marginMode().name());
            args.add(scope.positionSide().name());
            args.add(scope.instrumentVersion());
            args.add(scope.side().name());
            args.add(Timestamp.from(scope.preemptedAt()));
        }
        args.add(currentProductLine().name());
        return jdbcTemplate.query("""
                WITH requested_input(user_id, symbol, margin_mode, position_side, instrument_version, side,
                                     preempted_at) AS (
                    VALUES %s
                ),
                requested AS (
                    SELECT user_id, symbol, margin_mode, position_side, instrument_version, side,
                           min(preempted_at) AS preempted_at
                      FROM requested_input
                     GROUP BY user_id, symbol, margin_mode, position_side, instrument_version, side
                )
                SELECT o.order_id, o.user_id, o.client_order_id, o.symbol, o.instrument_version, o.side,
                       o.order_type, o.time_in_force, o.price_ticks, o.quantity_steps,
                       o.margin_mode, o.position_side, o.status, o.maker_fee_rate_ppm, o.taker_fee_rate_ppm,
                       o.post_only, r.preempted_at
                  FROM requested r
                  JOIN trading_orders o
                    ON o.user_id = r.user_id
                   AND o.symbol = r.symbol
                   AND o.margin_mode = r.margin_mode
                   AND o.position_side = r.position_side
                   AND o.instrument_version = r.instrument_version
                   AND o.side = r.side
                 WHERE o.product_line = ?
                   AND o.reduce_only = TRUE
                   AND o.status IN ('ACCEPTED', 'PARTIALLY_FILLED', 'CANCEL_REQUESTED')
                   AND o.remaining_quantity_steps > 0
                 ORDER BY o.created_at ASC, o.order_id ASC
                 FOR UPDATE OF o
                """.formatted(values), (rs, rowNum) -> new OpenReduceOnlyOrder(
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
                PositionSide.fromNullableDbValue(rs.getString("position_side")),
                OrderStatus.valueOf(rs.getString("status")),
                rs.getLong("maker_fee_rate_ppm"),
                rs.getLong("taker_fee_rate_ppm"),
                rs.getBoolean("post_only"),
                rs.getTimestamp("preempted_at").toInstant()), args.toArray());
    }

    public void requestCancel(long orderId, String reason, Instant now) {
        int rows = jdbcTemplate.update("""
                UPDATE trading_orders
                   SET status = 'CANCEL_REQUESTED',
                       reject_reason = ?,
                       updated_at = ?,
                       revision = revision + 1
                 WHERE order_id = ?
                   AND status IN ('ACCEPTED', 'PARTIALLY_FILLED')
                """, reason, Timestamp.from(now), orderId);
        requireSingleRow(rows, "更新强平抢占撤单状态");
    }

    private ProductLine currentProductLine() {
        return properties.getKafka().isProductTopicsEnabled()
                ? properties.getKafka().getProductLine()
                : ProductLine.LINEAR_PERPETUAL;
    }

    private void requireSingleRow(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException(operation + "失败");
        }
    }

    private void requireBatchRows(int[] rows, int expected, String operation) {
        if (rows.length != expected) {
            throw new IllegalStateException(operation + "结果数量不一致");
        }
        for (int row : rows) {
            if (row != 1 && row != Statement.SUCCESS_NO_INFO) {
                throw new IllegalStateException(operation + "失败");
            }
        }
    }

    public record NewLiquidationOrder(long orderId,
                                      ProductLine productLine,
                                      long userId,
                                      String clientOrderId,
                                      String symbol,
                                      long instrumentVersion,
                                      OrderSide side,
                                      long quantitySteps,
                                      MarginMode marginMode,
                                      PositionSide positionSide,
                                      long makerFeeRatePpm,
                                      long takerFeeRatePpm,
                                      Instant createdAt) {
        public NewLiquidationOrder {
            marginMode = MarginMode.defaultIfNull(marginMode);
            positionSide = PositionSide.defaultIfNull(positionSide);
        }
    }

    public record OrderScope(long userId,
                             String symbol,
                             MarginMode marginMode,
                             PositionSide positionSide,
                             long instrumentVersion,
                             OrderSide side,
                             Instant preemptedAt) {
        public OrderScope {
            marginMode = MarginMode.defaultIfNull(marginMode);
            positionSide = PositionSide.defaultIfNull(positionSide);
        }
    }

    public record OpenReduceOnlyOrder(long orderId,
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
                                      PositionSide positionSide,
                                      OrderStatus status,
                                      long makerFeeRatePpm,
                                      long takerFeeRatePpm,
                                      boolean postOnly,
                                      Instant preemptedAt) {
        public OpenReduceOnlyOrder {
            marginMode = MarginMode.defaultIfNull(marginMode);
            positionSide = PositionSide.defaultIfNull(positionSide);
        }
    }
}
