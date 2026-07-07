package com.surprising.marketmaker.provider.service;

import com.surprising.product.api.ProductLine;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcMarketMakerLeaseCoordinator implements MarketMakerLeaseCoordinator {

    private final JdbcTemplate jdbcTemplate;

    public JdbcMarketMakerLeaseCoordinator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean tryAcquire(ProductLine productLine,
                              String strategyId,
                              String symbol,
                              String ownerId,
                              Duration leaseDuration) {
        Instant now = Instant.now();
        Instant leaseUntil = now.plus(leaseDuration);
        int updated = jdbcTemplate.update("""
                UPDATE market_maker_strategy_leases
                   SET owner_id = ?,
                       lease_until = ?,
                       updated_at = ?
                 WHERE product_line = ?
                   AND strategy_id = ?
                   AND symbol = ?
                   AND (owner_id = ? OR lease_until <= ?)
                """, ownerId, Timestamp.from(leaseUntil), Timestamp.from(now), productLine.name(), strategyId, symbol, ownerId,
                Timestamp.from(now));
        if (updated > 0) {
            return true;
        }
        int inserted = jdbcTemplate.update("""
                INSERT INTO market_maker_strategy_leases (
                    product_line, strategy_id, symbol, owner_id, lease_until, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (product_line, strategy_id, symbol) DO NOTHING
                """, productLine.name(), strategyId, symbol, ownerId, Timestamp.from(leaseUntil), Timestamp.from(now));
        return inserted > 0;
    }
}
