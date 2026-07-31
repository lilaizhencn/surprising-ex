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

    public boolean moveAvailableToLocked(long userId, String asset, long amountUnits, Instant now) {
        ensure(userId, asset, now);
        return jdbcTemplate.update("""
                UPDATE account_balances
                   SET available_units = available_units - ?,
                       locked_units = locked_units + ?,
                       updated_at = ?
                 WHERE user_id = ?
                   AND asset = ?
                   AND available_units >= ?
                """, amountUnits, amountUnits, Timestamp.from(now), userId, asset, amountUnits) == 1;
    }

    public int moveLockedToAvailable(long userId, String asset, long amountUnits, Instant now) {
        return jdbcTemplate.update("""
                UPDATE account_balances
                   SET locked_units = locked_units - ?,
                       available_units = available_units + ?,
                       updated_at = ?
                 WHERE user_id = ?
                   AND asset = ?
                   AND locked_units >= ?
                """, amountUnits, amountUnits, Timestamp.from(now), userId, asset, amountUnits);
    }

    public long creditAvailableAndReturnEquity(long userId, String asset, long amountUnits, Instant now) {
        ensure(userId, asset, now);
        List<Long> rows = jdbcTemplate.query("""
                UPDATE account_balances
                   SET available_units = available_units + ?,
                       updated_at = ?
                 WHERE user_id = ?
                   AND asset = ?
             RETURNING available_units + locked_units
                """, (rs, rowNum) -> rs.getLong(1), amountUnits, Timestamp.from(now), userId, asset);
        return requireOne(rows, "account balance credit");
    }

    public long debitAvailableAndReturnEquity(long userId, String asset, long amountUnits, Instant now) {
        List<Long> rows = jdbcTemplate.query("""
                UPDATE account_balances
                   SET available_units = available_units - ?,
                       updated_at = ?
                 WHERE user_id = ?
                   AND asset = ?
                   AND available_units >= ?
             RETURNING available_units + locked_units
                """, (rs, rowNum) -> rs.getLong(1), amountUnits, Timestamp.from(now),
                userId, asset, amountUnits);
        return requireOne(rows, "account balance debit");
    }

    public long equity(long userId, String asset) {
        Long equityUnits = jdbcTemplate.queryForObject("""
                SELECT available_units + locked_units
                  FROM account_balances
                 WHERE user_id = ?
                   AND asset = ?
                """, Long.class, userId, asset);
        return equityUnits == null ? 0L : equityUnits;
    }

    private static void requireSingleRow(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException(operation + " affected " + rows + " rows");
        }
    }

    private static long requireOne(List<Long> rows, String operation) {
        if (rows == null || rows.size() != 1) {
            throw new IllegalStateException(operation + " affected "
                    + (rows == null ? 0 : rows.size()) + " rows");
        }
        return rows.getFirst();
    }

    public record BalanceRow(
            long userId,
            String asset,
            long availableUnits,
            long lockedUnits,
            Instant updatedAt) {
    }
}
