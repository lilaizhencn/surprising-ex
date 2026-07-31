package com.surprising.account.provider.repository;

import com.surprising.product.api.ProductLine;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 负责持仓模式切换的事务级串行锁，不访问业务表。
 */
@Repository
public class PositionModeLockRepository {

    private final JdbcTemplate jdbcTemplate;

    public PositionModeLockRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void lock(ProductLine productLine, long userId) {
        jdbcTemplate.query("""
                SELECT pg_advisory_xact_lock(hashtext('position-mode'), hashtext(?))
                """, rs -> null, productLine.name() + ":" + userId);
    }
}
