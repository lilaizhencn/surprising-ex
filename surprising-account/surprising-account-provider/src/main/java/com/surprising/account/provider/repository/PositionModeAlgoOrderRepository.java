package com.surprising.account.provider.repository;

import com.surprising.product.api.ProductLine;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 仅负责 trading_algo_orders 表中的持仓模式切换校验。
 */
@Repository
public class PositionModeAlgoOrderRepository {

    private final JdbcTemplate jdbcTemplate;

    public PositionModeAlgoOrderRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean existsActive(ProductLine productLine, long userId) {
        Boolean exists = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM trading_algo_orders
                     WHERE product_line = ?
                       AND user_id = ?
                       AND status IN ('PENDING', 'RUNNING', 'CANCEL_REQUESTED')
                )
                """, Boolean.class, productLine.name(), userId);
        return Boolean.TRUE.equals(exists);
    }
}
