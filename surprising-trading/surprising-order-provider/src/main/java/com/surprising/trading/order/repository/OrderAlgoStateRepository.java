package com.surprising.trading.order.repository;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 订单入口算法单状态仓储，只负责 {@code trading_algo_orders} 表。 */
@Repository
public class OrderAlgoStateRepository {
    private final JdbcTemplate jdbcTemplate;

    public OrderAlgoStateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean hasMarginModeConflict(ProductLine line, long userId, String symbol, MarginMode mode) {
        Boolean value = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM trading_algo_orders
                     WHERE product_line = ? AND user_id = ? AND symbol = ?
                       AND margin_mode <> ? AND status IN ('PENDING', 'RUNNING', 'CANCEL_REQUESTED')
                )
                """, Boolean.class, line.name(), userId, symbol, MarginMode.defaultIfNull(mode).name());
        return Boolean.TRUE.equals(value);
    }
}
