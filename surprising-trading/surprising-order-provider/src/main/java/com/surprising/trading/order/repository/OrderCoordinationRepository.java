package com.surprising.trading.order.repository;

import com.surprising.product.api.ProductLine;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 订单入口事务协调仓储，只负责 PostgreSQL advisory lock，不访问业务表。 */
@Repository
public class OrderCoordinationRepository {
    private final JdbcTemplate jdbcTemplate;

    public OrderCoordinationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void lockUserPositionMode(ProductLine line, long userId) {
        jdbcTemplate.query("SELECT pg_advisory_xact_lock(hashtext('position-mode'), hashtext(?))",
                rs -> null, line.name() + ":" + userId);
    }

    public void lockUserSymbolMarginScope(ProductLine line, long userId, String symbol) {
        jdbcTemplate.query("SELECT pg_advisory_xact_lock(hashtext('trading-margin-mode'), hashtext(?))",
                rs -> null, line.name() + ":" + userId + ":" + symbol);
    }
}
