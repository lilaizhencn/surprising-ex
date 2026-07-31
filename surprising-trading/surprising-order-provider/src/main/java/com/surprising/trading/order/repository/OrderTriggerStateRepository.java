package com.surprising.trading.order.repository;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 订单入口触发单状态仓储，只负责 {@code trading_trigger_orders} 表。 */
@Repository
public class OrderTriggerStateRepository {
    private final JdbcTemplate jdbcTemplate;

    public OrderTriggerStateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean hasMarginModeConflict(ProductLine line, long userId, String symbol, MarginMode mode) {
        Boolean value = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM trading_trigger_orders
                     WHERE product_line = ? AND user_id = ? AND symbol = ?
                       AND margin_mode <> ? AND status IN ('PENDING', 'TRIGGERING')
                )
                """, Boolean.class, line.name(), userId, symbol, MarginMode.defaultIfNull(mode).name());
        return Boolean.TRUE.equals(value);
    }
}
