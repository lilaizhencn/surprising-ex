package com.surprising.insurance.provider.repository;

import com.surprising.insurance.api.model.InsuranceFundBalanceResponse;
import com.surprising.insurance.provider.model.InsuranceFundBalanceState;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 仅负责 insurance_fund_balances 表。
 */
@Repository
public class InsuranceFundBalanceRepository {

    private final JdbcTemplate jdbcTemplate;

    public InsuranceFundBalanceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void ensure(String accountType, String asset, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO insurance_fund_balances (account_type, asset, balance_units, updated_at)
                VALUES (?, ?, 0, ?)
                ON CONFLICT (account_type, asset) DO NOTHING
                """, accountType, asset, Timestamp.from(now));
    }

    public InsuranceFundBalanceState lock(String accountType, String asset) {
        return jdbcTemplate.queryForObject("""
                SELECT balance_units, reserved_units
                  FROM insurance_fund_balances
                 WHERE account_type = ? AND asset = ?
                 FOR UPDATE
                """, (rs, rowNum) -> new InsuranceFundBalanceState(
                rs.getLong("balance_units"), rs.getLong("reserved_units")), accountType, asset);
    }

    public void updateBalance(String accountType, String asset, long balanceUnits, Instant now) {
        requireSingle(jdbcTemplate.update("""
                UPDATE insurance_fund_balances
                   SET balance_units = ?, updated_at = ?
                 WHERE account_type = ? AND asset = ?
                """, balanceUnits, Timestamp.from(now), accountType, asset), "insurance fund balance");
    }

    public void reserve(String accountType, String asset, long amountUnits, Instant now) {
        requireSingle(jdbcTemplate.update("""
                UPDATE insurance_fund_balances
                   SET reserved_units = reserved_units + ?, updated_at = ?
                 WHERE account_type = ? AND asset = ?
                   AND balance_units - reserved_units >= ?
                """, amountUnits, Timestamp.from(now), accountType, asset, amountUnits),
                "insurance fund coverage reservation");
    }

    public void release(String accountType, String asset, long amountUnits, Instant now) {
        requireSingle(jdbcTemplate.update("""
                UPDATE insurance_fund_balances
                   SET reserved_units = reserved_units - ?, updated_at = ?
                 WHERE account_type = ? AND asset = ? AND reserved_units >= ?
                """, amountUnits, Timestamp.from(now), accountType, asset, amountUnits),
                "insurance fund reservation release");
    }

    public long consumeReservation(String accountType, String asset, long amountUnits, Instant now) {
        List<Long> balances = jdbcTemplate.query("""
                UPDATE insurance_fund_balances
                   SET balance_units = balance_units - ?,
                       reserved_units = reserved_units - ?,
                       updated_at = ?
                 WHERE account_type = ? AND asset = ?
                   AND balance_units >= ? AND reserved_units >= ?
             RETURNING balance_units
                """, (rs, rowNum) -> rs.getLong("balance_units"),
                amountUnits, amountUnits, Timestamp.from(now), accountType, asset, amountUnits, amountUnits);
        if (balances == null || balances.size() != 1) {
            throw new IllegalStateException("insurance fund reservation missing at coverage completion");
        }
        return balances.getFirst();
    }

    public List<InsuranceFundBalanceResponse> find(String accountType, String asset) {
        String normalizedAsset = asset == null || asset.isBlank() ? null : asset;
        return jdbcTemplate.query("""
                SELECT asset, balance_units, updated_at
                  FROM insurance_fund_balances
                 WHERE account_type = ?
                   AND (CAST(? AS text) IS NULL OR asset = ?)
                 ORDER BY asset ASC
                """, (rs, rowNum) -> new InsuranceFundBalanceResponse(
                rs.getString("asset"),
                rs.getLong("balance_units"),
                rs.getTimestamp("updated_at").toInstant()), accountType, normalizedAsset, normalizedAsset);
    }

    public Optional<InsuranceFundBalanceResponse> findOne(String accountType, String asset) {
        return find(accountType, asset).stream().findFirst();
    }

    private void requireSingle(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException("failed to write " + operation);
        }
    }
}
