package com.surprising.trading.trigger.repository;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.PositionSide;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 触发单普通委托仓储，只负责 {@code trading_orders} 表。
 */
@Repository
public class TriggerOpenOrderRepository {

    private final JdbcTemplate jdbcTemplate;

    public TriggerOpenOrderRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean hasActiveMarginModeConflict(ProductLine productLine,
                                               long userId,
                                               String symbol,
                                               MarginMode marginMode) {
        Boolean conflict = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM trading_orders
                     WHERE product_line = ?
                       AND user_id = ?
                       AND symbol = ?
                       AND margin_mode <> ?
                       AND status IN ('ACCEPTED', 'PARTIALLY_FILLED', 'CANCEL_REQUESTED')
                       AND remaining_quantity_steps > 0
                )
                """, Boolean.class, productLine(productLine).name(), userId, symbol,
                MarginMode.defaultIfNull(marginMode).name());
        return Boolean.TRUE.equals(conflict);
    }

    public long openReduceOnlySteps(ProductLine productLine,
                                    long userId,
                                    String symbol,
                                    MarginMode marginMode,
                                    PositionSide positionSide,
                                    long instrumentVersion,
                                    OrderSide closeSide) {
        Long value = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(remaining_quantity_steps), 0)
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
                """, Long.class, productLine(productLine).name(), userId, symbol,
                MarginMode.defaultIfNull(marginMode).name(),
                PositionSide.defaultIfNull(positionSide).name(), instrumentVersion, closeSide.name());
        return value == null ? 0L : value;
    }

    private ProductLine productLine(ProductLine productLine) {
        return productLine == null ? ProductLine.LINEAR_PERPETUAL : productLine;
    }
}
