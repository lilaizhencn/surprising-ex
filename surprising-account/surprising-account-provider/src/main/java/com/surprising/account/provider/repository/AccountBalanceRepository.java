package com.surprising.account.provider.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AccountBalanceRepository {

    private final JdbcTemplate jdbcTemplate;

    public AccountBalanceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<BalanceRow> find(long userId, String asset) {
        return jdbcTemplate.query("""
                SELECT user_id, asset, available_units, locked_units, updated_at
                  FROM account_balances
                 WHERE user_id = ?
                   AND asset = ?
                """, (rs, rowNum) -> new BalanceRow(
                        rs.getLong("user_id"),
                        rs.getString("asset"),
                        rs.getLong("available_units"),
                        rs.getLong("locked_units"),
                        rs.getTimestamp("updated_at").toInstant()), userId, asset)
                .stream().findFirst();
    }

    public List<BalanceRow> findByUser(long userId) {
        return jdbcTemplate.query("""
                SELECT user_id, asset, available_units, locked_units, updated_at
                  FROM account_balances
                 WHERE user_id = ?
                 ORDER BY asset ASC
                """, (rs, rowNum) -> new BalanceRow(
                        rs.getLong("user_id"),
                        rs.getString("asset"),
                        rs.getLong("available_units"),
                        rs.getLong("locked_units"),
                        rs.getTimestamp("updated_at").toInstant()), userId);
    }

    public record BalanceRow(
            long userId,
            String asset,
            long availableUnits,
            long lockedUnits,
            Instant updatedAt) {
    }
}
