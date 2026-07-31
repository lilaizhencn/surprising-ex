package com.surprising.trading.order.repository;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.PositionSide;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 只减仓校验的开放订单仓储，只负责 {@code trading_orders} 表。
 */
@Repository
public class ReduceOnlyOpenOrderRepository {

    private final JdbcTemplate jdbcTemplate;

    public ReduceOnlyOpenOrderRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long lockedOpenReduceOnlySteps(ProductLine productLine,
                                          long userId,
                                          String symbol,
                                          MarginMode marginMode,
                                          long instrumentVersion,
                                          PositionSide positionSide,
                                          OrderSide closeSide) {
        return jdbcTemplate.query("""
                SELECT remaining_quantity_steps
                  FROM trading_orders
                 WHERE product_line = ?
                   AND user_id = ?
                   AND symbol = ?
                   AND margin_mode = ?
                   AND position_side = ?
                   AND instrument_version = ?
                   AND side = ?
                   AND reduce_only = TRUE
                   AND status IN ('ACCEPTED', 'PARTIALLY_FILLED', 'CANCEL_REQUESTED')
                FOR UPDATE
                """, (rs, rowNum) -> rs.getLong("remaining_quantity_steps"),
                productLine(productLine).name(), userId, symbol, MarginMode.defaultIfNull(marginMode).name(),
                PositionSide.defaultIfNull(positionSide).name(), instrumentVersion, closeSide.name())
                .stream()
                .mapToLong(Long::longValue)
                .reduce(0L, Math::addExact);
    }

    private ProductLine productLine(ProductLine productLine) {
        return productLine == null ? ProductLine.LINEAR_PERPETUAL : productLine;
    }
}
