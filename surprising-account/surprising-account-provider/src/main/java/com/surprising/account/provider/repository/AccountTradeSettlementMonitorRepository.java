package com.surprising.account.provider.repository;

import com.surprising.product.api.ProductLine;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AccountTradeSettlementMonitorRepository {

    private final JdbcTemplate jdbcTemplate;

    public AccountTradeSettlementMonitorRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public IncompleteTradeSnapshot staleIncomplete(ProductLine productLine,
                                                   Duration staleAfter,
                                                   Instant now) {
        Instant cutoff = now.minus(staleAfter);
        Timestamp checkedAt = Timestamp.from(now);
        return jdbcTemplate.query("""
                WITH complete_pairs AS MATERIALIZED (
                    SELECT product_line, symbol, trade_id
                      FROM account_trade_settlement_sides
                     WHERE product_line = ?
                       AND reconciled_at IS NULL
                     GROUP BY product_line, symbol, trade_id
                    HAVING COUNT(*) = 2
                     ORDER BY MIN(applied_at)
                     LIMIT 1000
                ),
                reconciled AS (
                    UPDATE account_trade_settlement_sides s
                       SET reconciled_at = ?
                      FROM complete_pairs p
                     WHERE s.product_line = p.product_line
                       AND s.symbol = p.symbol
                       AND s.trade_id = p.trade_id
                       AND s.reconciled_at IS NULL
                 RETURNING s.product_line
                ),
                incomplete AS (
                    SELECT product_line,
                           symbol,
                           trade_id,
                           MIN(applied_at) AS oldest_created_at
                      FROM account_trade_settlement_sides
                     WHERE product_line = ?
                       AND reconciled_at IS NULL
                       AND applied_at < ?
                     GROUP BY product_line, symbol, trade_id
                    HAVING COUNT(*) < 2
                )
                SELECT COUNT(*) AS incomplete_count,
                       MIN(oldest_created_at) AS oldest_created_at
                  FROM incomplete
                """, rs -> {
            if (!rs.next()) {
                return new IncompleteTradeSnapshot(0L, null);
            }
            Timestamp oldest = rs.getTimestamp("oldest_created_at");
            return new IncompleteTradeSnapshot(
                    rs.getLong("incomplete_count"),
                    oldest == null ? null : oldest.toInstant());
        }, productLine.name(), checkedAt, productLine.name(), Timestamp.from(cutoff));
    }

    public record IncompleteTradeSnapshot(long count, Instant oldestCreatedAt) {
    }
}
