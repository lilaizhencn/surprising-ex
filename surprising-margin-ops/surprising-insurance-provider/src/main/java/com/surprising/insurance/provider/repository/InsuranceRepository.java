package com.surprising.insurance.provider.repository;

import com.surprising.account.api.model.AccountCommandStatus;
import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.account.api.model.AccountUserCommandType;
import com.surprising.account.api.model.DeficitReservationAccountCommand;
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
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class InsuranceRepository {

    private static final String DEFAULT_ACCOUNT_TYPE = "USDT_PERPETUAL";

    private final JdbcTemplate jdbcTemplate;
    private final InsuranceProperties properties;
    private final ObjectMapper objectMapper;

    public InsuranceRepository(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new InsuranceProperties(), null);
    }

    public InsuranceRepository(JdbcTemplate jdbcTemplate, InsuranceProperties properties) {
        this(jdbcTemplate, properties, null);
    }

    @Autowired
    public InsuranceRepository(JdbcTemplate jdbcTemplate,
                               InsuranceProperties properties,
                               ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties == null ? new InsuranceProperties() : properties;
        this.objectMapper = objectMapper;
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
        FundBalanceState current = jdbcTemplate.queryForObject("""
                SELECT balance_units, reserved_units
                  FROM insurance_fund_balances
                 WHERE account_type = ? AND asset = ?
                 FOR UPDATE
                """, (rs, rowNum) -> new FundBalanceState(
                rs.getLong("balance_units"), rs.getLong("reserved_units")), accountType, asset);
        long nextBalance = Math.addExact(current == null ? 0L : current.balanceUnits(), amountUnits);
        if (nextBalance < (current == null ? 0L : current.reservedUnits())) {
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
                    SELECT account_type, user_id, asset,
                           deficit_units - reserved_units AS deficit_units
                      FROM account_product_deficits
                     WHERE account_type = ?
                       AND deficit_units - reserved_units > 0
                     ORDER BY updated_at ASC
                     LIMIT ?
                    """, (rs, rowNum) -> new DeficitRow(
                        rs.getString("account_type"),
                        rs.getLong("user_id"),
                        rs.getString("asset"),
                        rs.getLong("deficit_units")), accountType, batchSize)
                : jdbcTemplate.query("""
                    SELECT ? AS account_type, user_id, asset,
                           deficit_units - reserved_units AS deficit_units
                      FROM account_deficits
                     WHERE deficit_units - reserved_units > 0
                     ORDER BY updated_at ASC
                     LIMIT ?
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
        FundBalanceState fund = jdbcTemplate.queryForObject("""
                SELECT balance_units, reserved_units
                  FROM insurance_fund_balances
                 WHERE account_type = ? AND asset = ?
                 FOR UPDATE
                """, (rs, rowNum) -> new FundBalanceState(
                rs.getLong("balance_units"), rs.getLong("reserved_units")),
                deficit.accountType(), deficit.asset());
        long availableFund = fund == null
                ? 0L
                : Math.subtractExact(fund.balanceUnits(), fund.reservedUnits());
        long coverUnits = InsuranceMath.coverAmount(deficit.deficitUnits(), availableFund);
        if (coverUnits <= 0) {
            return false;
        }
        long remainingDeficit = Math.subtractExact(deficit.deficitUnits(), coverUnits);
        long coverageId = nextInsuranceSequence("insurance-coverage");
        String commandPrefix = properties.getKafka().getProductLine().name() + ":" + coverageId;
        String reserveCommandId = "INSURANCE_RESERVE:" + commandPrefix;
        String finalizeCommandId = "INSURANCE_FINALIZE:" + commandPrefix;
        int reserveRows = jdbcTemplate.update("""
                UPDATE insurance_fund_balances
                   SET reserved_units = reserved_units + ?, updated_at = ?
                 WHERE account_type = ? AND asset = ?
                   AND balance_units - reserved_units >= ?
                """, coverUnits, Timestamp.from(now), deficit.accountType(), deficit.asset(), coverUnits);
        requireSingleRow(reserveRows, "insurance fund coverage reservation");
        insertCoverage(coverageId, deficit, coverUnits, remainingDeficit,
                reserveCommandId, finalizeCommandId, now);
        DeficitReservationAccountCommand payload =
                new DeficitReservationAccountCommand(deficit.asset(), coverUnits);
        AccountUserCommand reserve = accountCommand(
                reserveCommandId, deficit.userId(), AccountUserCommandType.INSURANCE_DEFICIT_RESERVE,
                coverageId, null, payload, now);
        AccountUserCommand finalize = accountCommand(
                finalizeCommandId, deficit.userId(), AccountUserCommandType.INSURANCE_DEFICIT_FINALIZE,
                coverageId, reserveCommandId, payload, now);
        enqueueAccountCommand(coverageId, reserve, now);
        enqueueAccountCommand(coverageId, finalize, now);
        return true;
    }

    private void insertCoverage(long coverageId,
                                DeficitRow deficit,
                                long coveredUnits,
                                long remainingDeficit,
                                String reserveCommandId,
                                String finalizeCommandId,
                                Instant now) {
        int rows = jdbcTemplate.update("""
                INSERT INTO insurance_deficit_coverages (
                    coverage_id, account_type, user_id, asset, requested_units, covered_units,
                    remaining_deficit_units, reserve_command_id, finalize_command_id,
                    status, reason, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING_RESERVE',
                          'DEFICIT_COVERAGE', ?, ?)
                """, coverageId, deficit.accountType(), deficit.userId(), deficit.asset(), deficit.deficitUnits(), coveredUnits,
                remainingDeficit, reserveCommandId, finalizeCommandId, Timestamp.from(now), Timestamp.from(now));
        requireSingleRow(rows, "insurance deficit coverage insert");
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

    public List<PendingCoverage> lockPendingCoverages(int limit) {
        return jdbcTemplate.query("""
                SELECT c.coverage_id, c.account_type, c.user_id, c.asset, c.covered_units,
                       c.reserve_command_id, c.finalize_command_id, c.status,
                       reserve.status AS reserve_status,
                       finalize.status AS finalize_status,
                       finalize.result_payload::text AS finalize_result,
                       COALESCE(reserve.error_code, finalize.error_code) AS error_code,
                       COALESCE(reserve.error_message, finalize.error_message) AS error_message
                  FROM insurance_deficit_coverages c
                  LEFT JOIN account_commands reserve ON reserve.command_id = c.reserve_command_id
                  LEFT JOIN account_commands finalize ON finalize.command_id = c.finalize_command_id
                 WHERE c.account_type = ?
                   AND c.status IN ('PENDING_RESERVE', 'PENDING_FINALIZE')
                 ORDER BY c.created_at ASC, c.coverage_id ASC
                 LIMIT ?
                 FOR UPDATE OF c SKIP LOCKED
                """, (rs, rowNum) -> new PendingCoverage(
                rs.getLong("coverage_id"), rs.getString("account_type"), rs.getLong("user_id"),
                rs.getString("asset"), rs.getLong("covered_units"), rs.getString("reserve_command_id"),
                rs.getString("finalize_command_id"), rs.getString("status"),
                rs.getString("reserve_status"), rs.getString("finalize_status"),
                rs.getString("finalize_result"), rs.getString("error_code"), rs.getString("error_message")),
                accountType(), Math.max(1, limit));
    }

    public void markPendingFinalize(long coverageId, Instant now) {
        int rows = jdbcTemplate.update("""
                UPDATE insurance_deficit_coverages
                   SET status = 'PENDING_FINALIZE', updated_at = ?
                 WHERE coverage_id = ? AND status = 'PENDING_RESERVE'
                """, Timestamp.from(now), coverageId);
        if (rows != 0 && rows != 1) {
            throw new IllegalStateException("failed to update insurance coverage progress");
        }
    }

    public void failCoverage(PendingCoverage coverage, Instant now) {
        int fundRows = jdbcTemplate.update("""
                UPDATE insurance_fund_balances
                   SET reserved_units = reserved_units - ?, updated_at = ?
                 WHERE account_type = ? AND asset = ? AND reserved_units >= ?
                """, coverage.coveredUnits(), Timestamp.from(now), coverage.accountType(),
                coverage.asset(), coverage.coveredUnits());
        requireSingleRow(fundRows, "insurance fund reservation release");
        int rows = jdbcTemplate.update("""
                UPDATE insurance_deficit_coverages
                   SET status = 'FAILED', error_code = ?, error_message = ?,
                       completed_at = ?, updated_at = ?
                 WHERE coverage_id = ? AND status IN ('PENDING_RESERVE', 'PENDING_FINALIZE')
                """, coverage.errorCode(), truncate(coverage.errorMessage()), Timestamp.from(now),
                Timestamp.from(now), coverage.coverageId());
        requireSingleRow(rows, "insurance coverage failed transition");
    }

    public void completeCoverage(PendingCoverage coverage, long remainingDeficitUnits, Instant now) {
        List<Long> balances = jdbcTemplate.query("""
                UPDATE insurance_fund_balances
                   SET balance_units = balance_units - ?,
                       reserved_units = reserved_units - ?,
                       updated_at = ?
                 WHERE account_type = ? AND asset = ?
                   AND balance_units >= ? AND reserved_units >= ?
             RETURNING balance_units
                """, (rs, rowNum) -> rs.getLong("balance_units"),
                coverage.coveredUnits(), coverage.coveredUnits(), Timestamp.from(now),
                coverage.accountType(), coverage.asset(), coverage.coveredUnits(), coverage.coveredUnits());
        if (balances == null || balances.size() != 1) {
            throw new IllegalStateException("insurance fund reservation missing at coverage completion");
        }
        insertInsuranceLedger(coverage.accountType(), coverage.asset(), Math.negateExact(coverage.coveredUnits()),
                balances.getFirst(), "DEFICIT_COVERAGE", Long.toString(coverage.coverageId()),
                "COVER_ACCOUNT_DEFICIT", now);
        int rows = jdbcTemplate.update("""
                UPDATE insurance_deficit_coverages
                   SET remaining_deficit_units = ?,
                       status = CASE WHEN ? = 0 THEN 'COVERED' ELSE 'PARTIALLY_COVERED' END,
                       completed_at = ?, updated_at = ?
                 WHERE coverage_id = ? AND status IN ('PENDING_RESERVE', 'PENDING_FINALIZE')
                """, remainingDeficitUnits, remainingDeficitUnits, Timestamp.from(now),
                Timestamp.from(now), coverage.coverageId());
        requireSingleRow(rows, "insurance coverage completion");
    }

    private AccountUserCommand accountCommand(String commandId,
                                              long userId,
                                              AccountUserCommandType type,
                                              long coverageId,
                                              String dependency,
                                              Object payload,
                                              Instant now) {
        if (objectMapper == null) {
            throw new IllegalStateException("insurance account command serializer is not configured");
        }
        return new AccountUserCommand(
                AccountUserCommand.CURRENT_SCHEMA_VERSION,
                commandId,
                properties.getKafka().getProductLine(),
                userId,
                type,
                "INSURANCE",
                Long.toString(coverageId),
                dependency,
                objectMapper.writeValueAsString(payload),
                now,
                null);
    }

    private void enqueueAccountCommand(long coverageId, AccountUserCommand command, Instant now) {
        int rows = jdbcTemplate.update("""
                INSERT INTO account_outbox_events (
                    product_line, aggregate_type, aggregate_id, topic, event_key, event_type,
                    payload, next_attempt_at, created_at, updated_at
                ) VALUES (?, 'INSURANCE_ACCOUNT_COMMAND', ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """, command.productLine().name(), coverageId, properties.getKafka().getUserCommandsTopic(),
                command.partitionKey(), command.commandType().name(), objectMapper.writeValueAsString(command),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        requireSingleRow(rows, "insurance account command enqueue");
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

    private String truncate(String value) {
        if (value == null || value.length() <= 1000) {
            return value;
        }
        return value.substring(0, 1000);
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

    private record FundBalanceState(long balanceUnits, long reservedUnits) {
    }

    private record FundAdjustmentReference(long amountUnits, String reason) {
    }

    public record PendingCoverage(
            long coverageId,
            String accountType,
            long userId,
            String asset,
            long coveredUnits,
            String reserveCommandId,
            String finalizeCommandId,
            String coverageStatus,
            String reserveStatus,
            String finalizeStatus,
            String finalizeResult,
            String errorCode,
            String errorMessage) {

        public boolean reserveApplied() {
            return AccountCommandStatus.APPLIED.name().equals(reserveStatus);
        }

        public boolean reserveRejected() {
            return AccountCommandStatus.REJECTED.name().equals(reserveStatus);
        }

        public boolean finalizeApplied() {
            return AccountCommandStatus.APPLIED.name().equals(finalizeStatus);
        }
    }
}
