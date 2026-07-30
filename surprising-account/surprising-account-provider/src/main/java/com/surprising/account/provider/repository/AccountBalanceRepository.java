package com.surprising.account.provider.repository;

import java.sql.Timestamp;
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

    public void ensure(long userId, String asset, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO account_balances (user_id, asset, available_units, locked_units, updated_at)
                VALUES (?, ?, 0, 0, ?)
                ON CONFLICT (user_id, asset) DO NOTHING
                """, userId, asset, Timestamp.from(now));
    }

    public long applyAvailableDelta(long userId, String asset, long amountUnits, Instant now) {
        ensure(userId, asset, now);
        Long currentAvailable = jdbcTemplate.queryForObject("""
                SELECT available_units
                  FROM account_balances
                 WHERE user_id = ?
                   AND asset = ?
                 FOR UPDATE
                """, Long.class, userId, asset);
        long nextAvailable = Math.addExact(currentAvailable == null ? 0L : currentAvailable, amountUnits);
        if (nextAvailable < 0) {
            throw new IllegalArgumentException("insufficient available balance");
        }
        int rows = jdbcTemplate.update("""
                UPDATE account_balances
                   SET available_units = ?,
                       updated_at = ?
                 WHERE user_id = ?
                   AND asset = ?
                """, nextAvailable, Timestamp.from(now), userId, asset);
        requireSingleRow(rows, "account balance available update");
        return nextAvailable;
    }

    private static void requireSingleRow(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException(operation + " affected " + rows + " rows");
        }
    }

    public record BalanceRow(
            long userId,
            String asset,
            long availableUnits,
            long lockedUnits,
            Instant updatedAt) {
    }
}
