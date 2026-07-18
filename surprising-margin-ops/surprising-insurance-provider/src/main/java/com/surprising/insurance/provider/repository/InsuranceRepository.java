package com.surprising.insurance.provider.repository;

import com.surprising.account.api.model.LiquidationFeeSettledEvent;
import com.surprising.insurance.api.model.AdminCursorPage;
import com.surprising.insurance.api.model.InsuranceCoverageResponse;
import com.surprising.insurance.api.model.InsuranceFundBalanceResponse;
import com.surprising.insurance.api.model.InsuranceFundLedgerResponse;
import com.surprising.insurance.provider.config.InsuranceProperties;
import com.surprising.insurance.provider.service.InsuranceMath;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class InsuranceRepository {

    private static final String DEFAULT_ACCOUNT_TYPE = "USDT_PERPETUAL";
    private static final int ACCOUNT_HI_LO_BLOCK_SIZE = 10_000;
    private static final Map<String, String> ACCOUNT_DATABASE_SEQUENCES = Map.of(
            "ledger-entry", "public.account_ledger_entry_seq",
            "product-ledger-entry", "public.account_product_ledger_entry_seq");

    private final JdbcTemplate jdbcTemplate;
    private final InsuranceProperties properties;
    private final ConcurrentMap<String, AccountIdRange> accountIdRanges = new ConcurrentHashMap<>();

    public InsuranceRepository(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new InsuranceProperties());
    }

    @Autowired
    public InsuranceRepository(JdbcTemplate jdbcTemplate, InsuranceProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties == null ? new InsuranceProperties() : properties;
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
        String databaseSequence = ACCOUNT_DATABASE_SEQUENCES.get(sequenceName);
        if (databaseSequence == null) {
            throw new IllegalArgumentException("unsupported account sequence " + sequenceName);
        }
        return accountIdRanges.computeIfAbsent(sequenceName, ignored -> new AccountIdRange())
                .next(this, databaseSequence);
    }

    private long allocateAccountRangeStart(String databaseSequence) {
        Long value = jdbcTemplate.queryForObject("""
                SELECT nextval(CAST(? AS regclass))
                """, Long.class, databaseSequence);
        if (value == null) {
            throw new IllegalStateException("failed to allocate account sequence " + databaseSequence);
        }
        try {
            return Math.addExact(Math.multiplyExact(value - 1L, ACCOUNT_HI_LO_BLOCK_SIZE), 1L);
        } catch (ArithmeticException ex) {
            throw new IllegalStateException("account sequence exhausted " + databaseSequence, ex);
        }
    }

    private static final class AccountIdRange {
        private long next;
        private long end;

        synchronized long next(InsuranceRepository repository, String databaseSequence) {
            if (next == 0L || next > end) {
                next = repository.allocateAccountRangeStart(databaseSequence);
                end = Math.addExact(next, ACCOUNT_HI_LO_BLOCK_SIZE - 1L);
            }
            return next++;
        }
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
        String accountType = accountType();
        ensureFundBalance(accountType, asset, now);
        Long current = jdbcTemplate.queryForObject("""
                SELECT balance_units
                  FROM insurance_fund_balances
                 WHERE account_type = ? AND asset = ?
                 FOR UPDATE
                """, Long.class, accountType, asset);
        long nextBalance = Math.addExact(current == null ? 0L : current, amountUnits);
        if (nextBalance < 0) {
            throw new IllegalArgumentException("insufficient insurance fund balance");
        }
        long entryId = nextInsuranceSequence("insurance-ledger");
        int ledgerRows = jdbcTemplate.update("""
                INSERT INTO insurance_fund_ledger (
                    entry_id, account_type, asset, amount_units, balance_after_units,
                    reference_type, reference_id, reason, created_at
                ) VALUES (?, ?, ?, ?, ?, 'FUND_ADJUSTMENT', ?, ?, ?)
                ON CONFLICT (reference_type, reference_id, account_type, asset) DO NOTHING
                """, entryId, accountType, asset, amountUnits, nextBalance, referenceId, reason, Timestamp.from(now));
        if (ledgerRows == 0) {
            requireDuplicateFundAdjustmentMatches(accountType, asset, amountUnits, referenceId, reason);
            return balance(asset).orElseThrow();
        }
        int balanceRows = jdbcTemplate.update("""
                UPDATE insurance_fund_balances
                   SET balance_units = ?,
                       updated_at = ?
                 WHERE account_type = ? AND asset = ?
                """, nextBalance, Timestamp.from(now), accountType, asset);
        requireSingleRow(balanceRows, "insurance fund balance adjustment");
        return new InsuranceFundBalanceResponse(asset, nextBalance, now);
    }

    private void requireDuplicateFundAdjustmentMatches(String accountType,
                                                       String asset,
                                                       long amountUnits,
                                                       String referenceId,
                                                       String reason) {
        FundAdjustmentReference existing = jdbcTemplate.query("""
                SELECT amount_units, reason
                  FROM insurance_fund_ledger
                 WHERE reference_type = 'FUND_ADJUSTMENT'
                   AND reference_id = ?
                   AND account_type = ?
                   AND asset = ?
                """, (rs, rowNum) -> new FundAdjustmentReference(
                rs.getLong("amount_units"),
                rs.getString("reason")), referenceId, accountType, asset).stream().findFirst()
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
        String accountType = accountType();
        List<DeficitRow> rows = properties.getKafka().isProductTopicsEnabled()
                ? jdbcTemplate.query("""
                    SELECT account_type, user_id, asset, deficit_units
                      FROM account_product_deficits
                     WHERE account_type = ?
                       AND deficit_units > 0
                     ORDER BY updated_at ASC
                     LIMIT ?
                     FOR UPDATE SKIP LOCKED
                    """, (rs, rowNum) -> new DeficitRow(
                        rs.getString("account_type"),
                        rs.getLong("user_id"),
                        rs.getString("asset"),
                        rs.getLong("deficit_units")), accountType, batchSize)
                : jdbcTemplate.query("""
                    SELECT ? AS account_type, user_id, asset, deficit_units
                      FROM account_deficits
                     WHERE deficit_units > 0
                     ORDER BY updated_at ASC
                     LIMIT ?
                     FOR UPDATE SKIP LOCKED
                    """, (rs, rowNum) -> new DeficitRow(
                        rs.getString("account_type"),
                        rs.getLong("user_id"),
                        rs.getString("asset"),
                        rs.getLong("deficit_units")), accountType, batchSize);
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
        String accountType = accountType();
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

    public Optional<InsuranceFundBalanceResponse> balance(String asset) {
        return balances(asset).stream().findFirst();
    }

    public List<InsuranceFundLedgerResponse> ledger(String asset, int limit) {
        return ledgerPage(asset, limit, null, null).items();
    }

    public AdminCursorPage.CursorPage<InsuranceFundLedgerResponse> ledgerPage(String asset,
                                                                               int limit,
                                                                               String cursor,
                                                                               String sort) {
        String normalizedAsset = asset == null || asset.isBlank() ? null : asset;
        int safeLimit = AdminCursorPage.limit(limit, 1000);
        AdminCursorPage.SortSpec sortSpec = parseCreatedAtSort(sort, "entry_id");
        AdminCursorPage.Cursor decodedCursor = AdminCursorPage.decodeCursor(cursor);
        List<Object> args = new ArrayList<>();
        args.add(accountType());
        args.add(normalizedAsset);
        args.add(normalizedAsset);
        AdminCursorPage.addCursorArgs(args, decodedCursor);
        args.add(safeLimit + 1);
        List<InsuranceFundLedgerResponse> rows = jdbcTemplate.query("""
                SELECT *
                  FROM insurance_fund_ledger
                 WHERE account_type = ?
                   AND (CAST(? AS text) IS NULL OR asset = ?)
                %s
                 ORDER BY %s %s, %s %s
                 LIMIT ?
                """.formatted(AdminCursorPage.seekCondition(sortSpec, decodedCursor),
                        sortSpec.column(), sortSpec.directionSql(), sortSpec.idColumn(), sortSpec.directionSql()),
                (rs, rowNum) -> toLedger(rs), args.toArray());
        return AdminCursorPage.page(rows, safeLimit, sortSpec, InsuranceFundLedgerResponse::createdAt,
                InsuranceFundLedgerResponse::entryId);
    }

    public List<InsuranceCoverageResponse> coverages(Long userId, String asset, int limit) {
        return coveragesPage(userId, asset, limit, null, null).items();
    }

    public AdminCursorPage.CursorPage<InsuranceCoverageResponse> coveragesPage(Long userId,
                                                                                String asset,
                                                                                int limit,
                                                                                String cursor,
                                                                                String sort) {
        String normalizedAsset = asset == null || asset.isBlank() ? null : asset;
        int safeLimit = AdminCursorPage.limit(limit, 1000);
        AdminCursorPage.SortSpec sortSpec = parseCreatedAtSort(sort, "coverage_id");
        AdminCursorPage.Cursor decodedCursor = AdminCursorPage.decodeCursor(cursor);
        List<Object> args = new ArrayList<>();
        args.add(accountType());
        args.add(userId);
        args.add(userId);
        args.add(normalizedAsset);
        args.add(normalizedAsset);
        AdminCursorPage.addCursorArgs(args, decodedCursor);
        args.add(safeLimit + 1);
        List<InsuranceCoverageResponse> rows = jdbcTemplate.query("""
                SELECT *
                  FROM insurance_deficit_coverages
                 WHERE account_type = ?
                   AND (CAST(? AS text) IS NULL OR user_id = ?)
                   AND (CAST(? AS text) IS NULL OR asset = ?)
                %s
                 ORDER BY %s %s, %s %s
                 LIMIT ?
                """.formatted(AdminCursorPage.seekCondition(sortSpec, decodedCursor),
                        sortSpec.column(), sortSpec.directionSql(), sortSpec.idColumn(), sortSpec.directionSql()),
                (rs, rowNum) -> toCoverage(rs), args.toArray());
        return AdminCursorPage.page(rows, safeLimit, sortSpec, InsuranceCoverageResponse::createdAt,
                InsuranceCoverageResponse::coverageId);
    }

    private AdminCursorPage.SortSpec parseCreatedAtSort(String sort, String idColumn) {
        AdminCursorPage.SortSpec desc = new AdminCursorPage.SortSpec("createdAt", "created_at", idColumn, true);
        AdminCursorPage.SortSpec asc = new AdminCursorPage.SortSpec("createdAt", "created_at", idColumn, false);
        return AdminCursorPage.parseSort(sort, desc, List.of(desc, asc));
    }

    public boolean collectLiquidationFee(LiquidationFeeSettledEvent event) {
        if (event.amountUnits() <= 0) {
            throw new IllegalArgumentException("liquidation fee amountUnits must be positive");
        }
        Instant now = event.eventTime() == null ? Instant.now() : event.eventTime();
        String accountType = normalizeAccountType(event.accountType());
        requireProviderAccountType(accountType);
        String referenceId = event.tradeId() + ":" + event.orderId();
        ensureFundBalance(accountType, event.asset(), now);
        Long current = jdbcTemplate.queryForObject("""
                SELECT balance_units
                  FROM insurance_fund_balances
                 WHERE account_type = ? AND asset = ?
                 FOR UPDATE
                """, Long.class, accountType, event.asset());
        long currentBalance = current == null ? 0L : current;
        long nextBalance = Math.addExact(currentBalance, event.amountUnits());
        int ledgerRows = jdbcTemplate.update("""
                INSERT INTO insurance_fund_ledger (
                    entry_id, account_type, asset, amount_units, balance_after_units,
                    reference_type, reference_id, reason, created_at
                ) VALUES (?, ?, ?, ?, ?, 'LIQUIDATION_FEE', ?, 'COLLECT_LIQUIDATION_FEE', ?)
                ON CONFLICT (reference_type, reference_id, account_type, asset) DO NOTHING
                """, nextInsuranceSequence("insurance-ledger"), accountType, event.asset(), event.amountUnits(),
                nextBalance, referenceId, Timestamp.from(now));
        if (ledgerRows == 0) {
            requireDuplicateLiquidationFeeMatches(accountType, event.asset(), event.amountUnits(), referenceId);
            return false;
        }
        int balanceRows = jdbcTemplate.update("""
                UPDATE insurance_fund_balances
                   SET balance_units = ?,
                       updated_at = ?
                 WHERE account_type = ? AND asset = ?
                """, nextBalance, Timestamp.from(now), accountType, event.asset());
        requireSingleRow(balanceRows, "insurance fund liquidation fee collection");
        return true;
    }

    private void requireDuplicateLiquidationFeeMatches(String accountType,
                                                       String asset,
                                                       long amountUnits,
                                                       String referenceId) {
        FundAdjustmentReference existing = jdbcTemplate.query("""
                SELECT amount_units, reason
                  FROM insurance_fund_ledger
                 WHERE reference_type = 'LIQUIDATION_FEE'
                   AND reference_id = ?
                   AND account_type = ?
                   AND asset = ?
                """, (rs, rowNum) -> new FundAdjustmentReference(
                rs.getLong("amount_units"),
                rs.getString("reason")), referenceId, accountType, asset).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("duplicate liquidation fee but ledger missing"));
        if (existing.amountUnits() != amountUnits
                || !Objects.equals(existing.reason(), "COLLECT_LIQUIDATION_FEE")) {
            throw new IllegalStateException("conflicting duplicate liquidation fee reference " + referenceId);
        }
    }

    private boolean coverDeficit(DeficitRow deficit) {
        Instant now = Instant.now();
        ensureFundBalance(deficit.accountType(), deficit.asset(), now);
        Long fundBalance = jdbcTemplate.queryForObject("""
                SELECT balance_units
                  FROM insurance_fund_balances
                 WHERE account_type = ? AND asset = ?
                 FOR UPDATE
                """, Long.class, deficit.accountType(), deficit.asset());
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
                 WHERE account_type = ? AND asset = ?
                """, nextFundBalance, Timestamp.from(now), deficit.accountType(), deficit.asset());
        requireSingleRow(fundRows, "insurance fund balance deduction");
        int deficitRows = updateDeficit(deficit, remainingDeficit, now);
        requireSingleRow(deficitRows, "account deficit coverage");
        insertInsuranceLedger(deficit.accountType(), deficit.asset(), -coverUnits, nextFundBalance,
                "DEFICIT_COVERAGE", String.valueOf(coverageId), "COVER_ACCOUNT_DEFICIT", now);
        insertAccountLedger(deficit, coverUnits, remainingDeficit,
                String.valueOf(coverageId), now);
        return true;
    }

    private int updateDeficit(DeficitRow deficit, long remainingDeficit, Instant now) {
        if (properties.getKafka().isProductTopicsEnabled()) {
            return jdbcTemplate.update("""
                    UPDATE account_product_deficits
                       SET deficit_units = ?,
                           updated_at = ?
                     WHERE account_type = ? AND user_id = ? AND asset = ?
                    """, remainingDeficit, Timestamp.from(now), deficit.accountType(), deficit.userId(),
                    deficit.asset());
        }
        return jdbcTemplate.update("""
                UPDATE account_deficits
                   SET deficit_units = ?,
                       updated_at = ?
                 WHERE user_id = ? AND asset = ?
                """, remainingDeficit, Timestamp.from(now), deficit.userId(), deficit.asset());
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
                    coverage_id, account_type, user_id, asset, requested_units, covered_units,
                    remaining_deficit_units, status, reason, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, coverageId, deficit.accountType(), deficit.userId(), deficit.asset(), deficit.deficitUnits(), coveredUnits,
                remainingDeficit, status, reason, Timestamp.from(now), Timestamp.from(now));
        requireSingleRow(rows, "insurance deficit coverage insert");
        return coverageId;
    }

    private void insertInsuranceLedger(String accountType,
                                       String asset,
                                       long amountUnits,
                                       long balanceAfterUnits,
                                       String referenceType,
                                       String referenceId,
                                       String reason,
                                       Instant now) {
        int rows = jdbcTemplate.update("""
                INSERT INTO insurance_fund_ledger (
                    entry_id, account_type, asset, amount_units, balance_after_units,
                    reference_type, reference_id, reason, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (reference_type, reference_id, account_type, asset) DO NOTHING
                """, nextInsuranceSequence("insurance-ledger"), accountType, asset, amountUnits, balanceAfterUnits,
                referenceType, referenceId, reason, Timestamp.from(now));
        requireSingleRow(rows, "insurance fund ledger insert");
    }

    private void insertAccountLedger(DeficitRow deficit,
                                     long coveredUnits,
                                     long remainingDeficit,
                                     String referenceId,
                                     Instant now) {
        // Coverage reduces the explicit deficit. It does not credit available balance.
        long equityAfter = -remainingDeficit;
        int rows = properties.getKafka().isProductTopicsEnabled()
                ? jdbcTemplate.update("""
                    INSERT INTO account_product_ledger_entries (
                        entry_id, account_type, user_id, asset, amount_units, balance_after_units,
                        reference_type, reference_id, reason, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, 'INSURANCE_COVERAGE', ?, 'COVER_ACCOUNT_DEFICIT', ?)
                    ON CONFLICT (reference_type, reference_id, user_id, account_type, asset) DO NOTHING
                    """, nextAccountSequence("product-ledger-entry"), deficit.accountType(), deficit.userId(),
                        deficit.asset(), coveredUnits, equityAfter, referenceId, Timestamp.from(now))
                : jdbcTemplate.update("""
                    INSERT INTO account_ledger_entries (
                        entry_id, user_id, asset, amount_units, balance_after_units,
                        reference_type, reference_id, reason, created_at
                    ) VALUES (?, ?, ?, ?, ?, 'INSURANCE_COVERAGE', ?, 'COVER_ACCOUNT_DEFICIT', ?)
                    ON CONFLICT (reference_type, reference_id, user_id, asset) DO NOTHING
                    """, nextAccountSequence("ledger-entry"), deficit.userId(), deficit.asset(), coveredUnits,
                        equityAfter, referenceId, Timestamp.from(now));
        requireSingleRow(rows, "insurance account ledger insert");
    }

    private void ensureFundBalance(String accountType, String asset, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO insurance_fund_balances (account_type, asset, balance_units, updated_at)
                VALUES (?, ?, 0, ?)
                ON CONFLICT (account_type, asset) DO NOTHING
                """, accountType, asset, Timestamp.from(now));
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

    private String accountType() {
        return normalizeAccountType(properties.getKafka().getAccountType());
    }

    private void requireProviderAccountType(String eventAccountType) {
        if (!properties.getKafka().isProductTopicsEnabled()) {
            return;
        }
        String providerAccountType = accountType();
        if (!Objects.equals(eventAccountType, providerAccountType)) {
            throw new IllegalArgumentException("liquidation fee account type " + eventAccountType
                    + " does not match insurance provider account type " + providerAccountType);
        }
    }

    private String normalizeAccountType(String accountType) {
        return accountType == null || accountType.isBlank()
                ? DEFAULT_ACCOUNT_TYPE
                : accountType.trim().toUpperCase();
    }

    private record DeficitRow(String accountType, long userId, String asset, long deficitUnits) {
    }

    private record FundAdjustmentReference(long amountUnits, String reason) {
    }
}
