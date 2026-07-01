package com.surprising.insurance.provider.repository;

import com.surprising.insurance.api.model.InsuranceCoverageResponse;
import com.surprising.insurance.api.model.InsuranceFundBalanceResponse;
import com.surprising.insurance.api.model.InsuranceFundLedgerResponse;
import com.surprising.insurance.provider.service.InsuranceMath;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class InsuranceRepository {

    private final JdbcTemplate jdbcTemplate;

    public InsuranceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long nextInsuranceSequence(String sequenceName) {
        Long value = jdbcTemplate.queryForObject("""
                INSERT INTO insurance_sequences (sequence_name, sequence_value, updated_at)
                VALUES (?, 1, now())
                ON CONFLICT (sequence_name) DO UPDATE SET
                    sequence_value = insurance_sequences.sequence_value + 1,
                    updated_at = now()
                RETURNING sequence_value
                """, Long.class, sequenceName);
        if (value == null) {
            throw new IllegalStateException("failed to allocate insurance sequence " + sequenceName);
        }
        return value;
    }

    public long nextAccountSequence(String sequenceName) {
        Long value = jdbcTemplate.queryForObject("""
                INSERT INTO account_sequences (sequence_name, sequence_value, updated_at)
                VALUES (?, 1, now())
                ON CONFLICT (sequence_name) DO UPDATE SET
                    sequence_value = account_sequences.sequence_value + 1,
                    updated_at = now()
                RETURNING sequence_value
                """, Long.class, sequenceName);
        if (value == null) {
            throw new IllegalStateException("failed to allocate account sequence " + sequenceName);
        }
        return value;
    }

    /**
     * Admin fund changes are idempotent by reference id. This keeps manual deposits,
     * operational transfers, or replayed requests from changing the fund twice.
     */
    public InsuranceFundBalanceResponse adjustFund(String asset, long amountUnits, String referenceId, String reason) {
        if (amountUnits == 0) {
            throw new IllegalArgumentException("amountUnits must not be zero");
        }
        Instant now = Instant.now();
        ensureFundBalance(asset, now);
        Long current = jdbcTemplate.queryForObject("""
                SELECT balance_units
                  FROM insurance_fund_balances
                 WHERE asset = ?
                 FOR UPDATE
                """, Long.class, asset);
        long nextBalance = Math.addExact(current == null ? 0L : current, amountUnits);
        if (nextBalance < 0) {
            throw new IllegalArgumentException("insufficient insurance fund balance");
        }
        long entryId = nextInsuranceSequence("insurance-ledger");
        int ledgerRows = jdbcTemplate.update("""
                INSERT INTO insurance_fund_ledger (
                    entry_id, asset, amount_units, balance_after_units,
                    reference_type, reference_id, reason, created_at
                ) VALUES (?, ?, ?, ?, 'FUND_ADJUSTMENT', ?, ?, ?)
                ON CONFLICT (reference_type, reference_id, asset) DO NOTHING
                """, entryId, asset, amountUnits, nextBalance, referenceId, reason, Timestamp.from(now));
        if (ledgerRows == 0) {
            requireDuplicateFundAdjustmentMatches(asset, amountUnits, referenceId, reason);
            return balance(asset).orElseThrow();
        }
        int balanceRows = jdbcTemplate.update("""
                UPDATE insurance_fund_balances
                   SET balance_units = ?,
                       updated_at = ?
                 WHERE asset = ?
                """, nextBalance, Timestamp.from(now), asset);
        requireSingleRow(balanceRows, "insurance fund balance adjustment");
        return new InsuranceFundBalanceResponse(asset, nextBalance, now);
    }

    private void requireDuplicateFundAdjustmentMatches(String asset,
                                                       long amountUnits,
                                                       String referenceId,
                                                       String reason) {
        FundAdjustmentReference existing = jdbcTemplate.query("""
                SELECT amount_units, reason
                  FROM insurance_fund_ledger
                 WHERE reference_type = 'FUND_ADJUSTMENT'
                   AND reference_id = ?
                   AND asset = ?
                 FOR UPDATE
                """, (rs, rowNum) -> new FundAdjustmentReference(
                rs.getLong("amount_units"),
                rs.getString("reason")), referenceId, asset).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("duplicate fund adjustment but ledger missing"));
        if (existing.amountUnits() != amountUnits || !Objects.equals(existing.reason(), reason)) {
            throw new IllegalStateException("conflicting duplicate insurance fund reference " + referenceId);
        }
    }

    /**
     * Multiple insurance-provider nodes can run this scanner at the same time.
     * PostgreSQL row locks split positive deficit rows across nodes without double coverage.
     */
    public int coverDeficits(int batchSize) {
        List<DeficitRow> rows = jdbcTemplate.query("""
                SELECT user_id, asset, deficit_units
                  FROM account_deficits
                 WHERE deficit_units > 0
                 ORDER BY updated_at ASC
                 LIMIT ?
                 FOR UPDATE SKIP LOCKED
                """, (rs, rowNum) -> new DeficitRow(
                rs.getLong("user_id"),
                rs.getString("asset"),
                rs.getLong("deficit_units")), batchSize);
        int coveredRows = 0;
        for (DeficitRow row : rows) {
            if (coverDeficit(row)) {
                coveredRows++;
            }
        }
        return coveredRows;
    }

    public List<InsuranceFundBalanceResponse> balances(String asset) {
        String normalizedAsset = asset == null || asset.isBlank() ? null : asset;
        return jdbcTemplate.query("""
                SELECT asset, balance_units, updated_at
                  FROM insurance_fund_balances
                 WHERE (? IS NULL OR asset = ?)
                 ORDER BY asset ASC
                """, (rs, rowNum) -> new InsuranceFundBalanceResponse(
                rs.getString("asset"),
                rs.getLong("balance_units"),
                rs.getTimestamp("updated_at").toInstant()), normalizedAsset, normalizedAsset);
    }

    public Optional<InsuranceFundBalanceResponse> balance(String asset) {
        return balances(asset).stream().findFirst();
    }

    public List<InsuranceFundLedgerResponse> ledger(String asset, int limit) {
        String normalizedAsset = asset == null || asset.isBlank() ? null : asset;
        return jdbcTemplate.query("""
                SELECT *
                  FROM insurance_fund_ledger
                 WHERE (? IS NULL OR asset = ?)
                 ORDER BY created_at DESC
                 LIMIT ?
                """, (rs, rowNum) -> toLedger(rs), normalizedAsset, normalizedAsset, limit);
    }

    public List<InsuranceCoverageResponse> coverages(Long userId, String asset, int limit) {
        String normalizedAsset = asset == null || asset.isBlank() ? null : asset;
        return jdbcTemplate.query("""
                SELECT *
                  FROM insurance_deficit_coverages
                 WHERE (? IS NULL OR user_id = ?)
                   AND (? IS NULL OR asset = ?)
                 ORDER BY created_at DESC
                 LIMIT ?
                """, (rs, rowNum) -> toCoverage(rs), userId, userId, normalizedAsset, normalizedAsset, limit);
    }

    private boolean coverDeficit(DeficitRow deficit) {
        Instant now = Instant.now();
        ensureFundBalance(deficit.asset(), now);
        Long fundBalance = jdbcTemplate.queryForObject("""
                SELECT balance_units
                  FROM insurance_fund_balances
                 WHERE asset = ?
                 FOR UPDATE
                """, Long.class, deficit.asset());
        long coverUnits = InsuranceMath.coverAmount(deficit.deficitUnits(), fundBalance == null ? 0L : fundBalance);
        if (coverUnits <= 0) {
            // Keep the deficit row for a future scan after the insurance fund is topped up.
            return false;
        }
        long nextFundBalance = Math.subtractExact(fundBalance, coverUnits);
        long remainingDeficit = Math.subtractExact(deficit.deficitUnits(), coverUnits);
        long coverageId = insertCoverage(deficit, coverUnits, remainingDeficit,
                remainingDeficit == 0 ? "COVERED" : "PARTIALLY_COVERED", "DEFICIT_COVERAGE", now);
        int fundRows = jdbcTemplate.update("""
                UPDATE insurance_fund_balances
                   SET balance_units = ?,
                       updated_at = ?
                 WHERE asset = ?
                """, nextFundBalance, Timestamp.from(now), deficit.asset());
        requireSingleRow(fundRows, "insurance fund balance deduction");
        int deficitRows = jdbcTemplate.update("""
                UPDATE account_deficits
                   SET deficit_units = ?,
                       updated_at = ?
                 WHERE user_id = ? AND asset = ?
                """, remainingDeficit, Timestamp.from(now), deficit.userId(), deficit.asset());
        requireSingleRow(deficitRows, "account deficit coverage");
        insertInsuranceLedger(deficit.asset(), -coverUnits, nextFundBalance,
                "DEFICIT_COVERAGE", String.valueOf(coverageId), "COVER_ACCOUNT_DEFICIT", now);
        insertAccountLedger(deficit.userId(), deficit.asset(), coverUnits, remainingDeficit,
                String.valueOf(coverageId), now);
        return true;
    }

    private long insertCoverage(DeficitRow deficit,
                                long coveredUnits,
                                long remainingDeficit,
                                String status,
                                String reason,
                                Instant now) {
        long coverageId = nextInsuranceSequence("insurance-coverage");
        int rows = jdbcTemplate.update("""
                INSERT INTO insurance_deficit_coverages (
                    coverage_id, user_id, asset, requested_units, covered_units,
                    remaining_deficit_units, status, reason, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, coverageId, deficit.userId(), deficit.asset(), deficit.deficitUnits(), coveredUnits,
                remainingDeficit, status, reason, Timestamp.from(now), Timestamp.from(now));
        requireSingleRow(rows, "insurance deficit coverage insert");
        return coverageId;
    }

    private void insertInsuranceLedger(String asset,
                                       long amountUnits,
                                       long balanceAfterUnits,
                                       String referenceType,
                                       String referenceId,
                                       String reason,
                                       Instant now) {
        int rows = jdbcTemplate.update("""
                INSERT INTO insurance_fund_ledger (
                    entry_id, asset, amount_units, balance_after_units,
                    reference_type, reference_id, reason, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (reference_type, reference_id, asset) DO NOTHING
                """, nextInsuranceSequence("insurance-ledger"), asset, amountUnits, balanceAfterUnits,
                referenceType, referenceId, reason, Timestamp.from(now));
        requireSingleRow(rows, "insurance fund ledger insert");
    }

    private void insertAccountLedger(long userId,
                                     String asset,
                                     long coveredUnits,
                                     long remainingDeficit,
                                     String referenceId,
                                     Instant now) {
        // Coverage reduces the explicit deficit. It does not credit available balance.
        long equityAfter = -remainingDeficit;
        int rows = jdbcTemplate.update("""
                INSERT INTO account_ledger_entries (
                    entry_id, user_id, asset, amount_units, balance_after_units,
                    reference_type, reference_id, reason, created_at
                ) VALUES (?, ?, ?, ?, ?, 'INSURANCE_COVERAGE', ?, 'COVER_ACCOUNT_DEFICIT', ?)
                ON CONFLICT (reference_type, reference_id, user_id, asset) DO NOTHING
                """, nextAccountSequence("ledger-entry"), userId, asset, coveredUnits, equityAfter,
                referenceId, Timestamp.from(now));
        requireSingleRow(rows, "insurance account ledger insert");
    }

    private void ensureFundBalance(String asset, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO insurance_fund_balances (asset, balance_units, updated_at)
                VALUES (?, 0, ?)
                ON CONFLICT (asset) DO NOTHING
                """, asset, Timestamp.from(now));
    }

    private void requireSingleRow(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException("failed to write " + operation);
        }
    }

    private InsuranceFundLedgerResponse toLedger(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new InsuranceFundLedgerResponse(
                rs.getLong("entry_id"),
                rs.getString("asset"),
                rs.getLong("amount_units"),
                rs.getLong("balance_after_units"),
                rs.getString("reference_type"),
                rs.getString("reference_id"),
                rs.getString("reason"),
                rs.getTimestamp("created_at").toInstant());
    }

    private InsuranceCoverageResponse toCoverage(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new InsuranceCoverageResponse(
                rs.getLong("coverage_id"),
                rs.getLong("user_id"),
                rs.getString("asset"),
                rs.getLong("requested_units"),
                rs.getLong("covered_units"),
                rs.getLong("remaining_deficit_units"),
                rs.getString("status"),
                rs.getString("reason"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private record DeficitRow(long userId, String asset, long deficitUnits) {
    }

    private record FundAdjustmentReference(long amountUnits, String reason) {
    }
}
