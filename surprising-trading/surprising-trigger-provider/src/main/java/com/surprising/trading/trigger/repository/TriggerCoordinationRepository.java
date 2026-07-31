package com.surprising.trading.trigger.repository;

import com.surprising.product.api.ProductLine;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 触发模块事务协调仓储，只负责 PostgreSQL advisory lock，不访问业务表。
 */
@Repository
public class TriggerCoordinationRepository {

    private final JdbcTemplate jdbcTemplate;

    public TriggerCoordinationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void lockUserSymbolMarginScope(ProductLine productLine, long userId, String symbol) {
        jdbcTemplate.query("""
                SELECT pg_advisory_xact_lock(hashtext('trading-margin-mode'), hashtext(?))
                """, rs -> null, productLine(productLine).name() + ":" + userId + ":" + symbol);
    }

    public void lockUserPositionMode(ProductLine productLine, long userId) {
        jdbcTemplate.query("""
                SELECT pg_advisory_xact_lock(hashtext('position-mode'), hashtext(?))
                """, rs -> null, productLine(productLine).name() + ":" + userId);
    }

    private ProductLine productLine(ProductLine productLine) {
        return productLine == null ? ProductLine.LINEAR_PERPETUAL : productLine;
    }
}
