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
        return jdbcTemplate.query("""
                SELECT COUNT(*) AS incomplete_count,
                       MIN(created_at) AS oldest_created_at
                  FROM account_trade_settlements
                 WHERE product_line = ?
                   AND completed_at IS NULL
                   AND created_at < ?
                """, rs -> {
            if (!rs.next()) {
                return new IncompleteTradeSnapshot(0L, null);
            }
            Timestamp oldest = rs.getTimestamp("oldest_created_at");
            return new IncompleteTradeSnapshot(
                    rs.getLong("incomplete_count"),
                    oldest == null ? null : oldest.toInstant());
        }, productLine.name(), Timestamp.from(cutoff));
    }

    public record IncompleteTradeSnapshot(long count, Instant oldestCreatedAt) {
    }
}
