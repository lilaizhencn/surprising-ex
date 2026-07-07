package com.surprising.account.provider.repository;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.AdminBalanceAdjustmentRecord;
import com.surprising.account.api.model.AdminCursorPage;
import com.surprising.account.api.model.AccountLedgerEntryResponse;
import com.surprising.account.api.model.BalanceResponse;
import com.surprising.account.api.model.ProductBalanceResponse;
import com.surprising.account.api.model.ProductLedgerEntryResponse;
import com.surprising.account.api.model.ProductTransferRecordResponse;
import com.surprising.account.api.model.ProductTransferResponse;
import com.surprising.account.api.model.PositionMarginAdjustmentResponse;
import com.surprising.account.api.model.PositionMarginResponse;
import com.surprising.account.api.model.PositionModeResponse;
import com.surprising.account.api.model.PositionResponse;
import com.surprising.account.provider.model.BalanceDebitResult;
import com.surprising.account.provider.model.BalanceSettlementState;
import com.surprising.account.provider.model.ContractSpec;
import com.surprising.account.provider.model.LiquidationFeeContext;
import com.surprising.account.provider.model.LiquidationFeeSettlement;
import com.surprising.account.provider.model.OrderFeeSnapshot;
import com.surprising.account.provider.model.PositionState;
import com.surprising.account.provider.model.SpotInstrumentSpec;
import com.surprising.account.provider.service.MarginTransferMath;
import com.surprising.account.provider.service.PnlSettlementMath;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.product.api.ProductLine;
import com.surprising.product.api.ProductLineSql;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.PositionMode;
import com.surprising.trading.api.model.PositionSide;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class AccountRepository {

    private static final long PPM = 1_000_000L;

    private final JdbcTemplate jdbcTemplate;
    private final AccountSequenceRepository sequenceRepository;

    public AccountRepository(JdbcTemplate jdbcTemplate, AccountSequenceRepository sequenceRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.sequenceRepository = sequenceRepository;
    }

    public Optional<BalanceResponse> balance(long userId, String asset) {
        return jdbcTemplate.query("""
                SELECT b.user_id, b.asset, b.available_units, b.locked_units,
                       b.available_units + b.locked_units - COALESCE(d.deficit_units, 0) AS equity_units,
                       b.updated_at
                  FROM account_balances b
                  LEFT JOIN account_deficits d USING (user_id, asset)
                 WHERE b.user_id = ? AND b.asset = ?
                """, (rs, rowNum) -> new BalanceResponse(
                rs.getLong("user_id"),
                rs.getString("asset"),
                rs.getLong("available_units"),
                rs.getLong("locked_units"),
                rs.getLong("equity_units"),
                rs.getTimestamp("updated_at").toInstant()), userId, asset).stream().findFirst();
    }

    public List<BalanceResponse> balances(long userId) {
        return jdbcTemplate.query("""
                SELECT b.user_id, b.asset, b.available_units, b.locked_units,
                       b.available_units + b.locked_units - COALESCE(d.deficit_units, 0) AS equity_units,
                       b.updated_at
                  FROM account_balances b
                  LEFT JOIN account_deficits d USING (user_id, asset)
                 WHERE b.user_id = ?
                 ORDER BY b.asset ASC
                """, (rs, rowNum) -> new BalanceResponse(
                rs.getLong("user_id"),
                rs.getString("asset"),
                rs.getLong("available_units"),
                rs.getLong("locked_units"),
                rs.getLong("equity_units"),
                rs.getTimestamp("updated_at").toInstant()), userId);
    }

    public Optional<ProductBalanceResponse> productBalance(long userId, AccountType accountType, String asset) {
        AccountType normalizedType = requireAccountType(accountType);
        if (isLegacyPerpetualAccount(normalizedType)) {
            return balance(userId, asset).map(balance -> toProductBalance(normalizedType, balance));
        }
        return jdbcTemplate.query("""
                SELECT user_id, account_type, asset, available_units, locked_units,
                       available_units + locked_units - COALESCE(d.deficit_units, 0) AS equity_units,
                       b.updated_at
                  FROM account_product_balances b
                  LEFT JOIN account_product_deficits d USING (account_type, user_id, asset)
                 WHERE b.user_id = ?
                   AND b.account_type = ?
                   AND b.asset = ?
                """, (rs, rowNum) -> new ProductBalanceResponse(
                rs.getLong("user_id"),
                AccountType.valueOf(rs.getString("account_type")),
                rs.getString("asset"),
                rs.getLong("available_units"),
                rs.getLong("locked_units"),
                rs.getLong("equity_units"),
                rs.getTimestamp("updated_at").toInstant()), userId, normalizedType.name(), asset)
                .stream().findFirst();
    }

    public List<ProductBalanceResponse> productBalances(long userId, AccountType accountType) {
        if (accountType != null && isLegacyPerpetualAccount(accountType)) {
            return balances(userId).stream()
                    .map(balance -> toProductBalance(accountType, balance))
                    .toList();
        }
        if (accountType != null) {
            return productBalancesFromTable(userId, accountType);
        }
        List<ProductBalanceResponse> legacyBalances = balances(userId).stream()
                .map(balance -> toProductBalance(AccountType.USDT_PERPETUAL, balance))
                .toList();
        List<ProductBalanceResponse> isolatedBalances = productBalancesFromTable(userId, null);
        return java.util.stream.Stream.concat(legacyBalances.stream(), isolatedBalances.stream())
                .toList();
    }

    public List<AccountLedgerEntryResponse> accountLedger(Long userId,
                                                          String asset,
                                                          String referenceType,
                                                          int limit) {
        return accountLedgerPage(userId, asset, referenceType, limit, null, null).items();
    }

    public AdminCursorPage.CursorPage<AccountLedgerEntryResponse> accountLedgerPage(Long userId,
                                                                                    String asset,
                                                                                    String referenceType,
                                                                                    int limit,
                                                                                    String cursor,
                                                                                    String sort) {
        String normalizedAsset = emptyToNull(asset);
        String normalizedReferenceType = emptyToNull(referenceType);
        int safeLimit = AdminCursorPage.limit(limit, 1000);
        AdminCursorPage.SortSpec sortSpec = parseCreatedAtSort(sort, "entry_id");
        AdminCursorPage.Cursor decodedCursor = AdminCursorPage.decodeCursor(cursor);
        List<Object> args = new ArrayList<>();
        args.add(userId);
        args.add(userId);
        args.add(normalizedAsset);
        args.add(normalizedAsset);
        args.add(normalizedReferenceType);
        args.add(normalizedReferenceType);
        AdminCursorPage.addCursorArgs(args, decodedCursor);
        args.add(safeLimit + 1);
        List<AccountLedgerEntryResponse> rows = jdbcTemplate.query("""
                SELECT entry_id, user_id, asset, amount_units, balance_after_units, reference_type,
                       reference_id, reason, trade_id, order_id, symbol, fee_rate_ppm, created_at
                  FROM account_ledger_entries
                 WHERE (CAST(? AS text) IS NULL OR user_id = ?)
                   AND (CAST(? AS text) IS NULL OR asset = ?)
                   AND (CAST(? AS text) IS NULL OR reference_type = ?)
                %s
                 ORDER BY %s %s, %s %s
                 LIMIT ?
                """.formatted(AdminCursorPage.seekCondition(sortSpec, decodedCursor),
                        sortSpec.column(), sortSpec.directionSql(), sortSpec.idColumn(), sortSpec.directionSql()),
                (rs, rowNum) -> new AccountLedgerEntryResponse(
                rs.getLong("entry_id"),
                rs.getLong("user_id"),
                rs.getString("asset"),
                rs.getLong("amount_units"),
                rs.getLong("balance_after_units"),
                rs.getString("reference_type"),
                rs.getString("reference_id"),
                rs.getString("reason"),
                nullableLong(rs, "trade_id"),
                nullableLong(rs, "order_id"),
                rs.getString("symbol"),
                nullableLong(rs, "fee_rate_ppm"),
                rs.getTimestamp("created_at").toInstant()), args.toArray());
        return AdminCursorPage.page(rows, safeLimit, sortSpec, AccountLedgerEntryResponse::createdAt,
                AccountLedgerEntryResponse::entryId);
    }

    public List<ProductLedgerEntryResponse> productLedger(Long userId,
                                                          AccountType accountType,
                                                          String asset,
                                                          String referenceType,
                                                          int limit) {
        return productLedgerPage(userId, accountType, asset, referenceType, limit, null, null).items();
    }

    public AdminCursorPage.CursorPage<ProductLedgerEntryResponse> productLedgerPage(Long userId,
                                                                                    AccountType accountType,
                                                                                    String asset,
                                                                                    String referenceType,
                                                                                    int limit,
                                                                                    String cursor,
                                                                                    String sort) {
        String normalizedAsset = emptyToNull(asset);
        String normalizedReferenceType = emptyToNull(referenceType);
        String normalizedAccountType = accountType == null ? null : accountType.name();
        int safeLimit = AdminCursorPage.limit(limit, 1000);
        AdminCursorPage.SortSpec sortSpec = parseCreatedAtSort(sort, "entry_id");
        AdminCursorPage.Cursor decodedCursor = AdminCursorPage.decodeCursor(cursor);
        List<Object> args = new ArrayList<>();
        args.add(userId);
        args.add(userId);
        args.add(normalizedAccountType);
        args.add(normalizedAccountType);
        args.add(normalizedAsset);
        args.add(normalizedAsset);
        args.add(normalizedReferenceType);
        args.add(normalizedReferenceType);
        AdminCursorPage.addCursorArgs(args, decodedCursor);
        args.add(safeLimit + 1);
        List<ProductLedgerEntryResponse> rows = jdbcTemplate.query("""
                SELECT entry_id, user_id, account_type, asset, amount_units, balance_after_units,
                       reference_type, reference_id, reason, created_at
                  FROM account_product_ledger_entries
                 WHERE (CAST(? AS text) IS NULL OR user_id = ?)
                   AND (CAST(? AS text) IS NULL OR account_type = ?)
                   AND (CAST(? AS text) IS NULL OR asset = ?)
                   AND (CAST(? AS text) IS NULL OR reference_type = ?)
                %s
                 ORDER BY %s %s, %s %s
                 LIMIT ?
                """.formatted(AdminCursorPage.seekCondition(sortSpec, decodedCursor),
                        sortSpec.column(), sortSpec.directionSql(), sortSpec.idColumn(), sortSpec.directionSql()),
                (rs, rowNum) -> new ProductLedgerEntryResponse(
                rs.getLong("entry_id"),
                rs.getLong("user_id"),
                AccountType.valueOf(rs.getString("account_type")),
                rs.getString("asset"),
                rs.getLong("amount_units"),
                rs.getLong("balance_after_units"),
                rs.getString("reference_type"),
                rs.getString("reference_id"),
                rs.getString("reason"),
                rs.getTimestamp("created_at").toInstant()), args.toArray());
        return AdminCursorPage.page(rows, safeLimit, sortSpec, ProductLedgerEntryResponse::createdAt,
                ProductLedgerEntryResponse::entryId);
    }

    public List<ProductTransferRecordResponse> productTransfers(Long userId,
                                                                AccountType accountType,
                                                                String asset,
                                                                int limit) {
        String normalizedAsset = emptyToNull(asset);
        String normalizedAccountType = accountType == null ? null : accountType.name();
        int safeLimit = Math.max(1, Math.min(limit, 1000));
        return jdbcTemplate.query("""
                SELECT transfer_id, user_id, source_account_type, target_account_type, asset, amount_units,
                       reference_id, status, reason, created_at, updated_at
                  FROM account_product_transfers
                 WHERE (CAST(? AS text) IS NULL OR user_id = ?)
                   AND (CAST(? AS text) IS NULL OR source_account_type = ? OR target_account_type = ?)
                   AND (CAST(? AS text) IS NULL OR asset = ?)
                 ORDER BY created_at DESC, transfer_id DESC
                 LIMIT ?
                """, (rs, rowNum) -> new ProductTransferRecordResponse(
                rs.getLong("transfer_id"),
                rs.getLong("user_id"),
                AccountType.valueOf(rs.getString("source_account_type")),
                AccountType.valueOf(rs.getString("target_account_type")),
                rs.getString("asset"),
                rs.getLong("amount_units"),
                rs.getString("reference_id"),
                rs.getString("status"),
                rs.getString("reason"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()), userId, userId,
                normalizedAccountType, normalizedAccountType, normalizedAccountType,
                normalizedAsset, normalizedAsset, safeLimit);
    }

    public AdminCursorPage.CursorPage<ProductTransferRecordResponse> productTransferPage(Long userId,
                                                                                         AccountType accountType,
                                                                                         String asset,
                                                                                         int limit,
                                                                                         String cursor,
                                                                                         String sort) {
        String normalizedAsset = emptyToNull(asset);
        String normalizedAccountType = accountType == null ? null : accountType.name();
        int safeLimit = AdminCursorPage.limit(limit, 1000);
        AdminCursorPage.SortSpec sortSpec = parseCreatedAtSort(sort, "transfer_id");
        AdminCursorPage.Cursor decodedCursor = AdminCursorPage.decodeCursor(cursor);
        List<Object> args = new ArrayList<>();
        args.add(userId);
        args.add(userId);
        args.add(normalizedAccountType);
        args.add(normalizedAccountType);
        args.add(normalizedAccountType);
        args.add(normalizedAsset);
        args.add(normalizedAsset);
        AdminCursorPage.addCursorArgs(args, decodedCursor);
        args.add(safeLimit + 1);
        List<ProductTransferRecordResponse> rows = jdbcTemplate.query("""
                SELECT transfer_id, user_id, source_account_type, target_account_type, asset, amount_units,
                       reference_id, status, reason, created_at, updated_at
                  FROM account_product_transfers
                 WHERE (CAST(? AS text) IS NULL OR user_id = ?)
                   AND (CAST(? AS text) IS NULL OR source_account_type = ? OR target_account_type = ?)
                   AND (CAST(? AS text) IS NULL OR asset = ?)
                %s
                 ORDER BY %s %s, %s %s
                 LIMIT ?
                """.formatted(AdminCursorPage.seekCondition(sortSpec, decodedCursor),
                        sortSpec.column(), sortSpec.directionSql(), sortSpec.idColumn(), sortSpec.directionSql()),
                (rs, rowNum) -> new ProductTransferRecordResponse(
                rs.getLong("transfer_id"),
                rs.getLong("user_id"),
                AccountType.valueOf(rs.getString("source_account_type")),
                AccountType.valueOf(rs.getString("target_account_type")),
                rs.getString("asset"),
                rs.getLong("amount_units"),
                rs.getString("reference_id"),
                rs.getString("status"),
                rs.getString("reason"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()), args.toArray());
        return AdminCursorPage.page(rows, safeLimit, sortSpec, ProductTransferRecordResponse::createdAt,
                ProductTransferRecordResponse::transferId);
    }

    public AdminBalanceAdjustmentRecord recordAdminBalanceAdjustment(String adjustmentKind,
                                                                     long adminUserId,
                                                                     String adminUsername,
                                                                     long userId,
                                                                     AccountType accountType,
                                                                     String asset,
                                                                     long amountUnits,
                                                                     long balanceAfterUnits,
                                                                     String referenceId,
                                                                     String reason) {
        String normalizedKind = requireAdjustmentKind(adjustmentKind);
        String referenceKey = adminAdjustmentReferenceKey(normalizedKind, userId, accountType, asset, referenceId);
        return jdbcTemplate.queryForObject("""
                INSERT INTO account_admin_balance_adjustments (
                    reference_key, adjustment_kind, admin_user_id, admin_username, user_id, account_type,
                    asset, amount_units, balance_after_units, reference_id, reason, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (reference_key) DO UPDATE
                   SET reference_key = EXCLUDED.reference_key
                RETURNING adjustment_id, adjustment_kind, admin_user_id, admin_username, user_id, account_type,
                          asset, amount_units, balance_after_units, reference_id, reason, created_at
                """, (rs, rowNum) -> new AdminBalanceAdjustmentRecord(
                rs.getLong("adjustment_id"),
                rs.getString("adjustment_kind"),
                rs.getLong("admin_user_id"),
                rs.getString("admin_username"),
                rs.getLong("user_id"),
                nullableAccountType(rs.getString("account_type")),
                rs.getString("asset"),
                rs.getLong("amount_units"),
                rs.getLong("balance_after_units"),
                rs.getString("reference_id"),
                rs.getString("reason"),
                rs.getTimestamp("created_at").toInstant()),
                referenceKey, normalizedKind, adminUserId, emptyToNull(adminUsername), userId,
                accountType == null ? null : accountType.name(), asset, amountUnits, balanceAfterUnits,
                referenceId, reason, Timestamp.from(Instant.now()));
    }

    public List<AdminBalanceAdjustmentRecord> adminBalanceAdjustments(Long adminUserId,
                                                                      Long userId,
                                                                      String adjustmentKind,
                                                                      AccountType accountType,
                                                                      String asset,
                                                                      String referenceId,
                                                                      int limit) {
        String normalizedKind = emptyToNull(adjustmentKind);
        String normalizedAccountType = accountType == null ? null : accountType.name();
        String normalizedAsset = emptyToNull(asset);
        String normalizedReferenceId = emptyToNull(referenceId);
        int safeLimit = Math.max(1, Math.min(limit, 1000));
        return jdbcTemplate.query("""
                SELECT adjustment_id, adjustment_kind, admin_user_id, admin_username, user_id, account_type,
                       asset, amount_units, balance_after_units, reference_id, reason, created_at
                  FROM account_admin_balance_adjustments
                 WHERE (CAST(? AS text) IS NULL OR admin_user_id = ?)
                   AND (CAST(? AS text) IS NULL OR user_id = ?)
                   AND (CAST(? AS text) IS NULL OR adjustment_kind = ?)
                   AND (CAST(? AS text) IS NULL OR account_type = ?)
                   AND (CAST(? AS text) IS NULL OR asset = ?)
                   AND (CAST(? AS text) IS NULL OR reference_id = ?)
                 ORDER BY created_at DESC, adjustment_id DESC
                 LIMIT ?
                """, (rs, rowNum) -> new AdminBalanceAdjustmentRecord(
                rs.getLong("adjustment_id"),
                rs.getString("adjustment_kind"),
                rs.getLong("admin_user_id"),
                rs.getString("admin_username"),
                rs.getLong("user_id"),
                nullableAccountType(rs.getString("account_type")),
                rs.getString("asset"),
                rs.getLong("amount_units"),
                rs.getLong("balance_after_units"),
                rs.getString("reference_id"),
                rs.getString("reason"),
                rs.getTimestamp("created_at").toInstant()), adminUserId, adminUserId, userId, userId,
                normalizedKind, normalizedKind, normalizedAccountType, normalizedAccountType,
                normalizedAsset, normalizedAsset, normalizedReferenceId, normalizedReferenceId, safeLimit);
    }

    public AdminCursorPage.CursorPage<AdminBalanceAdjustmentRecord> adminBalanceAdjustmentPage(Long adminUserId,
                                                                                               Long userId,
                                                                                               String adjustmentKind,
                                                                                               AccountType accountType,
                                                                                               String asset,
                                                                                               String referenceId,
                                                                                               int limit,
                                                                                               String cursor,
                                                                                               String sort) {
        String normalizedKind = emptyToNull(adjustmentKind);
        String normalizedAccountType = accountType == null ? null : accountType.name();
        String normalizedAsset = emptyToNull(asset);
        String normalizedReferenceId = emptyToNull(referenceId);
        int safeLimit = AdminCursorPage.limit(limit, 1000);
        AdminCursorPage.SortSpec sortSpec = parseCreatedAtSort(sort, "adjustment_id");
        AdminCursorPage.Cursor decodedCursor = AdminCursorPage.decodeCursor(cursor);
        List<Object> args = new ArrayList<>();
        args.add(adminUserId);
        args.add(adminUserId);
        args.add(userId);
        args.add(userId);
        args.add(normalizedKind);
        args.add(normalizedKind);
        args.add(normalizedAccountType);
        args.add(normalizedAccountType);
        args.add(normalizedAsset);
        args.add(normalizedAsset);
        args.add(normalizedReferenceId);
        args.add(normalizedReferenceId);
        AdminCursorPage.addCursorArgs(args, decodedCursor);
        args.add(safeLimit + 1);
        List<AdminBalanceAdjustmentRecord> rows = jdbcTemplate.query("""
                SELECT adjustment_id, adjustment_kind, admin_user_id, admin_username, user_id, account_type,
                       asset, amount_units, balance_after_units, reference_id, reason, created_at
                  FROM account_admin_balance_adjustments
                 WHERE (CAST(? AS text) IS NULL OR admin_user_id = ?)
                   AND (CAST(? AS text) IS NULL OR user_id = ?)
                   AND (CAST(? AS text) IS NULL OR adjustment_kind = ?)
                   AND (CAST(? AS text) IS NULL OR account_type = ?)
                   AND (CAST(? AS text) IS NULL OR asset = ?)
                   AND (CAST(? AS text) IS NULL OR reference_id = ?)
                %s
                 ORDER BY %s %s, %s %s
                 LIMIT ?
                """.formatted(AdminCursorPage.seekCondition(sortSpec, decodedCursor),
                        sortSpec.column(), sortSpec.directionSql(), sortSpec.idColumn(), sortSpec.directionSql()),
                (rs, rowNum) -> new AdminBalanceAdjustmentRecord(
                rs.getLong("adjustment_id"),
                rs.getString("adjustment_kind"),
                rs.getLong("admin_user_id"),
                rs.getString("admin_username"),
                rs.getLong("user_id"),
                nullableAccountType(rs.getString("account_type")),
                rs.getString("asset"),
                rs.getLong("amount_units"),
                rs.getLong("balance_after_units"),
                rs.getString("reference_id"),
                rs.getString("reason"),
                rs.getTimestamp("created_at").toInstant()), args.toArray());
        return AdminCursorPage.page(rows, safeLimit, sortSpec, AdminBalanceAdjustmentRecord::createdAt,
                AdminBalanceAdjustmentRecord::adjustmentId);
    }

    private AdminCursorPage.SortSpec parseCreatedAtSort(String sort, String idColumn) {
        AdminCursorPage.SortSpec createdAtDesc = new AdminCursorPage.SortSpec(
                "createdAt", "created_at", idColumn, true);
        AdminCursorPage.SortSpec createdAtAsc = new AdminCursorPage.SortSpec(
                "createdAt", "created_at", idColumn, false);
        return AdminCursorPage.parseSort(sort, createdAtDesc, List.of(createdAtDesc, createdAtAsc));
    }

    private List<ProductBalanceResponse> productBalancesFromTable(long userId, AccountType accountType) {
        StringBuilder sql = new StringBuilder("""
                SELECT user_id, account_type, asset, available_units, locked_units,
                       available_units + locked_units - COALESCE(d.deficit_units, 0) AS equity_units,
                       b.updated_at
                  FROM account_product_balances b
                  LEFT JOIN account_product_deficits d USING (account_type, user_id, asset)
                 WHERE b.user_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(userId);
        if (accountType == null) {
            sql.append("   AND b.account_type <> 'USDT_PERPETUAL'\n");
        } else {
            sql.append("   AND b.account_type = ?\n");
            args.add(accountType.name());
        }
        sql.append("""
                 ORDER BY account_type ASC, asset ASC
                """);
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new ProductBalanceResponse(
                rs.getLong("user_id"),
                AccountType.valueOf(rs.getString("account_type")),
                rs.getString("asset"),
                rs.getLong("available_units"),
                rs.getLong("locked_units"),
                rs.getLong("equity_units"),
                rs.getTimestamp("updated_at").toInstant()), args.toArray());
    }

    public ProductBalanceResponse adjustProductBalance(long userId,
                                                       AccountType accountType,
                                                       String asset,
                                                       long amountUnits,
                                                       String referenceId,
                                                       String reason) {
        AccountType normalizedType = requireAccountType(accountType);
        if (isLegacyPerpetualAccount(normalizedType)) {
            BalanceResponse updated = adjustBalance(userId, asset, amountUnits,
                    normalizedType.name() + ":" + referenceId, reason);
            return toProductBalance(normalizedType, updated);
        }
        Instant now = Instant.now();
        int ledgerRows = jdbcTemplate.update("""
                INSERT INTO account_product_ledger_entries (
                    entry_id, user_id, account_type, asset, amount_units, balance_after_units,
                    reference_type, reference_id, reason, created_at
                ) VALUES (?, ?, ?, ?, ?, 0, 'PRODUCT_BALANCE_ADJUSTMENT', ?, ?, ?)
                ON CONFLICT (reference_type, reference_id, user_id, account_type, asset) DO NOTHING
                """, sequenceRepository.nextSequence("product-ledger-entry"), userId, normalizedType.name(), asset,
                amountUnits, referenceId, reason, Timestamp.from(now));
        if (ledgerRows == 0) {
            requireDuplicateProductBalanceAdjustmentMatches(userId, normalizedType, asset, amountUnits, referenceId,
                    reason);
            return productBalance(userId, normalizedType, asset)
                    .orElseThrow(() -> new IllegalStateException("duplicate product adjustment but balance missing"));
        }
        long nextAvailable = applyProductAvailableDelta(userId, normalizedType, asset, amountUnits, now);
        int ledgerRowsAfter = jdbcTemplate.update("""
                UPDATE account_product_ledger_entries
                   SET balance_after_units = ?
                 WHERE reference_type = 'PRODUCT_BALANCE_ADJUSTMENT'
                   AND reference_id = ?
                   AND user_id = ?
                   AND account_type = ?
                   AND asset = ?
                """, nextAvailable, referenceId, userId, normalizedType.name(), asset);
        requireSingleRow(ledgerRowsAfter, "product balance adjustment ledger update");
        return productBalance(userId, normalizedType, asset)
                .orElseThrow(() -> new IllegalStateException("product balance not found after adjustment"));
    }

    public ProductTransferResponse transferProductBalance(long userId,
                                                          AccountType sourceAccountType,
                                                          AccountType targetAccountType,
                                                          String asset,
                                                          long amountUnits,
                                                          String referenceId,
                                                          String reason) {
        AccountType source = requireAccountType(sourceAccountType);
        AccountType target = requireAccountType(targetAccountType);
        if (source == target) {
            throw new IllegalArgumentException("source and target account types must be different");
        }
        if (amountUnits <= 0) {
            throw new IllegalArgumentException("amountUnits must be positive");
        }
        Instant now = Instant.now();
        long transferId = sequenceRepository.nextSequence("product-transfer");
        int rows = jdbcTemplate.update("""
                INSERT INTO account_product_transfers (
                    transfer_id, user_id, source_account_type, target_account_type, asset,
                    amount_units, reference_id, status, reason, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'COMPLETED', ?, ?, ?)
                ON CONFLICT (user_id, reference_id) DO NOTHING
                """, transferId, userId, source.name(), target.name(), asset, amountUnits, referenceId, reason,
                Timestamp.from(now), Timestamp.from(now));
        if (rows == 0) {
            return duplicateProductTransfer(userId, source, target, asset, amountUnits, referenceId, reason);
        }

        long sourceAfter = applyProductAvailableDelta(userId, source, asset, Math.negateExact(amountUnits), now);
        long targetAfter = applyProductAvailableDelta(userId, target, asset, amountUnits, now);
        insertProductTransferLedger(userId, source, asset, Math.negateExact(amountUnits), sourceAfter,
                referenceId + ":OUT", reason, now);
        insertProductTransferLedger(userId, target, asset, amountUnits, targetAfter,
                referenceId + ":IN", reason, now);

        return new ProductTransferResponse(transferId, userId, source, target, asset, amountUnits, referenceId,
                "COMPLETED",
                productBalance(userId, source, asset)
                        .orElseThrow(() -> new IllegalStateException("source balance missing after transfer")),
                productBalance(userId, target, asset)
                        .orElseThrow(() -> new IllegalStateException("target balance missing after transfer")),
                now);
    }

    public BalanceResponse adjustBalance(long userId, String asset, long amountUnits, String referenceId, String reason) {
        Instant now = Instant.now();
        int ledgerRows = jdbcTemplate.update("""
                INSERT INTO account_ledger_entries (
                    entry_id, user_id, asset, amount_units, balance_after_units,
                    reference_type, reference_id, reason, created_at
                ) VALUES (?, ?, ?, ?, 0, 'BALANCE_ADJUSTMENT', ?, ?, ?)
                ON CONFLICT (reference_type, reference_id, user_id, asset) DO NOTHING
                """, sequenceRepository.nextSequence("ledger-entry"), userId, asset, amountUnits,
                referenceId, reason, Timestamp.from(now));
        if (ledgerRows == 0) {
            requireDuplicateBalanceAdjustmentMatches(userId, asset, amountUnits, referenceId, reason);
            return balance(userId, asset)
                    .orElseThrow(() -> new IllegalStateException("duplicate balance adjustment but balance missing"));
        }
        jdbcTemplate.update("""
                INSERT INTO account_balances (user_id, asset, available_units, locked_units, updated_at)
                VALUES (?, ?, 0, 0, ?)
                ON CONFLICT (user_id, asset) DO NOTHING
                """, userId, asset, Timestamp.from(now));
        Long currentAvailable = jdbcTemplate.queryForObject("""
                SELECT available_units
                  FROM account_balances
                 WHERE user_id = ? AND asset = ?
                 FOR UPDATE
                """, Long.class, userId, asset);
        long nextAvailable = Math.addExact(currentAvailable == null ? 0L : currentAvailable, amountUnits);
        if (nextAvailable < 0) {
            throw new IllegalArgumentException("insufficient available balance");
        }
        int balanceRows = jdbcTemplate.update("""
                UPDATE account_balances
                   SET available_units = ?,
                       updated_at = ?
                 WHERE user_id = ? AND asset = ?
                """, nextAvailable, Timestamp.from(now), userId, asset);
        requireSingleRow(balanceRows, "balance adjustment update");
        BalanceResponse updated = balance(userId, asset)
                .orElseThrow(() -> new IllegalStateException("balance not found after adjustment"));
        int ledgerRowsAfter = jdbcTemplate.update("""
                UPDATE account_ledger_entries
                   SET balance_after_units = ?
                 WHERE reference_type = 'BALANCE_ADJUSTMENT'
                   AND reference_id = ?
                   AND user_id = ?
                   AND asset = ?
                """, updated.availableUnits(), referenceId, userId, asset);
        requireSingleRow(ledgerRowsAfter, "balance adjustment ledger update");
        return updated;
    }

    private void requireDuplicateBalanceAdjustmentMatches(long userId,
                                                          String asset,
                                                          long amountUnits,
                                                          String referenceId,
                                                          String reason) {
        AdjustmentReference existing = jdbcTemplate.query("""
                SELECT amount_units, reason
                  FROM account_ledger_entries
                 WHERE reference_type = 'BALANCE_ADJUSTMENT'
                   AND reference_id = ?
                   AND user_id = ?
                   AND asset = ?
                 FOR UPDATE
                """, (rs, rowNum) -> new AdjustmentReference(
                rs.getLong("amount_units"),
                rs.getString("reason")), referenceId, userId, asset).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("duplicate balance adjustment but ledger missing"));
        if (existing.amountUnits() != amountUnits || !Objects.equals(existing.reason(), reason)) {
            throw new IllegalStateException("conflicting duplicate balance adjustment reference " + referenceId);
        }
    }

    public Optional<PositionResponse> position(long userId, String symbol, MarginMode marginMode) {
        return position(userId, symbol, marginMode, PositionSide.NET);
    }

    public PositionModeResponse positionMode(long userId) {
        return positionMode(ProductLine.LINEAR_PERPETUAL, userId);
    }

    public PositionModeResponse positionMode(ProductLine productLine, long userId) {
        ProductLine resolvedProductLine = productLine(productLine);
        return jdbcTemplate.query("""
                SELECT position_mode, updated_at
                  FROM account_position_modes
                 WHERE product_line = ?
                   AND user_id = ?
                """, (rs, rowNum) -> new PositionModeResponse(
                resolvedProductLine,
                userId,
                PositionMode.fromNullableDbValue(rs.getString("position_mode")),
                rs.getTimestamp("updated_at").toInstant()), resolvedProductLine.name(), userId).stream().findFirst()
                .orElse(new PositionModeResponse(resolvedProductLine, userId, PositionMode.ONE_WAY, Instant.EPOCH));
    }

    @Transactional
    public PositionModeResponse updatePositionMode(long userId, PositionMode positionMode, Instant now) {
        return updatePositionMode(ProductLine.LINEAR_PERPETUAL, userId, positionMode, now);
    }

    @Transactional
    public PositionModeResponse updatePositionMode(ProductLine productLine,
                                                   long userId,
                                                   PositionMode positionMode,
                                                   Instant now) {
        ProductLine resolvedProductLine = productLine(productLine);
        PositionMode normalizedMode = PositionMode.defaultIfNull(positionMode);
        lockUserPositionMode(resolvedProductLine, userId);
        PositionMode current = positionMode(resolvedProductLine, userId).positionMode();
        if (current == normalizedMode) {
            return new PositionModeResponse(resolvedProductLine, userId, current, now);
        }
        requirePositionModeSwitchable(resolvedProductLine, userId);
        int rows = jdbcTemplate.update("""
                INSERT INTO account_position_modes (product_line, user_id, position_mode, updated_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (product_line, user_id) DO UPDATE
                   SET position_mode = EXCLUDED.position_mode,
                       updated_at = EXCLUDED.updated_at
                """, resolvedProductLine.name(), userId, normalizedMode.name(), Timestamp.from(now));
        requireSingleRow(rows, "position mode upsert");
        return new PositionModeResponse(resolvedProductLine, userId, normalizedMode, now);
    }

    private void lockUserPositionMode(long userId) {
        lockUserPositionMode(ProductLine.LINEAR_PERPETUAL, userId);
    }

    private void lockUserPositionMode(ProductLine productLine, long userId) {
        jdbcTemplate.query("""
                SELECT pg_advisory_xact_lock(hashtext('position-mode'), hashtext(?))
                """, rs -> null, productLine(productLine).name() + ":" + userId);
    }

    private void requirePositionModeSwitchable(long userId) {
        requirePositionModeSwitchable(ProductLine.LINEAR_PERPETUAL, userId);
    }

    private void requirePositionModeSwitchable(ProductLine productLine, long userId) {
        String productLineName = productLine(productLine).name();
        Boolean hasPositions = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM account_positions p
                     WHERE p.user_id = ?
                       AND p.signed_quantity_steps <> 0
                       AND p.product_line = ?
                )
                """, Boolean.class, userId, productLineName);
        if (Boolean.TRUE.equals(hasPositions)) {
            throw new IllegalStateException("position mode switch requires no open positions");
        }
        Boolean hasOpenOrders = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM trading_orders o
                     WHERE o.user_id = ?
                       AND o.status IN ('ACCEPTED', 'PARTIALLY_FILLED', 'CANCEL_REQUESTED')
                       AND o.remaining_quantity_steps > 0
                       AND o.product_line = ?
                )
                """, Boolean.class, userId, productLineName);
        if (Boolean.TRUE.equals(hasOpenOrders)) {
            throw new IllegalStateException("position mode switch requires no active orders");
        }
        Boolean hasTriggerOrders = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM trading_trigger_orders t
                     WHERE t.user_id = ?
                       AND t.status IN ('PENDING', 'TRIGGERING')
                       AND t.product_line = ?
                )
                """, Boolean.class, userId, productLineName);
        if (Boolean.TRUE.equals(hasTriggerOrders)) {
            throw new IllegalStateException("position mode switch requires no pending trigger orders");
        }
        Boolean hasAlgoOrders = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM trading_algo_orders a
                     WHERE a.user_id = ?
                       AND a.status IN ('PENDING', 'RUNNING', 'CANCEL_REQUESTED')
                       AND a.product_line = ?
                )
                """, Boolean.class, userId, productLineName);
        if (Boolean.TRUE.equals(hasAlgoOrders)) {
            throw new IllegalStateException("position mode switch requires no active algo orders");
        }
        Boolean hasUnsettledTrades = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM trading_match_trades mt
                      LEFT JOIN account_processed_trades pt
                        ON pt.product_line = mt.product_line
                       AND pt.symbol = mt.symbol
                       AND pt.trade_id = mt.trade_id
                     WHERE (mt.taker_user_id = ? OR mt.maker_user_id = ?)
                       AND pt.trade_id IS NULL
                       AND mt.product_line = ?
                )
                """, Boolean.class, userId, userId, productLineName);
        if (Boolean.TRUE.equals(hasUnsettledTrades)) {
            throw new IllegalStateException("position mode switch requires all matched trades to be settled");
        }
        Boolean hasActiveReservations = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM account_margin_reservations
                     WHERE user_id = ?
                       AND account_type = ?
                       AND status NOT IN ('RELEASED', 'CONSUMED')
                )
                """, Boolean.class, userId, productLine(productLine).accountTypeCode());
        if (Boolean.TRUE.equals(hasActiveReservations)) {
            throw new IllegalStateException("position mode switch requires no active margin reservations");
        }
    }

    public Optional<PositionResponse> position(long userId, String symbol, MarginMode marginMode,
                                               PositionSide positionSide) {
        return jdbcTemplate.query("""
                SELECT user_id, symbol, margin_mode, position_side, instrument_version, signed_quantity_steps,
                       entry_price_ticks, realized_pnl_units, updated_at
                  FROM account_positions
                 WHERE user_id = ? AND symbol = ? AND margin_mode = ? AND position_side = ?
                """, (rs, rowNum) -> toPositionResponse(rs), userId, symbol,
                MarginMode.defaultIfNull(marginMode).name(), PositionSide.defaultIfNull(positionSide).name())
                .stream().findFirst();
    }

    public Optional<PositionResponse> position(ProductLine productLine,
                                               long userId,
                                               String symbol,
                                               MarginMode marginMode,
                                               PositionSide positionSide) {
        ProductLine resolvedProductLine = productLine(productLine);
        return jdbcTemplate.query("""
                SELECT user_id, symbol, margin_mode, position_side, instrument_version,
                       signed_quantity_steps, entry_price_ticks, realized_pnl_units, updated_at
                 FROM account_positions
                 WHERE product_line = ?
                   AND user_id = ?
                   AND symbol = ?
                   AND margin_mode = ?
                   AND position_side = ?
                """, (rs, rowNum) -> toPositionResponse(rs),
                resolvedProductLine.name(), userId, symbol,
                MarginMode.defaultIfNull(marginMode).name(), PositionSide.defaultIfNull(positionSide).name())
                .stream().findFirst();
    }

    public List<PositionResponse> positions(long userId) {
        return positions(userId, null);
    }

    public List<PositionResponse> positions(long userId, PositionSide positionSide) {
        String normalizedPositionSide = positionSide == null ? null : PositionSide.defaultIfNull(positionSide).name();
        return jdbcTemplate.query("""
                SELECT user_id, symbol, margin_mode, position_side, instrument_version, signed_quantity_steps,
                       entry_price_ticks, realized_pnl_units, updated_at
                  FROM account_positions
                 WHERE user_id = ?
                   AND (CAST(? AS text) IS NULL OR position_side = ?)
                   AND signed_quantity_steps <> 0
                 ORDER BY symbol ASC, margin_mode ASC, position_side ASC
                """, (rs, rowNum) -> toPositionResponse(rs), userId, normalizedPositionSide,
                normalizedPositionSide);
    }

    public List<PositionResponse> positions(ProductLine productLine, long userId, PositionSide positionSide) {
        ProductLine resolvedProductLine = productLine(productLine);
        String normalizedPositionSide = positionSide == null ? null : PositionSide.defaultIfNull(positionSide).name();
        return jdbcTemplate.query("""
                SELECT user_id, symbol, margin_mode, position_side, instrument_version,
                       signed_quantity_steps, entry_price_ticks, realized_pnl_units, updated_at
                  FROM account_positions
                 WHERE product_line = ?
                   AND user_id = ?
                   AND (CAST(? AS text) IS NULL OR position_side = ?)
                   AND signed_quantity_steps <> 0
                 ORDER BY symbol ASC, margin_mode ASC, position_side ASC
                """, (rs, rowNum) -> toPositionResponse(rs), resolvedProductLine.name(), userId,
                normalizedPositionSide, normalizedPositionSide);
    }

    public List<PositionResponse> openPositionsForSettlement(String symbol, long instrumentVersion) {
        return jdbcTemplate.query("""
                SELECT user_id, symbol, margin_mode, position_side, instrument_version, signed_quantity_steps,
                       entry_price_ticks, realized_pnl_units, updated_at
                  FROM account_positions
                 WHERE symbol = ?
                   AND instrument_version = ?
                   AND signed_quantity_steps <> 0
                 ORDER BY user_id ASC, margin_mode ASC, position_side ASC
                 FOR UPDATE
                """, (rs, rowNum) -> toPositionResponse(rs), symbol, instrumentVersion);
    }

    public Optional<PositionMarginResponse> positionMargin(long userId, String symbol, MarginMode marginMode) {
        return positionMargin(userId, symbol, marginMode, PositionSide.NET);
    }

    public Optional<PositionMarginResponse> positionMargin(long userId, String symbol, MarginMode marginMode,
                                                          PositionSide positionSide) {
        MarginMode normalizedMarginMode = MarginMode.defaultIfNull(marginMode);
        PositionSide normalizedPositionSide = PositionSide.defaultIfNull(positionSide);
        return jdbcTemplate.query("""
                SELECT p.user_id,
                       p.symbol,
                       i.settle_asset AS asset,
                       p.margin_mode,
                       p.position_side,
                       COALESCE(m.margin_units, 0) AS margin_units,
                       COALESCE(m.updated_at, p.updated_at) AS updated_at
                  FROM account_positions p
                  JOIN instruments i
                    ON i.symbol = p.symbol
                   AND i.version = p.instrument_version
                  LEFT JOIN account_position_margins m
                    ON m.user_id = p.user_id
                   AND m.symbol = p.symbol
                   AND m.asset = i.settle_asset
                   AND m.margin_mode = p.margin_mode
                   AND m.position_side = p.position_side
                 WHERE p.user_id = ?
                   AND p.symbol = ?
                   AND p.margin_mode = ?
                   AND p.position_side = ?
                """, (rs, rowNum) -> new PositionMarginResponse(
                rs.getLong("user_id"),
                rs.getString("symbol"),
                rs.getString("asset"),
                MarginMode.fromNullableDbValue(rs.getString("margin_mode")),
                PositionSide.fromNullableDbValue(rs.getString("position_side")),
                rs.getLong("margin_units"),
                rs.getTimestamp("updated_at").toInstant()), userId, symbol, normalizedMarginMode.name(),
                normalizedPositionSide.name())
                .stream()
                .findFirst();
    }

    public Optional<PositionMarginResponse> positionMargin(ProductLine productLine,
                                                           long userId,
                                                           String symbol,
                                                           MarginMode marginMode,
                                                           PositionSide positionSide) {
        ProductLine resolvedProductLine = productLine(productLine);
        MarginMode normalizedMarginMode = MarginMode.defaultIfNull(marginMode);
        PositionSide normalizedPositionSide = PositionSide.defaultIfNull(positionSide);
        return jdbcTemplate.query("""
                SELECT p.user_id,
                       p.symbol,
                       i.settle_asset AS asset,
                       p.margin_mode,
                       p.position_side,
                       COALESCE(m.margin_units, 0) AS margin_units,
                       COALESCE(m.updated_at, p.updated_at) AS updated_at
                  FROM account_positions p
                  JOIN instruments i
                    ON i.symbol = p.symbol
                   AND i.version = p.instrument_version
                  LEFT JOIN account_position_margins m
                    ON m.product_line = p.product_line
                   AND m.user_id = p.user_id
                   AND m.symbol = p.symbol
                   AND m.asset = i.settle_asset
                   AND m.margin_mode = p.margin_mode
                   AND m.position_side = p.position_side
                 WHERE p.product_line = ?
                   AND p.user_id = ?
                   AND p.symbol = ?
                   AND p.margin_mode = ?
                   AND p.position_side = ?
                """, (rs, rowNum) -> new PositionMarginResponse(
                rs.getLong("user_id"),
                rs.getString("symbol"),
                rs.getString("asset"),
                MarginMode.fromNullableDbValue(rs.getString("margin_mode")),
                PositionSide.fromNullableDbValue(rs.getString("position_side")),
                rs.getLong("margin_units"),
                rs.getTimestamp("updated_at").toInstant()), resolvedProductLine.name(), userId, symbol,
                normalizedMarginMode.name(), normalizedPositionSide.name())
                .stream()
                .findFirst();
    }

    public PositionMarginAdjustmentResponse adjustIsolatedPositionMargin(long userId,
                                                                         String symbol,
                                                                         long amountUnits,
                                                                         String referenceId,
                                                                         String reason,
                                                                         Duration maxRiskSnapshotAge,
                                                                         long removalBufferPpm) {
        return adjustIsolatedPositionMargin(userId, symbol, PositionSide.NET, amountUnits, referenceId, reason,
                maxRiskSnapshotAge, removalBufferPpm);
    }

    public PositionMarginAdjustmentResponse adjustIsolatedPositionMargin(ProductLine productLine,
                                                                         long userId,
                                                                         String symbol,
                                                                         long amountUnits,
                                                                         String referenceId,
                                                                         String reason,
                                                                         Duration maxRiskSnapshotAge,
                                                                         long removalBufferPpm) {
        return adjustIsolatedPositionMargin(productLine, userId, symbol, PositionSide.NET, amountUnits, referenceId,
                reason, maxRiskSnapshotAge, removalBufferPpm);
    }

    public PositionMarginAdjustmentResponse adjustIsolatedPositionMargin(ProductLine productLine,
                                                                         long userId,
                                                                         String symbol,
                                                                         PositionSide positionSide,
                                                                         long amountUnits,
                                                                         String referenceId,
                                                                         String reason,
                                                                         Duration maxRiskSnapshotAge,
                                                                         long removalBufferPpm) {
        return adjustIsolatedPositionMarginScoped(productLine(productLine), userId, symbol, positionSide,
                amountUnits, referenceId, reason, maxRiskSnapshotAge, removalBufferPpm);
    }

    public PositionMarginAdjustmentResponse adjustIsolatedPositionMargin(long userId,
                                                                         String symbol,
                                                                         PositionSide positionSide,
                                                                         long amountUnits,
                                                                         String referenceId,
                                                                         String reason,
                                                                         Duration maxRiskSnapshotAge,
                                                                         long removalBufferPpm) {
        return adjustIsolatedPositionMarginScoped(null, userId, symbol, positionSide, amountUnits, referenceId,
                reason, maxRiskSnapshotAge, removalBufferPpm);
    }

    private PositionMarginAdjustmentResponse adjustIsolatedPositionMarginScoped(ProductLine productLine,
                                                                                long userId,
                                                                                String symbol,
                                                                                PositionSide positionSide,
                                                                                long amountUnits,
                                                                                String referenceId,
                                                                                String reason,
                                                                                Duration maxRiskSnapshotAge,
                                                                                long removalBufferPpm) {
        Optional<PositionMarginAdjustmentReference> existing =
                productLine == null
                        ? positionMarginAdjustmentReference(userId, symbol, referenceId)
                        : productPositionMarginAdjustmentReference(accountType(productLine), userId, symbol, referenceId);
        PositionSide normalizedPositionSide = PositionSide.defaultIfNull(positionSide);
        if (existing.isPresent()) {
            requirePositionMarginAdjustmentMatches(existing.get(), amountUnits, reason, symbol);
            return productLine == null
                    ? positionMarginAdjustmentResponse(userId, symbol, existing.get().asset(), normalizedPositionSide,
                            amountUnits, referenceId)
                    : positionMarginAdjustmentResponse(productLine, userId, symbol, existing.get().asset(),
                            normalizedPositionSide, amountUnits, referenceId);
        }

        PositionCollateralTarget target = productLine == null
                ? lockOpenIsolatedPosition(userId, symbol, normalizedPositionSide)
                : lockOpenIsolatedPosition(productLine, userId, symbol, normalizedPositionSide);
        AccountType productAccountType = productLine == null ? null : accountType(productLine);
        int ledgerRows = productLine == null
                ? insertPositionMarginAdjustmentLedger(userId, target.asset(), amountUnits, referenceId, reason, symbol)
                : insertProductPositionMarginAdjustmentLedger(productAccountType, userId, target.asset(),
                        amountUnits, referenceId, reason, symbol);
        if (ledgerRows == 0) {
            Optional<PositionMarginAdjustmentReference> duplicateReference = productLine == null
                    ? positionMarginAdjustmentReferenceByAsset(userId, target.asset(), referenceId)
                    : productPositionMarginAdjustmentReferenceByAsset(productAccountType, userId, target.asset(),
                            referenceId);
            PositionMarginAdjustmentReference duplicate = duplicateReference
                    .orElseThrow(() -> new IllegalStateException("duplicate position margin adjustment but ledger missing"));
            requirePositionMarginAdjustmentMatches(duplicate, amountUnits, reason, symbol);
            return productLine == null
                    ? positionMarginAdjustmentResponse(userId, symbol, target.asset(), normalizedPositionSide,
                            amountUnits, referenceId)
                    : positionMarginAdjustmentResponse(productLine, userId, symbol, target.asset(),
                            normalizedPositionSide, amountUnits, referenceId);
        }

        Instant now = Instant.now();
        ProductLine resolvedProductLine = productLine == null ? ProductLine.LINEAR_PERPETUAL : productLine;
        long currentMarginUnits = lockPositionMarginUnits(resolvedProductLine, userId, symbol, target.asset(),
                MarginMode.ISOLATED, normalizedPositionSide);
        if (amountUnits > 0) {
            if (productLine == null) {
                addIsolatedPositionMargin(userId, symbol, target.asset(), normalizedPositionSide, amountUnits, now);
            } else {
                addProductIsolatedPositionMargin(productAccountType, resolvedProductLine, userId, symbol,
                        target.asset(), normalizedPositionSide, amountUnits, now);
            }
        } else {
            long removeUnits = Math.absExact(amountUnits);
            validateIsolatedMarginRemoval(target, currentMarginUnits, removeUnits, maxRiskSnapshotAge,
                    removalBufferPpm);
            if (productLine == null) {
                removeIsolatedPositionMargin(userId, symbol, target.asset(), normalizedPositionSide, removeUnits, now);
            } else {
                removeProductIsolatedPositionMargin(productAccountType, resolvedProductLine, userId, symbol,
                        target.asset(), normalizedPositionSide, removeUnits, now);
            }
        }

        PositionMarginAdjustmentResponse response =
                productLine == null
                        ? positionMarginAdjustmentResponse(userId, symbol, target.asset(), normalizedPositionSide,
                                amountUnits, referenceId)
                        : positionMarginAdjustmentResponse(productLine, userId, symbol, target.asset(),
                                normalizedPositionSide, amountUnits, referenceId);
        int ledgerRowsAfter = productLine == null
                ? updatePositionMarginAdjustmentLedgerBalance(userId, target.asset(), referenceId,
                        response.equityUnits())
                : updateProductPositionMarginAdjustmentLedgerBalance(productAccountType, userId, target.asset(),
                        referenceId, response.equityUnits());
        requireSingleRow(ledgerRowsAfter, "position margin adjustment ledger update");
        return response;
    }

    public PositionState lockPosition(long userId, String symbol, MarginMode marginMode) {
        return lockPosition(userId, symbol, marginMode, PositionSide.NET);
    }

    public PositionState lockPosition(long userId, String symbol, MarginMode marginMode, PositionSide positionSide) {
        return lockPosition(ProductLine.LINEAR_PERPETUAL, userId, symbol, marginMode, positionSide);
    }

    public PositionState lockPosition(ProductLine productLine,
                                      long userId,
                                      String symbol,
                                      MarginMode marginMode,
                                      PositionSide positionSide) {
        ProductLine resolvedProductLine = productLine(productLine);
        MarginMode normalizedMarginMode = MarginMode.defaultIfNull(marginMode);
        PositionSide normalizedPositionSide = PositionSide.defaultIfNull(positionSide);
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO account_positions (
                    product_line, user_id, symbol, margin_mode, position_side, instrument_version, signed_quantity_steps,
                    entry_price_ticks, realized_pnl_units, updated_at
                ) VALUES (?, ?, ?, ?, ?, NULL, 0, 0, 0, ?)
                ON CONFLICT (product_line, user_id, symbol, margin_mode, position_side) DO NOTHING
                """, resolvedProductLine.name(), userId, symbol, normalizedMarginMode.name(),
                normalizedPositionSide.name(), Timestamp.from(now));
        return jdbcTemplate.queryForObject("""
                SELECT instrument_version, signed_quantity_steps, entry_price_ticks, realized_pnl_units
                  FROM account_positions
                 WHERE product_line = ?
                   AND user_id = ?
                   AND symbol = ?
                   AND margin_mode = ?
                   AND position_side = ?
                 FOR UPDATE
                """, (rs, rowNum) -> new PositionState(
                rs.getLong("signed_quantity_steps"),
                longOrZero(rs, "instrument_version"),
                rs.getLong("entry_price_ticks"),
                rs.getLong("realized_pnl_units")), resolvedProductLine.name(), userId, symbol, normalizedMarginMode.name(),
                normalizedPositionSide.name());
    }

    private PositionCollateralTarget lockOpenIsolatedPosition(long userId, String symbol, PositionSide positionSide) {
        return jdbcTemplate.query("""
                SELECT p.instrument_version,
                       p.signed_quantity_steps,
                       i.settle_asset AS asset
                  FROM account_positions p
                  JOIN instruments i
                    ON i.symbol = p.symbol
                   AND i.version = p.instrument_version
                 WHERE p.user_id = ?
                   AND p.symbol = ?
                   AND p.margin_mode = 'ISOLATED'
                   AND p.position_side = ?
                   AND p.signed_quantity_steps <> 0
                 FOR UPDATE OF p
                """, (rs, rowNum) -> new PositionCollateralTarget(
                userId,
                symbol,
                rs.getString("asset"),
                PositionSide.defaultIfNull(positionSide),
                rs.getLong("instrument_version"),
                rs.getLong("signed_quantity_steps")), userId, symbol,
                PositionSide.defaultIfNull(positionSide).name()).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("open isolated position not found"));
    }

    private PositionCollateralTarget lockOpenIsolatedPosition(ProductLine productLine,
                                                              long userId,
                                                              String symbol,
                                                              PositionSide positionSide) {
        ProductLine resolvedProductLine = productLine(productLine);
        return jdbcTemplate.query("""
                SELECT p.instrument_version,
                       p.signed_quantity_steps,
                       i.settle_asset AS asset
                 FROM account_positions p
                 JOIN instruments i
                    ON i.symbol = p.symbol
                   AND i.version = p.instrument_version
                 WHERE p.product_line = ?
                   AND p.user_id = ?
                   AND p.symbol = ?
                   AND p.margin_mode = 'ISOLATED'
                   AND p.position_side = ?
                   AND p.signed_quantity_steps <> 0
                 FOR UPDATE OF p
                """, (rs, rowNum) -> new PositionCollateralTarget(
                userId,
                symbol,
                rs.getString("asset"),
                PositionSide.defaultIfNull(positionSide),
                rs.getLong("instrument_version"),
                rs.getLong("signed_quantity_steps")), resolvedProductLine.name(), userId, symbol,
                PositionSide.defaultIfNull(positionSide).name())
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("open isolated position not found"));
    }

    private long lockPositionMarginUnits(long userId, String symbol, String asset, MarginMode marginMode) {
        return lockPositionMarginUnits(userId, symbol, asset, marginMode, PositionSide.NET);
    }

    private long lockPositionMarginUnits(long userId, String symbol, String asset, MarginMode marginMode,
                                         PositionSide positionSide) {
        return lockPositionMarginUnits(ProductLine.LINEAR_PERPETUAL, userId, symbol, asset, marginMode, positionSide);
    }

    private long lockPositionMarginUnits(ProductLine productLine, long userId, String symbol, String asset,
                                         MarginMode marginMode, PositionSide positionSide) {
        ProductLine resolvedProductLine = productLine(productLine);
        return jdbcTemplate.query("""
                SELECT margin_units
                  FROM account_position_margins
                 WHERE product_line = ?
                   AND user_id = ?
                   AND symbol = ?
                   AND asset = ?
                   AND margin_mode = ?
                   AND position_side = ?
                 FOR UPDATE
                """, (rs, rowNum) -> rs.getLong("margin_units"), resolvedProductLine.name(), userId, symbol, asset,
                MarginMode.defaultIfNull(marginMode).name(), PositionSide.defaultIfNull(positionSide).name())
                .stream().findFirst().orElse(0L);
    }

    private void addIsolatedPositionMargin(long userId,
                                           String symbol,
                                           String asset,
                                           PositionSide positionSide,
                                           long amountUnits,
                                           Instant now) {
        addIsolatedPositionMargin(ProductLine.LINEAR_PERPETUAL, userId, symbol, asset, positionSide, amountUnits, now);
    }

    private void addIsolatedPositionMargin(ProductLine productLine,
                                           long userId,
                                           String symbol,
                                           String asset,
                                           PositionSide positionSide,
                                           long amountUnits,
                                           Instant now) {
        ProductLine resolvedProductLine = productLine(productLine);
        int balanceRows = jdbcTemplate.update("""
                UPDATE account_balances
                   SET available_units = available_units - ?,
                       locked_units = locked_units + ?,
                       updated_at = ?
                 WHERE user_id = ?
                   AND asset = ?
                   AND available_units >= ?
                """, amountUnits, amountUnits, Timestamp.from(now), userId, asset, amountUnits);
        if (balanceRows != 1) {
            throw new IllegalArgumentException("insufficient available balance");
        }
        int marginRows = jdbcTemplate.update("""
                INSERT INTO account_position_margins (
                    product_line, user_id, symbol, asset, margin_mode, position_side, margin_units, updated_at
                ) VALUES (?, ?, ?, ?, 'ISOLATED', ?, ?, ?)
                ON CONFLICT (product_line, user_id, symbol, asset, margin_mode, position_side) DO UPDATE
                   SET margin_units = account_position_margins.margin_units + EXCLUDED.margin_units,
                       updated_at = EXCLUDED.updated_at
                """, resolvedProductLine.name(), userId, symbol, asset, PositionSide.defaultIfNull(positionSide).name(),
                amountUnits, Timestamp.from(now));
        requireSingleRow(marginRows, "isolated position margin add");
    }

    private void addProductIsolatedPositionMargin(AccountType accountType,
                                                  ProductLine productLine,
                                                  long userId,
                                                  String symbol,
                                                  String asset,
                                                  PositionSide positionSide,
                                                  long amountUnits,
                                                  Instant now) {
        ProductLine resolvedProductLine = productLine(productLine);
        int balanceRows = jdbcTemplate.update("""
                UPDATE account_product_balances
                   SET available_units = available_units - ?,
                       locked_units = locked_units + ?,
                       updated_at = ?
                 WHERE account_type = ?
                   AND user_id = ?
                   AND asset = ?
                   AND available_units >= ?
                """, amountUnits, amountUnits, Timestamp.from(now), accountType.name(), userId, asset, amountUnits);
        if (balanceRows != 1) {
            throw new IllegalArgumentException("insufficient available product balance");
        }
        int marginRows = jdbcTemplate.update("""
                INSERT INTO account_position_margins (
                    product_line, user_id, symbol, asset, margin_mode, position_side, margin_units, updated_at
                ) VALUES (?, ?, ?, ?, 'ISOLATED', ?, ?, ?)
                ON CONFLICT (product_line, user_id, symbol, asset, margin_mode, position_side) DO UPDATE
                   SET margin_units = account_position_margins.margin_units + EXCLUDED.margin_units,
                       updated_at = EXCLUDED.updated_at
                """, resolvedProductLine.name(), userId, symbol, asset, PositionSide.defaultIfNull(positionSide).name(),
                amountUnits, Timestamp.from(now));
        requireSingleRow(marginRows, "product isolated position margin add");
    }

    private void removeIsolatedPositionMargin(long userId,
                                              String symbol,
                                              String asset,
                                              PositionSide positionSide,
                                              long amountUnits,
                                              Instant now) {
        removeIsolatedPositionMargin(ProductLine.LINEAR_PERPETUAL, userId, symbol, asset, positionSide, amountUnits, now);
    }

    private void removeIsolatedPositionMargin(ProductLine productLine,
                                              long userId,
                                              String symbol,
                                              String asset,
                                              PositionSide positionSide,
                                              long amountUnits,
                                              Instant now) {
        ProductLine resolvedProductLine = productLine(productLine);
        int marginRows = jdbcTemplate.update("""
                UPDATE account_position_margins
                   SET margin_units = margin_units - ?,
                       updated_at = ?
                 WHERE product_line = ?
                   AND user_id = ?
                   AND symbol = ?
                   AND asset = ?
                   AND margin_mode = 'ISOLATED'
                   AND position_side = ?
                   AND margin_units >= ?
                """, amountUnits, Timestamp.from(now), resolvedProductLine.name(), userId, symbol, asset,
                PositionSide.defaultIfNull(positionSide).name(), amountUnits);
        requireSingleRow(marginRows, "isolated position margin remove");
        jdbcTemplate.update("""
                DELETE FROM account_position_margins
                 WHERE product_line = ? AND user_id = ? AND symbol = ? AND asset = ? AND margin_mode = 'ISOLATED'
                   AND position_side = ? AND margin_units = 0
                """, resolvedProductLine.name(), userId, symbol, asset, PositionSide.defaultIfNull(positionSide).name());
        int balanceRows = jdbcTemplate.update("""
                UPDATE account_balances
                   SET available_units = available_units + ?,
                       locked_units = locked_units - ?,
                       updated_at = ?
                 WHERE user_id = ?
                   AND asset = ?
                   AND locked_units >= ?
                """, amountUnits, amountUnits, Timestamp.from(now), userId, asset, amountUnits);
        if (balanceRows != 1) {
            throw new IllegalStateException("insufficient locked balance for isolated margin removal");
        }
    }

    private void removeProductIsolatedPositionMargin(AccountType accountType,
                                                     ProductLine productLine,
                                                     long userId,
                                                     String symbol,
                                                     String asset,
                                                     PositionSide positionSide,
                                                     long amountUnits,
                                                     Instant now) {
        ProductLine resolvedProductLine = productLine(productLine);
        int marginRows = jdbcTemplate.update("""
                UPDATE account_position_margins
                   SET margin_units = margin_units - ?,
                       updated_at = ?
                 WHERE product_line = ?
                   AND user_id = ?
                   AND symbol = ?
                   AND asset = ?
                   AND margin_mode = 'ISOLATED'
                   AND position_side = ?
                   AND margin_units >= ?
                """, amountUnits, Timestamp.from(now), resolvedProductLine.name(), userId, symbol, asset,
                PositionSide.defaultIfNull(positionSide).name(), amountUnits);
        requireSingleRow(marginRows, "product isolated position margin remove");
        jdbcTemplate.update("""
                DELETE FROM account_position_margins
                 WHERE product_line = ? AND user_id = ? AND symbol = ? AND asset = ? AND margin_mode = 'ISOLATED'
                   AND position_side = ? AND margin_units = 0
                """, resolvedProductLine.name(), userId, symbol, asset, PositionSide.defaultIfNull(positionSide).name());
        int balanceRows = jdbcTemplate.update("""
                UPDATE account_product_balances
                   SET available_units = available_units + ?,
                       locked_units = locked_units - ?,
                       updated_at = ?
                 WHERE account_type = ?
                   AND user_id = ?
                   AND asset = ?
                   AND locked_units >= ?
                """, amountUnits, amountUnits, Timestamp.from(now), accountType.name(), userId, asset, amountUnits);
        if (balanceRows != 1) {
            throw new IllegalStateException("insufficient locked product balance for isolated margin removal");
        }
    }

    private void validateIsolatedMarginRemoval(PositionCollateralTarget target,
                                               long currentMarginUnits,
                                               long removeUnits,
                                               Duration maxRiskSnapshotAge,
                                               long removalBufferPpm) {
        if (currentMarginUnits < removeUnits) {
            throw new IllegalArgumentException("insufficient isolated position margin");
        }
        RiskRemovalSnapshot snapshot = latestRiskRemovalSnapshot(target.userId(), target.symbol(),
                target.positionSide(),
                maxRiskSnapshotAge);
        if (snapshot.instrumentVersion() != target.instrumentVersion()
                || snapshot.signedQuantitySteps() != target.signedQuantitySteps()) {
            throw new IllegalStateException("risk snapshot is stale for isolated margin removal");
        }
        if ("LIQUIDATION".equals(snapshot.status())) {
            throw new IllegalStateException("position is already in liquidation risk");
        }
        long afterMarginUnits = Math.subtractExact(currentMarginUnits, removeUnits);
        long equityAfterUnits = Math.addExact(afterMarginUnits, snapshot.unrealizedPnlUnits());
        long requiredEquityUnits = requiredEquityWithBuffer(snapshot.maintenanceMarginUnits(), removalBufferPpm);
        if (equityAfterUnits < requiredEquityUnits) {
            throw new IllegalArgumentException("isolated margin removal would breach maintenance margin buffer");
        }
    }

    private RiskRemovalSnapshot latestRiskRemovalSnapshot(long userId, String symbol, PositionSide positionSide,
                                                          Duration maxRiskSnapshotAge) {
        return jdbcTemplate.query("""
                SELECT instrument_version,
                       signed_quantity_steps,
                       unrealized_pnl_units,
                       maintenance_margin_units,
                       status,
                       event_time
                  FROM risk_position_snapshots
                 WHERE user_id = ?
                   AND symbol = ?
                   AND margin_mode = 'ISOLATED'
                   AND position_side = ?
                   AND event_time >= now() - (? * INTERVAL '1 millisecond')
                 ORDER BY event_time DESC
                 LIMIT 1
                """, (rs, rowNum) -> new RiskRemovalSnapshot(
                rs.getLong("instrument_version"),
                rs.getLong("signed_quantity_steps"),
                rs.getLong("unrealized_pnl_units"),
                rs.getLong("maintenance_margin_units"),
                rs.getString("status"),
                rs.getTimestamp("event_time").toInstant()), userId, symbol,
                PositionSide.defaultIfNull(positionSide).name(), maxRiskSnapshotAge.toMillis())
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("fresh risk snapshot not found for isolated margin removal"));
    }

    private long requiredEquityWithBuffer(long maintenanceMarginUnits, long removalBufferPpm) {
        if (removalBufferPpm < 0) {
            throw new IllegalArgumentException("removalBufferPpm must be non-negative");
        }
        BigInteger required = BigInteger.valueOf(maintenanceMarginUnits)
                .multiply(BigInteger.valueOf(Math.addExact(PPM, removalBufferPpm)))
                .add(BigInteger.valueOf(PPM - 1L))
                .divide(BigInteger.valueOf(PPM));
        return required.longValueExact();
    }

    private PositionMarginAdjustmentResponse positionMarginAdjustmentResponse(long userId,
                                                                              String symbol,
                                                                              String asset,
                                                                              long amountUnits,
                                                                              String referenceId) {
        return positionMarginAdjustmentResponse(userId, symbol, asset, PositionSide.NET, amountUnits, referenceId);
    }

    private PositionMarginAdjustmentResponse positionMarginAdjustmentResponse(long userId,
                                                                              String symbol,
                                                                              String asset,
                                                                              PositionSide positionSide,
                                                                              long amountUnits,
                                                                              String referenceId) {
        PositionSide normalizedPositionSide = PositionSide.defaultIfNull(positionSide);
        long marginUnits = lockPositionMarginUnits(userId, symbol, asset, MarginMode.ISOLATED,
                normalizedPositionSide);
        BalanceResponse currentBalance = balance(userId, asset)
                .orElse(new BalanceResponse(userId, asset, 0L, 0L, 0L, Instant.EPOCH));
        return new PositionMarginAdjustmentResponse(userId, symbol, asset, MarginMode.ISOLATED,
                normalizedPositionSide, amountUnits,
                marginUnits, currentBalance.availableUnits(), currentBalance.lockedUnits(),
                currentBalance.equityUnits(), referenceId, currentBalance.updatedAt());
    }

    private PositionMarginAdjustmentResponse positionMarginAdjustmentResponse(ProductLine productLine,
                                                                              long userId,
                                                                              String symbol,
                                                                              String asset,
                                                                              PositionSide positionSide,
                                                                              long amountUnits,
                                                                              String referenceId) {
        ProductLine resolvedProductLine = productLine(productLine);
        AccountType accountType = accountType(resolvedProductLine);
        PositionSide normalizedPositionSide = PositionSide.defaultIfNull(positionSide);
        long marginUnits = lockPositionMarginUnits(resolvedProductLine, userId, symbol, asset, MarginMode.ISOLATED,
                normalizedPositionSide);
        ProductBalanceResponse currentBalance = productBalance(userId, accountType, asset)
                .orElse(new ProductBalanceResponse(userId, accountType, asset, 0L, 0L, 0L, Instant.EPOCH));
        return new PositionMarginAdjustmentResponse(userId, symbol, asset, MarginMode.ISOLATED,
                normalizedPositionSide, amountUnits,
                marginUnits, currentBalance.availableUnits(), currentBalance.lockedUnits(),
                currentBalance.equityUnits(), referenceId, currentBalance.updatedAt());
    }

    private int insertPositionMarginAdjustmentLedger(long userId,
                                                     String asset,
                                                     long amountUnits,
                                                     String referenceId,
                                                     String reason,
                                                     String symbol) {
        return jdbcTemplate.update("""
                INSERT INTO account_ledger_entries (
                    entry_id, user_id, asset, amount_units, balance_after_units,
                    reference_type, reference_id, reason, symbol, created_at
                ) VALUES (?, ?, ?, ?, 0, 'POSITION_MARGIN_ADJUSTMENT', ?, ?, ?, ?)
                ON CONFLICT (reference_type, reference_id, user_id, asset) DO NOTHING
                """, sequenceRepository.nextSequence("ledger-entry"), userId, asset, amountUnits,
                referenceId, reason, symbol, Timestamp.from(Instant.now()));
    }

    private int insertProductPositionMarginAdjustmentLedger(AccountType accountType,
                                                            long userId,
                                                            String asset,
                                                            long amountUnits,
                                                            String referenceId,
                                                            String reason,
                                                            String symbol) {
        return jdbcTemplate.update("""
                INSERT INTO account_product_ledger_entries (
                    entry_id, user_id, account_type, asset, amount_units, balance_after_units,
                    reference_type, reference_id, reason, symbol, created_at
                ) VALUES (?, ?, ?, ?, ?, 0, 'POSITION_MARGIN_ADJUSTMENT', ?, ?, ?, ?)
                ON CONFLICT (reference_type, reference_id, user_id, account_type, asset) DO NOTHING
                """, sequenceRepository.nextSequence("product-ledger-entry"), userId, accountType.name(), asset,
                amountUnits, referenceId, reason, symbol, Timestamp.from(Instant.now()));
    }

    private int updatePositionMarginAdjustmentLedgerBalance(long userId,
                                                            String asset,
                                                            String referenceId,
                                                            long balanceAfterUnits) {
        return jdbcTemplate.update("""
                UPDATE account_ledger_entries
                   SET balance_after_units = ?
                 WHERE reference_type = 'POSITION_MARGIN_ADJUSTMENT'
                   AND reference_id = ?
                   AND user_id = ?
                   AND asset = ?
                """, balanceAfterUnits, referenceId, userId, asset);
    }

    private int updateProductPositionMarginAdjustmentLedgerBalance(AccountType accountType,
                                                                   long userId,
                                                                   String asset,
                                                                   String referenceId,
                                                                   long balanceAfterUnits) {
        return jdbcTemplate.update("""
                UPDATE account_product_ledger_entries
                   SET balance_after_units = ?
                 WHERE reference_type = 'POSITION_MARGIN_ADJUSTMENT'
                   AND reference_id = ?
                   AND user_id = ?
                   AND account_type = ?
                   AND asset = ?
                """, balanceAfterUnits, referenceId, userId, accountType.name(), asset);
    }

    private Optional<PositionMarginAdjustmentReference> positionMarginAdjustmentReference(long userId,
                                                                                         String symbol,
                                                                                         String referenceId) {
        return jdbcTemplate.query("""
                SELECT asset, amount_units, reason, symbol
                  FROM account_ledger_entries
                 WHERE reference_type = 'POSITION_MARGIN_ADJUSTMENT'
                   AND reference_id = ?
                   AND user_id = ?
                   AND symbol = ?
                 FOR UPDATE
                """, (rs, rowNum) -> new PositionMarginAdjustmentReference(
                rs.getString("asset"),
                rs.getLong("amount_units"),
                rs.getString("reason"),
                rs.getString("symbol")), referenceId, userId, symbol).stream().findFirst();
    }

    private Optional<PositionMarginAdjustmentReference> positionMarginAdjustmentReferenceByAsset(long userId,
                                                                                                String asset,
                                                                                                String referenceId) {
        return jdbcTemplate.query("""
                SELECT asset, amount_units, reason, symbol
                  FROM account_ledger_entries
                 WHERE reference_type = 'POSITION_MARGIN_ADJUSTMENT'
                   AND reference_id = ?
                   AND user_id = ?
                   AND asset = ?
                 FOR UPDATE
                """, (rs, rowNum) -> new PositionMarginAdjustmentReference(
                rs.getString("asset"),
                rs.getLong("amount_units"),
                rs.getString("reason"),
                rs.getString("symbol")), referenceId, userId, asset).stream().findFirst();
    }

    private Optional<PositionMarginAdjustmentReference> productPositionMarginAdjustmentReference(AccountType accountType,
                                                                                                 long userId,
                                                                                                 String symbol,
                                                                                                 String referenceId) {
        return jdbcTemplate.query("""
                SELECT asset, amount_units, reason, symbol
                  FROM account_product_ledger_entries
                 WHERE reference_type = 'POSITION_MARGIN_ADJUSTMENT'
                   AND reference_id = ?
                   AND user_id = ?
                   AND account_type = ?
                   AND symbol = ?
                 FOR UPDATE
                """, (rs, rowNum) -> new PositionMarginAdjustmentReference(
                rs.getString("asset"),
                rs.getLong("amount_units"),
                rs.getString("reason"),
                rs.getString("symbol")), referenceId, userId, accountType.name(), symbol).stream().findFirst();
    }

    private Optional<PositionMarginAdjustmentReference> productPositionMarginAdjustmentReferenceByAsset(AccountType accountType,
                                                                                                        long userId,
                                                                                                        String asset,
                                                                                                        String referenceId) {
        return jdbcTemplate.query("""
                SELECT asset, amount_units, reason, symbol
                  FROM account_product_ledger_entries
                 WHERE reference_type = 'POSITION_MARGIN_ADJUSTMENT'
                   AND reference_id = ?
                   AND user_id = ?
                   AND account_type = ?
                   AND asset = ?
                 FOR UPDATE
                """, (rs, rowNum) -> new PositionMarginAdjustmentReference(
                rs.getString("asset"),
                rs.getLong("amount_units"),
                rs.getString("reason"),
                rs.getString("symbol")), referenceId, userId, accountType.name(), asset).stream().findFirst();
    }

    private void requirePositionMarginAdjustmentMatches(PositionMarginAdjustmentReference existing,
                                                        long amountUnits,
                                                        String reason,
                                                        String symbol) {
        if (existing.amountUnits() != amountUnits
                || !Objects.equals(existing.reason(), reason)
                || !Objects.equals(existing.symbol(), symbol)) {
            throw new IllegalStateException("conflicting duplicate position margin adjustment reference");
        }
    }

    public PositionResponse updatePosition(long userId,
                                           String symbol,
                                           MarginMode marginMode,
                                           PositionState state,
                                           Instant now) {
        return updatePosition(userId, symbol, marginMode, PositionSide.NET, state, now);
    }

    public PositionResponse updatePosition(long userId,
                                           String symbol,
                                           MarginMode marginMode,
                                           PositionSide positionSide,
                                           PositionState state,
                                           Instant now) {
        MarginMode normalizedMarginMode = MarginMode.defaultIfNull(marginMode);
        PositionSide normalizedPositionSide = PositionSide.defaultIfNull(positionSide);
        long previousSignedQuantitySteps = lockCurrentPositionQuantity(userId, symbol, normalizedMarginMode,
                normalizedPositionSide);
        return updatePosition(userId, symbol, normalizedMarginMode, normalizedPositionSide, state,
                previousSignedQuantitySteps, now);
    }

    public PositionResponse updatePosition(long userId,
                                           String symbol,
                                           MarginMode marginMode,
                                           PositionState state,
                                           long previousSignedQuantitySteps,
                                           Instant now) {
        return updatePosition(userId, symbol, marginMode, PositionSide.NET, state, previousSignedQuantitySteps, now);
    }

    public PositionResponse updatePosition(long userId,
                                           String symbol,
                                           MarginMode marginMode,
                                           PositionSide positionSide,
                                           PositionState state,
                                           long previousSignedQuantitySteps,
                                           Instant now) {
        return updatePosition(ProductLine.LINEAR_PERPETUAL, userId, symbol, marginMode, positionSide, state,
                previousSignedQuantitySteps, now);
    }

    public PositionResponse updatePosition(ProductLine productLine,
                                           long userId,
                                           String symbol,
                                           MarginMode marginMode,
                                           PositionSide positionSide,
                                           PositionState state,
                                           long previousSignedQuantitySteps,
                                           Instant now) {
        ProductLine resolvedProductLine = productLine(productLine);
        MarginMode normalizedMarginMode = MarginMode.defaultIfNull(marginMode);
        PositionSide normalizedPositionSide = PositionSide.defaultIfNull(positionSide);
        int rows = jdbcTemplate.update("""
                UPDATE account_positions
                   SET signed_quantity_steps = ?,
                       instrument_version = ?,
                       entry_price_ticks = ?,
                       realized_pnl_units = ?,
                       updated_at = ?
                 WHERE product_line = ?
                   AND user_id = ?
                   AND symbol = ?
                   AND margin_mode = ?
                   AND position_side = ?
                """, state.signedQuantitySteps(), nullableVersion(state.instrumentVersion()),
                state.entryPriceTicks(), state.realizedPnlUnits(),
                Timestamp.from(now), resolvedProductLine.name(), userId, symbol, normalizedMarginMode.name(),
                normalizedPositionSide.name());
        requireSingleRow(rows, "account position update");
        updateSymbolOpenInterest(resolvedProductLine, symbol, previousSignedQuantitySteps, state.signedQuantitySteps(), now);
        return new PositionResponse(userId, symbol, state.instrumentVersion(), normalizedMarginMode,
                normalizedPositionSide,
                state.signedQuantitySteps(), state.entryPriceTicks(), state.realizedPnlUnits(), now);
    }

    private long lockCurrentPositionQuantity(long userId, String symbol, MarginMode marginMode) {
        return lockCurrentPositionQuantity(userId, symbol, marginMode, PositionSide.NET);
    }

    private long lockCurrentPositionQuantity(long userId, String symbol, MarginMode marginMode,
                                             PositionSide positionSide) {
        return lockCurrentPositionQuantity(ProductLine.LINEAR_PERPETUAL, userId, symbol, marginMode, positionSide);
    }

    private long lockCurrentPositionQuantity(ProductLine productLine, long userId, String symbol, MarginMode marginMode,
                                             PositionSide positionSide) {
        ProductLine resolvedProductLine = productLine(productLine);
        return jdbcTemplate.query("""
                SELECT signed_quantity_steps
                  FROM account_positions
                 WHERE product_line = ?
                   AND user_id = ?
                   AND symbol = ?
                   AND margin_mode = ?
                   AND position_side = ?
                 FOR UPDATE
                """, (rs, rowNum) -> rs.getLong("signed_quantity_steps"), resolvedProductLine.name(), userId, symbol,
                MarginMode.defaultIfNull(marginMode).name(), PositionSide.defaultIfNull(positionSide).name()).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("position not found before update"));
    }

    private void updateSymbolOpenInterest(ProductLine productLine,
                                          String symbol,
                                          long previousSignedQuantitySteps,
                                          long nextSignedQuantitySteps,
                                          Instant now) {
        ProductLine resolvedProductLine = productLine(productLine);
        long longDelta = Math.subtractExact(longQuantitySteps(nextSignedQuantitySteps),
                longQuantitySteps(previousSignedQuantitySteps));
        long shortDelta = Math.subtractExact(shortQuantitySteps(nextSignedQuantitySteps),
                shortQuantitySteps(previousSignedQuantitySteps));
        if (longDelta == 0L && shortDelta == 0L) {
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO trading_symbol_open_interest (
                    product_line, symbol, long_quantity_steps, short_quantity_steps, open_quantity_steps, updated_at
                ) VALUES (?, ?, 0, 0, 0, ?)
                ON CONFLICT (product_line, symbol) DO NOTHING
                """, resolvedProductLine.name(), symbol, Timestamp.from(now));
        int rows = jdbcTemplate.update("""
                UPDATE trading_symbol_open_interest
                   SET long_quantity_steps = long_quantity_steps + ?,
                       short_quantity_steps = short_quantity_steps + ?,
                       open_quantity_steps = GREATEST(long_quantity_steps + ?, short_quantity_steps + ?),
                       updated_at = ?
                 WHERE symbol = ?
                   AND product_line = ?
                   AND long_quantity_steps + ? >= 0
                   AND short_quantity_steps + ? >= 0
                """, longDelta, shortDelta, longDelta, shortDelta, Timestamp.from(now), symbol, resolvedProductLine.name(),
                longDelta, shortDelta);
        requireSingleRow(rows, "symbol open interest update");
    }

    private long longQuantitySteps(long signedQuantitySteps) {
        return signedQuantitySteps > 0 ? signedQuantitySteps : 0L;
    }

    private long shortQuantitySteps(long signedQuantitySteps) {
        return signedQuantitySteps < 0 ? Math.negateExact(signedQuantitySteps) : 0L;
    }

    public ContractSpec contractSpec(String symbol, long instrumentVersion) {
        return jdbcTemplate.query("""
                SELECT i.version,
                       i.contract_type,
                       i.settle_asset,
                       i.notional_multiplier_units,
                       i.price_tick_units,
                       i.initial_margin_rate_ppm,
                       i.maker_fee_rate_ppm,
                       i.taker_fee_rate_ppm,
                       ss.scale_units AS settle_scale_units
                  FROM instruments i
                  JOIN account_asset_scales ss
                    ON ss.asset = i.settle_asset
                 WHERE i.symbol = ?
                   AND i.version = ?
                """, (rs, rowNum) -> new ContractSpec(
                rs.getLong("version"),
                ContractType.valueOf(rs.getString("contract_type")),
                rs.getString("settle_asset"),
                rs.getLong("notional_multiplier_units"),
                rs.getLong("price_tick_units"),
                rs.getLong("settle_scale_units"),
                rs.getLong("initial_margin_rate_ppm"),
                rs.getLong("maker_fee_rate_ppm"),
                rs.getLong("taker_fee_rate_ppm")), symbol, instrumentVersion).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("instrument contract spec not found for "
                        + symbol + " version " + instrumentVersion));
    }

    public InstrumentType instrumentType(String symbol, long instrumentVersion) {
        return jdbcTemplate.query("""
                SELECT instrument_type
                  FROM instruments
                 WHERE symbol = ?
                   AND version = ?
                """, (rs, rowNum) -> InstrumentType.valueOf(rs.getString("instrument_type")),
                symbol, instrumentVersion).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("instrument type not found for "
                        + symbol + " version " + instrumentVersion));
    }

    public long latestMarkPriceTicks(String symbol, long instrumentVersion) {
        return jdbcTemplate.query("""
                SELECT ((m.mark_price_units + i.price_tick_units / 2) / i.price_tick_units) AS mark_price_ticks
                  FROM instruments i
                  JOIN LATERAL (
                      SELECT mark_price_units
                        FROM price_mark_ticks
                       WHERE symbol = i.symbol
                       ORDER BY event_time DESC
                       LIMIT 1
                  ) m ON TRUE
                 WHERE i.symbol = ?
                   AND i.version = ?
                """, (rs, rowNum) -> rs.getLong("mark_price_ticks"), symbol, instrumentVersion)
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("latest mark price not found for " + symbol));
    }

    public long latestMarkPriceUnits(String symbol) {
        return jdbcTemplate.query("""
                SELECT mark_price_units
                  FROM price_mark_ticks
                 WHERE symbol = ?
                 ORDER BY event_time DESC
                 LIMIT 1
                """, (rs, rowNum) -> rs.getLong("mark_price_units"), symbol)
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("latest mark price not found for " + symbol));
    }

    public SpotInstrumentSpec spotInstrumentSpec(String symbol, long instrumentVersion) {
        return jdbcTemplate.query("""
                SELECT version, base_asset, quote_asset, quantity_step_units, notional_multiplier_units
                  FROM instruments
                 WHERE symbol = ?
                   AND version = ?
                   AND instrument_type = 'SPOT'
                   AND contract_type = 'SPOT'
                """, (rs, rowNum) -> new SpotInstrumentSpec(
                rs.getLong("version"),
                rs.getString("base_asset"),
                rs.getString("quote_asset"),
                rs.getLong("quantity_step_units"),
                rs.getLong("notional_multiplier_units")), symbol, instrumentVersion).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("spot instrument spec not found for "
                        + symbol + " version " + instrumentVersion));
    }

    public OrderFeeSnapshot orderFeeSnapshot(long orderId, long userId, String symbol) {
        return jdbcTemplate.query("""
                SELECT maker_fee_rate_ppm,
                       taker_fee_rate_ppm
                  FROM trading_orders
                 WHERE order_id = ?
                   AND user_id = ?
                   AND symbol = ?
                """, (rs, rowNum) -> new OrderFeeSnapshot(
                rs.getLong("maker_fee_rate_ppm"),
                rs.getLong("taker_fee_rate_ppm")), orderId, userId, symbol).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("order fee snapshot not found for order " + orderId));
    }

    public Optional<LiquidationFeeContext> liquidationFeeContext(long orderId, long userId, String symbol) {
        return jdbcTemplate.query("""
                SELECT liquidation_order_id,
                       candidate_id,
                       liquidation_fee_rate_ppm
                  FROM liquidation_orders
                 WHERE order_id = ?
                   AND user_id = ?
                   AND symbol = ?
                   AND status IN ('SUBMITTED', 'PARTIALLY_FILLED', 'FILLED')
                """, (rs, rowNum) -> new LiquidationFeeContext(
                rs.getLong("liquidation_order_id"),
                rs.getLong("candidate_id"),
                rs.getLong("liquidation_fee_rate_ppm")), orderId, userId, symbol).stream().findFirst();
    }

    public boolean markTradeProcessing(long tradeId, String symbol) {
        return markTradeProcessing(ProductLine.LINEAR_PERPETUAL, tradeId, symbol);
    }

    public boolean markTradeProcessing(ProductLine productLine, long tradeId, String symbol) {
        ProductLine resolvedProductLine = productLine(productLine);
        int rows = jdbcTemplate.update("""
                INSERT INTO account_processed_trades (product_line, trade_id, symbol, processed_at)
                VALUES (?, ?, ?, now())
                ON CONFLICT (product_line, symbol, trade_id) DO NOTHING
                """, resolvedProductLine.name(), tradeId, symbol);
        return rows == 1;
    }

    public void settleRealizedPnl(long userId,
                                  String asset,
                                  long orderId,
                                  long tradeId,
                                  String symbol,
                                  MarginMode marginMode,
                                  long realizedPnlDeltaUnits,
                                  Instant now) {
        settleRealizedPnl(AccountType.USDT_PERPETUAL, userId, asset, orderId, tradeId, symbol, marginMode,
                realizedPnlDeltaUnits, now);
    }

    public void settleRealizedPnl(AccountType accountType,
                                  long userId,
                                  String asset,
                                  long orderId,
                                  long tradeId,
                                  String symbol,
                                  MarginMode marginMode,
                                  long realizedPnlDeltaUnits,
                                  Instant now) {
        if (realizedPnlDeltaUnits == 0) {
            return;
        }
        AccountType normalizedType = requireAccountType(accountType);
        String referenceId = tradeId + ":" + orderId;
        if (isLegacyPerpetualAccount(normalizedType)) {
            int ledgerRows = jdbcTemplate.update("""
                    INSERT INTO account_ledger_entries (
                        entry_id, user_id, asset, amount_units, balance_after_units,
                        reference_type, reference_id, reason, created_at
                    ) VALUES (?, ?, ?, ?, 0, 'TRADE_PNL', ?, 'REALIZED_PNL', ?)
                    ON CONFLICT (reference_type, reference_id, user_id, asset) DO NOTHING
                    """, sequenceRepository.nextSequence("ledger-entry"), userId, asset, realizedPnlDeltaUnits,
                    referenceId, Timestamp.from(now));
            requireSingleRow(ledgerRows, "trade pnl ledger insert");
            long balanceAfterUnits = applyAmountToBalance(normalizedType, userId, asset, symbol, marginMode,
                    realizedPnlDeltaUnits, now);
            int ledgerRowsAfter = jdbcTemplate.update("""
                    UPDATE account_ledger_entries
                       SET balance_after_units = ?
                     WHERE reference_type = 'TRADE_PNL'
                       AND reference_id = ?
                       AND user_id = ?
                       AND asset = ?
                    """, balanceAfterUnits, referenceId, userId, asset);
            requireSingleRow(ledgerRowsAfter, "trade pnl ledger update");
            return;
        }
        insertProductSettlementLedger(userId, normalizedType, asset, realizedPnlDeltaUnits, 0L,
                "TRADE_PNL", referenceId, "REALIZED_PNL", now);
        long balanceAfterUnits = applyAmountToBalance(normalizedType, userId, asset, symbol, marginMode,
                realizedPnlDeltaUnits, now);
        updateProductSettlementLedgerBalance(userId, normalizedType, asset, "TRADE_PNL", referenceId,
                balanceAfterUnits);
    }

    public boolean settleLifecyclePnl(AccountType accountType,
                                      long userId,
                                      String asset,
                                      String referenceType,
                                      String referenceId,
                                      String reason,
                                      String symbol,
                                      MarginMode marginMode,
                                      long realizedPnlDeltaUnits,
                                      Instant now) {
        if (realizedPnlDeltaUnits == 0) {
            return true;
        }
        AccountType normalizedType = requireAccountType(accountType);
        if (isLegacyPerpetualAccount(normalizedType)) {
            int ledgerRows = jdbcTemplate.update("""
                    INSERT INTO account_ledger_entries (
                        entry_id, user_id, asset, amount_units, balance_after_units,
                        reference_type, reference_id, reason, symbol, created_at
                    ) VALUES (?, ?, ?, ?, 0, ?, ?, ?, ?, ?)
                    ON CONFLICT (reference_type, reference_id, user_id, asset) DO NOTHING
                    """, sequenceRepository.nextSequence("ledger-entry"), userId, asset, realizedPnlDeltaUnits,
                    referenceType, referenceId, reason, symbol, Timestamp.from(now));
            if (ledgerRows == 0) {
                return false;
            }
            long balanceAfterUnits = applyAmountToBalance(normalizedType, userId, asset, symbol, marginMode,
                    realizedPnlDeltaUnits, now);
            int ledgerRowsAfter = jdbcTemplate.update("""
                    UPDATE account_ledger_entries
                       SET balance_after_units = ?
                     WHERE reference_type = ?
                       AND reference_id = ?
                       AND user_id = ?
                       AND asset = ?
                    """, balanceAfterUnits, referenceType, referenceId, userId, asset);
            requireSingleRow(ledgerRowsAfter, "lifecycle pnl ledger update");
            return true;
        }
        if (!tryInsertProductSettlementLedger(userId, normalizedType, asset, realizedPnlDeltaUnits, 0L,
                referenceType, referenceId, reason, now)) {
            return false;
        }
        long balanceAfterUnits = applyAmountToBalance(normalizedType, userId, asset, symbol, marginMode,
                realizedPnlDeltaUnits, now);
        updateProductSettlementLedgerBalance(userId, normalizedType, asset, referenceType, referenceId,
                balanceAfterUnits);
        return true;
    }

    public void settleOptionPremium(AccountType accountType,
                                    OrderSide side,
                                    long userId,
                                    String asset,
                                    long orderId,
                                    long tradeId,
                                    String symbol,
                                    MarginMode marginMode,
                                    long premiumUnits,
                                    boolean orderCompleted,
                                    Instant now) {
        if (premiumUnits <= 0) {
            return;
        }
        AccountType normalizedType = requireAccountType(accountType);
        if (normalizedType != AccountType.OPTION) {
            throw new IllegalArgumentException("option premium requires OPTION account");
        }
        String referenceId = tradeId + ":" + orderId + ":" + side.name();
        if (side == OrderSide.BUY) {
            long balanceAfterUnits = debitOptionPremium(normalizedType, userId, asset, orderId, symbol, marginMode,
                    premiumUnits, orderCompleted, now);
            insertProductSettlementLedger(userId, normalizedType, asset, Math.negateExact(premiumUnits),
                    balanceAfterUnits, "OPTION_PREMIUM", referenceId, "OPTION_PREMIUM_PAID", now);
            return;
        }
        long balanceAfterUnits = applyAmountToBalance(normalizedType, userId, asset, symbol, marginMode,
                premiumUnits, now);
        insertProductSettlementLedger(userId, normalizedType, asset, premiumUnits, balanceAfterUnits,
                "OPTION_PREMIUM", referenceId, "OPTION_PREMIUM_RECEIVED", now);
    }

    public void settleRealizedPnl(long userId,
                                  String asset,
                                  long orderId,
                                  long tradeId,
                                  long realizedPnlDeltaUnits,
                                  Instant now) {
        settleRealizedPnl(userId, asset, orderId, tradeId, "", MarginMode.CROSS, realizedPnlDeltaUnits, now);
    }

    public void settleTradeFee(long userId,
                               String asset,
                               long orderId,
                               long tradeId,
                               long feeDeltaUnits,
                               String reason,
                               long feeRatePpm,
                               String symbol,
                               MarginMode marginMode,
                               Instant now) {
        settleTradeFee(AccountType.USDT_PERPETUAL, userId, asset, orderId, tradeId, feeDeltaUnits, reason,
                feeRatePpm, symbol, marginMode, now);
    }

    public void settleTradeFee(AccountType accountType,
                               long userId,
                               String asset,
                               long orderId,
                               long tradeId,
                               long feeDeltaUnits,
                               String reason,
                               long feeRatePpm,
                               String symbol,
                               MarginMode marginMode,
                               Instant now) {
        if (feeDeltaUnits == 0) {
            return;
        }
        AccountType normalizedType = requireAccountType(accountType);
        String referenceId = tradeId + ":" + orderId;
        if (isLegacyPerpetualAccount(normalizedType)) {
            int ledgerRows = jdbcTemplate.update("""
                    INSERT INTO account_ledger_entries (
                        entry_id, user_id, asset, amount_units, balance_after_units,
                        reference_type, reference_id, reason, trade_id, order_id, symbol, fee_rate_ppm, created_at
                    ) VALUES (?, ?, ?, ?, 0, 'TRADE_FEE', ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (reference_type, reference_id, user_id, asset) DO NOTHING
                    """, sequenceRepository.nextSequence("ledger-entry"), userId, asset, feeDeltaUnits, referenceId,
                    reason, tradeId, orderId, symbol, feeRatePpm, Timestamp.from(now));
            requireSingleRow(ledgerRows, "trade fee ledger insert");
            long balanceAfterUnits = applyAmountToBalance(normalizedType, userId, asset, symbol, marginMode,
                    feeDeltaUnits, now);
            int ledgerRowsAfter = jdbcTemplate.update("""
                    UPDATE account_ledger_entries
                       SET balance_after_units = ?
                     WHERE reference_type = 'TRADE_FEE'
                       AND reference_id = ?
                       AND user_id = ?
                       AND asset = ?
                    """, balanceAfterUnits, referenceId, userId, asset);
            requireSingleRow(ledgerRowsAfter, "trade fee ledger update");
            return;
        }
        insertProductSettlementLedger(userId, normalizedType, asset, feeDeltaUnits, 0L,
                "TRADE_FEE", referenceId, reason, now);
        long balanceAfterUnits = applyAmountToBalance(normalizedType, userId, asset, symbol, marginMode,
                feeDeltaUnits, now);
        updateProductSettlementLedgerBalance(userId, normalizedType, asset, "TRADE_FEE", referenceId,
                balanceAfterUnits);
    }

    public void settleSpotTradeSide(long userId,
                                    long orderId,
                                    long tradeId,
                                    String symbol,
                                    OrderSide side,
                                    long priceTicks,
                                    long quantitySteps,
                                    SpotInstrumentSpec spec,
                                    long feeRatePpm,
                                    String feeReason,
                                    boolean orderCompleted,
                                    Instant now) {
        if (priceTicks <= 0 || quantitySteps <= 0) {
            throw new IllegalArgumentException("priceTicks and quantitySteps must be positive");
        }
        SpotReservation reservation = lockSpotReservation(orderId, userId, symbol);
        if (reservation.side() != side) {
            throw new IllegalStateException("spot reservation side mismatch for order " + orderId);
        }
        long baseUnits = multiplyToLong(quantitySteps, spec.quantityStepUnits());
        long quoteUnits = multiplyToLong(priceTicks, quantitySteps, spec.notionalMultiplierUnits());
        long feeUnits = spotFeeUnits(quoteUnits, feeRatePpm);
        long positiveFeeUnits = feeRatePpm > 0 ? feeUnits : 0L;
        long settledUnits = side == OrderSide.BUY
                ? Math.addExact(quoteUnits, positiveFeeUnits)
                : baseUnits;
        long remainingReservationUnits = Math.subtractExact(reservation.reservedUnits(),
                Math.addExact(reservation.settledUnits(), reservation.releasedUnits()));
        if (settledUnits > remainingReservationUnits) {
            throw new IllegalStateException("spot reservation is smaller than filled amount for order " + orderId);
        }
        long releaseUnits = orderCompleted ? Math.subtractExact(remainingReservationUnits, settledUnits) : 0L;
        if (side == OrderSide.BUY) {
            debitSpotLocked(userId, spec.quoteAsset(), quoteUnits, now, tradeId, orderId, "SPOT_BUY_COST");
            if (positiveFeeUnits > 0) {
                debitSpotLocked(userId, spec.quoteAsset(), positiveFeeUnits, now, tradeId, orderId, feeReason);
            } else if (feeUnits > 0) {
                creditSpotAvailable(userId, spec.quoteAsset(), feeUnits, now, tradeId, orderId, feeReason);
            }
            creditSpotAvailable(userId, spec.baseAsset(), baseUnits, now, tradeId, orderId, "SPOT_BUY_FILL");
            releaseSpotLocked(userId, spec.quoteAsset(), releaseUnits, now);
        } else {
            debitSpotLocked(userId, spec.baseAsset(), baseUnits, now, tradeId, orderId, "SPOT_SELL_BASE");
            creditSpotAvailable(userId, spec.quoteAsset(), quoteUnits, now, tradeId, orderId, "SPOT_SELL_PROCEEDS");
            if (positiveFeeUnits > 0) {
                debitSpotAvailable(userId, spec.quoteAsset(), positiveFeeUnits, now, tradeId, orderId, feeReason);
            } else if (feeUnits > 0) {
                creditSpotAvailable(userId, spec.quoteAsset(), feeUnits, now, tradeId, orderId, feeReason);
            }
            releaseSpotLocked(userId, spec.baseAsset(), releaseUnits, now);
        }
        updateSpotReservation(orderId, settledUnits, releaseUnits, feeReason, now);
    }

    public void settleTradeFee(long userId,
                               String asset,
                               long orderId,
                               long tradeId,
                               long feeDeltaUnits,
                               String reason,
                               long feeRatePpm,
                               String symbol,
                               Instant now) {
        settleTradeFee(userId, asset, orderId, tradeId, feeDeltaUnits, reason, feeRatePpm, symbol, MarginMode.CROSS,
                now);
    }

    @Transactional
    public Optional<LiquidationFeeSettlement> settleLiquidationFee(long userId,
                                                                   String asset,
                                                                   long orderId,
                                                                   long tradeId,
                                                                   String symbol,
                                                                   MarginMode marginMode,
                                                                   long requestedFeeUnits,
                                                                   LiquidationFeeContext context,
                                                                   Instant now) {
        return settleLiquidationFee(AccountType.USDT_PERPETUAL, userId, asset, orderId, tradeId, symbol,
                marginMode, requestedFeeUnits, context, now);
    }

    @Transactional
    public Optional<LiquidationFeeSettlement> settleLiquidationFee(AccountType accountType,
                                                                   long userId,
                                                                   String asset,
                                                                   long orderId,
                                                                   long tradeId,
                                                                   String symbol,
                                                                   MarginMode marginMode,
                                                                   long requestedFeeUnits,
                                                                   LiquidationFeeContext context,
                                                                   Instant now) {
        if (requestedFeeUnits <= 0 || context == null || context.feeRatePpm() <= 0) {
            return Optional.empty();
        }
        AccountType normalizedType = requireAccountType(accountType);
        String referenceId = tradeId + ":" + orderId;
        if (liquidationFeeReferenceExists(normalizedType, userId, asset, referenceId)) {
            return Optional.empty();
        }
        BalanceDebitResult debit = applyCappedDebitToBalance(normalizedType, userId, asset, symbol, marginMode,
                requestedFeeUnits, now);
        if (debit.debitedUnits() <= 0) {
            return Optional.empty();
        }
        if (isLegacyPerpetualAccount(normalizedType)) {
            int ledgerRows = jdbcTemplate.update("""
                    INSERT INTO account_ledger_entries (
                        entry_id, user_id, asset, amount_units, balance_after_units,
                        reference_type, reference_id, reason, trade_id, order_id, symbol, fee_rate_ppm, created_at
                    ) VALUES (?, ?, ?, ?, ?, 'LIQUIDATION_FEE', ?, 'COLLECT_LIQUIDATION_FEE', ?, ?, ?, ?, ?)
                    ON CONFLICT (reference_type, reference_id, user_id, asset) DO NOTHING
                    """, sequenceRepository.nextSequence("ledger-entry"), userId, asset,
                    Math.negateExact(debit.debitedUnits()), debit.balanceAfterUnits(), referenceId, tradeId,
                    orderId, symbol, context.feeRatePpm(), Timestamp.from(now));
            requireSingleRow(ledgerRows, "liquidation fee ledger insert");
        } else {
            insertProductSettlementLedger(userId, normalizedType, asset, Math.negateExact(debit.debitedUnits()),
                    debit.balanceAfterUnits(), "LIQUIDATION_FEE", referenceId, "COLLECT_LIQUIDATION_FEE", now);
        }
        return Optional.of(new LiquidationFeeSettlement(context.liquidationOrderId(), context.candidateId(),
                debit.debitedUnits(), context.feeRatePpm()));
    }

    public void releaseOrderMargin(long orderId,
                                   long userId,
                                   String symbol,
                                   long closeSteps,
                                   boolean sweepRemainder,
                                   Instant now) {
        OrderMarginReservation reservation = lockOrderMarginReservation(orderId, userId, symbol);
        if (reservation == null) {
            requireReservationUnlessReduceOnly(orderId, userId, symbol);
            return;
        }
        long amountUnits = MarginTransferMath.orderMarginReleaseAmount(reservation.reservedUnits(),
                reservation.releasedUnits(), reservation.positionMarginUnits(), reservation.orderQuantitySteps(),
                closeSteps, sweepRemainder);
        releaseReservedMargin(orderId, reservation.accountType(), reservation.userId(), reservation.asset(), amountUnits,
                "POSITION_REDUCED", now);
    }

    public void consumeOrderMargin(long orderId,
                                   long userId,
                                   String symbol,
                                   MarginMode marginMode,
                                   long openSteps,
                                   long actualMarginUnits,
                                   boolean sweepRemainder,
                                   Instant now) {
        consumeOrderMargin(ProductLine.LINEAR_PERPETUAL, orderId, userId, symbol, marginMode, openSteps,
                actualMarginUnits, sweepRemainder, now);
    }

    public void consumeOrderMargin(ProductLine productLine,
                                   long orderId,
                                   long userId,
                                   String symbol,
                                   MarginMode marginMode,
                                   long openSteps,
                                   long actualMarginUnits,
                                   boolean sweepRemainder,
                                   Instant now) {
        if (openSteps <= 0) {
            return;
        }
        ProductLine resolvedProductLine = productLine(productLine);
        OrderMarginReservation reservation = lockOrderMarginReservation(orderId, userId, symbol);
        if (reservation == null) {
            throw new IllegalStateException("missing order margin reservation for opening fill " + orderId);
        }
        if (reservation.reduceOnly()) {
            throw new IllegalStateException("reduce-only order cannot consume opening margin " + orderId);
        }
        long allocatedUnits = MarginTransferMath.orderMarginConsumeAmount(reservation.reservedUnits(),
                reservation.releasedUnits(), reservation.positionMarginUnits(), reservation.orderQuantitySteps(),
                openSteps, sweepRemainder);
        long excessUnits = MarginTransferMath.excessOrderMarginUnits(allocatedUnits, actualMarginUnits);
        if (actualMarginUnits <= 0 && excessUnits <= 0) {
            return;
        }
        if (actualMarginUnits > 0) {
            MarginMode normalizedMarginMode = MarginMode.defaultIfNull(marginMode);
            int reservationRows = jdbcTemplate.update("""
                    UPDATE account_margin_reservations
                       SET position_margin_units = position_margin_units + ?,
                           status = CASE
                               WHEN released_units + position_margin_units + ? >= reserved_units THEN 'CONSUMED'
                               ELSE 'PARTIALLY_CONSUMED'
                           END,
                           reason = 'POSITION_OPENED',
                           updated_at = ?
                     WHERE order_id = ?
                       AND released_units + position_margin_units + ? <= reserved_units
                    """, actualMarginUnits, actualMarginUnits, Timestamp.from(now), orderId, actualMarginUnits);
            requireSingleRow(reservationRows, "order margin consumption");
            int positionMarginRows = jdbcTemplate.update("""
                    INSERT INTO account_position_margins (
                        product_line, user_id, symbol, asset, margin_mode, position_side, margin_units, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (product_line, user_id, symbol, asset, margin_mode, position_side) DO UPDATE
                       SET margin_units = account_position_margins.margin_units + EXCLUDED.margin_units,
                           updated_at = EXCLUDED.updated_at
                    """, resolvedProductLine.name(), userId, symbol, reservation.asset(), normalizedMarginMode.name(),
                    reservation.positionSide().name(), actualMarginUnits,
                    Timestamp.from(now));
            requireSingleRow(positionMarginRows, "position margin upsert");
        }
        releaseReservedMargin(orderId, reservation.accountType(), reservation.userId(), reservation.asset(), excessUnits,
                "ORDER_PRICE_IMPROVEMENT", now);
    }

    public void releasePositionMargin(long userId,
                                      String symbol,
                                      MarginMode marginMode,
                                      long closeSteps,
                                      long positionAbsSteps,
                                      Instant now) {
        releasePositionMargin(userId, symbol, marginMode, closeSteps, PositionSide.NET, positionAbsSteps, now);
    }

    public void releasePositionMargin(long userId,
                                      String symbol,
                                      MarginMode marginMode,
                                      long closeSteps,
                                      PositionSide positionSide,
                                      long positionAbsSteps,
                                      Instant now) {
        releasePositionMargin(ProductLine.LINEAR_PERPETUAL, userId, symbol, marginMode, closeSteps, positionSide,
                positionAbsSteps, now);
    }

    public void releasePositionMargin(ProductLine productLine,
                                      long userId,
                                      String symbol,
                                      MarginMode marginMode,
                                      long closeSteps,
                                      PositionSide positionSide,
                                      long positionAbsSteps,
                                      Instant now) {
        ProductLine resolvedProductLine = productLine(productLine);
        MarginMode normalizedMarginMode = MarginMode.defaultIfNull(marginMode);
        PositionSide normalizedPositionSide = PositionSide.defaultIfNull(positionSide);
        List<PositionMargin> margins = jdbcTemplate.query("""
                SELECT m.asset,
                       m.margin_mode,
                       m.position_side,
                       m.margin_units,
                       ? AS account_type
                  FROM account_position_margins m
                 WHERE m.product_line = ?
                   AND m.user_id = ?
                   AND m.symbol = ?
                   AND m.margin_mode = ?
                   AND m.position_side = ?
                   AND m.margin_units > 0
                 FOR UPDATE OF m
                """, (rs, rowNum) -> new PositionMargin(symbol, rs.getString("asset"),
                MarginMode.fromNullableDbValue(rs.getString("margin_mode")),
                PositionSide.fromNullableDbValue(rs.getString("position_side")), rs.getLong("margin_units"),
                accountTypeFromNullableDbValue(rs.getString("account_type"))),
                resolvedProductLine.accountTypeCode(), resolvedProductLine.name(), userId, symbol,
                normalizedMarginMode.name(), normalizedPositionSide.name());
        for (PositionMargin margin : margins) {
            long amountUnits = MarginTransferMath.positionMarginReleaseAmount(margin.marginUnits(),
                    closeSteps, positionAbsSteps);
            if (amountUnits <= 0) {
                continue;
            }
            releaseBalanceLock(margin.accountType(), userId, margin.asset(), amountUnits, now);
            int marginRows = jdbcTemplate.update("""
                    UPDATE account_position_margins
                       SET margin_units = margin_units - ?,
                           updated_at = ?
                     WHERE user_id = ? AND symbol = ? AND asset = ?
                       AND margin_mode = ?
                       AND position_side = ?
                       AND product_line = ?
                       AND margin_units >= ?
                    """, amountUnits, Timestamp.from(now), userId, symbol, margin.asset(),
                    margin.marginMode().name(), margin.positionSide().name(), resolvedProductLine.name(), amountUnits);
            requireSingleRow(marginRows, "position margin release");
            jdbcTemplate.update("""
                    DELETE FROM account_position_margins
                     WHERE user_id = ? AND symbol = ? AND asset = ? AND margin_mode = ?
                       AND position_side = ? AND product_line = ? AND margin_units = 0
                    """, userId, symbol, margin.asset(), margin.marginMode().name(), margin.positionSide().name(),
                    resolvedProductLine.name());
        }
    }

    private OrderMarginReservation lockOrderMarginReservation(long orderId, long userId, String symbol) {
        return jdbcTemplate.query("""
                SELECT r.account_type, r.user_id, r.asset, r.reserved_units, r.released_units,
                       r.position_margin_units, r.margin_mode, r.position_side, o.quantity_steps, o.reduce_only
                  FROM account_margin_reservations r
                  JOIN trading_orders o
                    ON o.order_id = r.order_id
                 WHERE r.order_id = ?
                   AND r.user_id = ?
                   AND r.symbol = ?
                 FOR UPDATE OF r
                """, (rs, rowNum) -> new OrderMarginReservation(
                accountTypeFromNullableDbValue(rs.getString("account_type")),
                rs.getLong("user_id"),
                rs.getString("asset"),
                rs.getLong("reserved_units"),
                rs.getLong("released_units"),
                rs.getLong("position_margin_units"),
                MarginMode.fromNullableDbValue(rs.getString("margin_mode")),
                PositionSide.fromNullableDbValue(rs.getString("position_side")),
                rs.getLong("quantity_steps"),
                rs.getBoolean("reduce_only")), orderId, userId, symbol).stream().findFirst().orElse(null);
    }

    private void requireReservationUnlessReduceOnly(long orderId, long userId, String symbol) {
        Boolean reduceOnly = jdbcTemplate.query("""
                SELECT reduce_only
                  FROM trading_orders
                 WHERE order_id = ?
                   AND user_id = ?
                   AND symbol = ?
                 FOR UPDATE
                """, (rs, rowNum) -> rs.getBoolean("reduce_only"), orderId, userId, symbol)
                .stream()
                .findFirst()
                .orElse(null);
        if (reduceOnly == null) {
            throw new IllegalStateException("order not found for account margin release " + orderId);
        }
        if (!reduceOnly) {
            throw new IllegalStateException("missing order margin reservation for closing fill " + orderId);
        }
    }

    private void releaseReservedMargin(long orderId,
                                       AccountType accountType,
                                       long userId,
                                       String asset,
                                       long amountUnits,
                                       String reason,
                                       Instant now) {
        if (amountUnits <= 0) {
            return;
        }
        releaseBalanceLock(accountType, userId, asset, amountUnits, now);
        int reservationRows = jdbcTemplate.update("""
                UPDATE account_margin_reservations
                   SET released_units = released_units + ?,
                       status = CASE
                           WHEN released_units + ? >= reserved_units AND position_margin_units = 0 THEN 'RELEASED'
                           WHEN released_units + ? + position_margin_units >= reserved_units THEN 'CONSUMED'
                           WHEN position_margin_units > 0 THEN 'PARTIALLY_CONSUMED'
                           ELSE 'PARTIALLY_RELEASED'
                       END,
                       reason = ?,
                       updated_at = ?
                 WHERE order_id = ?
                """, amountUnits, amountUnits, amountUnits, reason, Timestamp.from(now), orderId);
        requireSingleRow(reservationRows, "reserved margin release");
    }

    private void releaseBalanceLock(AccountType accountType, long userId, String asset, long amountUnits, Instant now) {
        if (isLegacyPerpetualAccount(accountType)) {
            releaseLegacyBalanceLock(userId, asset, amountUnits, now);
            return;
        }
        int rows = jdbcTemplate.update("""
                UPDATE account_product_balances
                   SET locked_units = locked_units - ?,
                       available_units = available_units + ?,
                       updated_at = ?
                 WHERE account_type = ?
                   AND user_id = ?
                   AND asset = ?
                   AND locked_units >= ?
                """, amountUnits, amountUnits, Timestamp.from(now), accountType.name(), userId, asset, amountUnits);
        if (rows != 1) {
            throw new IllegalStateException("insufficient locked product balance for margin release");
        }
    }

    private long debitOptionPremium(AccountType accountType,
                                    long userId,
                                    String asset,
                                    long orderId,
                                    String symbol,
                                    MarginMode marginMode,
                                    long premiumUnits,
                                    boolean orderCompleted,
                                    Instant now) {
        OrderMarginReservation reservation = lockOrderMarginReservation(orderId, userId, symbol);
        if (reservation == null) {
            return applyAmountToBalance(accountType, userId, asset, symbol, marginMode,
                    Math.negateExact(premiumUnits), now);
        }
        long spendableUnits = Math.subtractExact(reservation.reservedUnits(),
                Math.addExact(reservation.releasedUnits(), reservation.positionMarginUnits()));
        if (spendableUnits < premiumUnits) {
            throw new IllegalStateException("reserved option premium is smaller than filled amount for order "
                    + orderId);
        }
        debitBalanceLock(accountType, userId, asset, premiumUnits, now);
        int reservationRows = jdbcTemplate.update("""
                UPDATE account_margin_reservations
                   SET released_units = released_units + ?,
                       status = CASE
                           WHEN released_units + ? >= reserved_units AND position_margin_units = 0 THEN 'RELEASED'
                           WHEN released_units + ? + position_margin_units >= reserved_units THEN 'CONSUMED'
                           WHEN position_margin_units > 0 THEN 'PARTIALLY_CONSUMED'
                           ELSE 'PARTIALLY_RELEASED'
                       END,
                       reason = 'OPTION_PREMIUM_PAID',
                       updated_at = ?
                 WHERE order_id = ?
                   AND released_units + position_margin_units + ? <= reserved_units
                """, premiumUnits, premiumUnits, premiumUnits, Timestamp.from(now), orderId, premiumUnits);
        requireSingleRow(reservationRows, "option premium reservation debit");
        if (orderCompleted) {
            long remainingUnits = Math.subtractExact(spendableUnits, premiumUnits);
            releaseReservedMargin(orderId, reservation.accountType(), reservation.userId(), reservation.asset(),
                    remainingUnits, "OPTION_PREMIUM_REMAINDER", now);
        }
        return productEquity(accountType, userId, asset);
    }

    private void debitBalanceLock(AccountType accountType, long userId, String asset, long amountUnits, Instant now) {
        int rows = jdbcTemplate.update("""
                UPDATE account_product_balances
                   SET locked_units = locked_units - ?,
                       updated_at = ?
                 WHERE account_type = ?
                   AND user_id = ?
                   AND asset = ?
                   AND locked_units >= ?
                """, amountUnits, Timestamp.from(now), accountType.name(), userId, asset, amountUnits);
        if (rows != 1) {
            throw new IllegalStateException("insufficient locked product balance for option premium");
        }
    }

    private long productEquity(AccountType accountType, long userId, String asset) {
        Long equityUnits = jdbcTemplate.queryForObject("""
                SELECT b.available_units + b.locked_units - COALESCE(d.deficit_units, 0) AS equity_units
                  FROM account_product_balances b
             LEFT JOIN account_product_deficits d USING (account_type, user_id, asset)
                 WHERE b.account_type = ?
                   AND b.user_id = ?
                   AND b.asset = ?
                """, Long.class, accountType.name(), userId, asset);
        return equityUnits == null ? 0L : equityUnits;
    }

    private void releaseLegacyBalanceLock(long userId, String asset, long amountUnits, Instant now) {
        int rows = jdbcTemplate.update("""
                UPDATE account_balances
                   SET locked_units = locked_units - ?,
                       available_units = available_units + ?,
                       updated_at = ?
                 WHERE user_id = ? AND asset = ?
                   AND locked_units >= ?
                """, amountUnits, amountUnits, Timestamp.from(now), userId, asset, amountUnits);
        if (rows != 1) {
            throw new IllegalStateException("insufficient locked balance for margin release");
        }
    }

    private long applyAmountToBalance(AccountType accountType,
                                      long userId,
                                      String asset,
                                      String symbol,
                                      MarginMode marginMode,
                                      long amountUnits,
                                      Instant now) {
        if (isLegacyPerpetualAccount(accountType)) {
            return applyAmountToLegacyBalance(userId, asset, symbol, marginMode, amountUnits, now);
        }
        return applyAmountToProductBalance(accountType, userId, asset, symbol, marginMode, amountUnits, now);
    }

    private long applyAmountToLegacyBalance(long userId,
                                            String asset,
                                            String symbol,
                                            MarginMode marginMode,
                                            long amountUnits,
                                            Instant now) {
        jdbcTemplate.update("""
                INSERT INTO account_balances (user_id, asset, available_units, locked_units, updated_at)
                VALUES (?, ?, 0, 0, ?)
                ON CONFLICT (user_id, asset) DO NOTHING
                """, userId, asset, Timestamp.from(now));
        jdbcTemplate.update("""
                INSERT INTO account_deficits (user_id, asset, deficit_units, updated_at)
                VALUES (?, ?, 0, ?)
                ON CONFLICT (user_id, asset) DO NOTHING
                """, userId, asset, Timestamp.from(now));
        MarginMode normalizedMarginMode = MarginMode.defaultIfNull(marginMode);
        Optional<Long> availableDebitFastPath = tryApplyLegacyAvailableDebitFastPath(
                userId, asset, normalizedMarginMode, amountUnits, now);
        if (availableDebitFastPath.isPresent()) {
            return availableDebitFastPath.get();
        }
        List<PositionMargin> lockedMargins = amountUnits < 0
                ? lockPositionMargins(ProductLine.LINEAR_PERPETUAL, userId, asset, symbol, normalizedMarginMode)
                : List.of();
        long maxLockedDebitUnits = lockedMargins.stream()
                .mapToLong(PositionMargin::marginUnits)
                .reduce(0L, Math::addExact);
        BalanceSettlementState current = jdbcTemplate.queryForObject("""
                SELECT b.available_units, b.locked_units, d.deficit_units
                  FROM account_balances b
                  JOIN account_deficits d USING (user_id, asset)
                 WHERE b.user_id = ? AND b.asset = ?
                 FOR UPDATE OF b, d
        """, (rs, rowNum) -> new BalanceSettlementState(
                rs.getLong("available_units"),
                rs.getLong("locked_units"),
                rs.getLong("deficit_units")), userId, asset);
        long availableInput = amountUnits < 0 && normalizedMarginMode == MarginMode.ISOLATED
                ? 0L
                : current.availableUnits();
        BalanceSettlementState next = PnlSettlementMath.apply(availableInput, current.lockedUnits(),
                current.deficitUnits(), amountUnits, maxLockedDebitUnits);
        if (amountUnits < 0 && normalizedMarginMode == MarginMode.ISOLATED) {
            next = new BalanceSettlementState(current.availableUnits(), next.lockedUnits(), next.deficitUnits());
        }
        long lockedDebitUnits = Math.subtractExact(current.lockedUnits(), next.lockedUnits());
        reducePositionMargins(ProductLine.LINEAR_PERPETUAL, userId, asset, lockedDebitUnits, lockedMargins, now);
        int balanceRows = jdbcTemplate.update("""
                UPDATE account_balances
                   SET available_units = ?,
                       locked_units = ?,
                       updated_at = ?
                 WHERE user_id = ? AND asset = ?
                """, next.availableUnits(), next.lockedUnits(), Timestamp.from(now), userId, asset);
        requireSingleRow(balanceRows, "pnl balance update");
        updateDeficitIfChanged(userId, asset, current.deficitUnits(), next.deficitUnits(), now,
                "pnl deficit update");
        return PnlSettlementMath.netEquityUnits(next.availableUnits(), next.lockedUnits(), next.deficitUnits());
    }

    private long applyAmountToProductBalance(AccountType accountType,
                                             long userId,
                                             String asset,
                                             String symbol,
                                             MarginMode marginMode,
                                             long amountUnits,
                                             Instant now) {
        jdbcTemplate.update("""
                INSERT INTO account_product_balances (
                    account_type, user_id, asset, available_units, locked_units, updated_at
                ) VALUES (?, ?, ?, 0, 0, ?)
                ON CONFLICT (account_type, user_id, asset) DO NOTHING
                """, accountType.name(), userId, asset, Timestamp.from(now));
        jdbcTemplate.update("""
                INSERT INTO account_product_deficits (account_type, user_id, asset, deficit_units, updated_at)
                VALUES (?, ?, ?, 0, ?)
                ON CONFLICT (account_type, user_id, asset) DO NOTHING
                """, accountType.name(), userId, asset, Timestamp.from(now));
        MarginMode normalizedMarginMode = MarginMode.defaultIfNull(marginMode);
        Optional<Long> availableDebitFastPath = tryApplyProductAvailableDebitFastPath(
                accountType, userId, asset, normalizedMarginMode, amountUnits, now);
        if (availableDebitFastPath.isPresent()) {
            return availableDebitFastPath.get();
        }
        ProductLine resolvedProductLine = accountType.productLine().orElse(ProductLine.LINEAR_PERPETUAL);
        List<PositionMargin> lockedMargins = amountUnits < 0
                ? lockPositionMargins(resolvedProductLine, userId, asset, symbol, normalizedMarginMode)
                : List.of();
        long maxLockedDebitUnits = lockedMargins.stream()
                .mapToLong(PositionMargin::marginUnits)
                .reduce(0L, Math::addExact);
        BalanceSettlementState current = jdbcTemplate.queryForObject("""
                SELECT b.available_units, b.locked_units, d.deficit_units
                  FROM account_product_balances b
                  JOIN account_product_deficits d USING (account_type, user_id, asset)
                 WHERE b.account_type = ? AND b.user_id = ? AND b.asset = ?
                 FOR UPDATE OF b, d
        """, (rs, rowNum) -> new BalanceSettlementState(
                rs.getLong("available_units"),
                rs.getLong("locked_units"),
                rs.getLong("deficit_units")), accountType.name(), userId, asset);
        long availableInput = amountUnits < 0 && normalizedMarginMode == MarginMode.ISOLATED
                ? 0L
                : current.availableUnits();
        BalanceSettlementState next = PnlSettlementMath.apply(availableInput, current.lockedUnits(),
                current.deficitUnits(), amountUnits, maxLockedDebitUnits);
        if (amountUnits < 0 && normalizedMarginMode == MarginMode.ISOLATED) {
            next = new BalanceSettlementState(current.availableUnits(), next.lockedUnits(), next.deficitUnits());
        }
        long lockedDebitUnits = Math.subtractExact(current.lockedUnits(), next.lockedUnits());
        reducePositionMargins(resolvedProductLine, userId, asset, lockedDebitUnits, lockedMargins, now);
        int balanceRows = jdbcTemplate.update("""
                UPDATE account_product_balances
                   SET available_units = ?,
                       locked_units = ?,
                       updated_at = ?
                 WHERE account_type = ?
                   AND user_id = ?
                   AND asset = ?
                """, next.availableUnits(), next.lockedUnits(), Timestamp.from(now), accountType.name(), userId,
                asset);
        requireSingleRow(balanceRows, "product pnl balance update");
        updateProductDeficitIfChanged(accountType, userId, asset, current.deficitUnits(), next.deficitUnits(), now,
                "product pnl deficit update");
        return PnlSettlementMath.netEquityUnits(next.availableUnits(), next.lockedUnits(), next.deficitUnits());
    }

    private BalanceDebitResult applyCappedDebitToBalance(AccountType accountType,
                                                         long userId,
                                                         String asset,
                                                         String symbol,
                                                         MarginMode marginMode,
                                                         long requestedDebitUnits,
                                                         Instant now) {
        if (isLegacyPerpetualAccount(accountType)) {
            return applyCappedDebitToLegacyBalance(userId, asset, symbol, marginMode, requestedDebitUnits, now);
        }
        return applyCappedDebitToProductBalance(accountType, userId, asset, symbol, marginMode, requestedDebitUnits,
                now);
    }

    private BalanceDebitResult applyCappedDebitToLegacyBalance(long userId,
                                                               String asset,
                                                               String symbol,
                                                               MarginMode marginMode,
                                                               long requestedDebitUnits,
                                                               Instant now) {
        jdbcTemplate.update("""
                INSERT INTO account_balances (user_id, asset, available_units, locked_units, updated_at)
                VALUES (?, ?, 0, 0, ?)
                ON CONFLICT (user_id, asset) DO NOTHING
                """, userId, asset, Timestamp.from(now));
        jdbcTemplate.update("""
                INSERT INTO account_deficits (user_id, asset, deficit_units, updated_at)
                VALUES (?, ?, 0, ?)
                ON CONFLICT (user_id, asset) DO NOTHING
                """, userId, asset, Timestamp.from(now));
        MarginMode normalizedMarginMode = MarginMode.defaultIfNull(marginMode);
        List<PositionMargin> lockedMargins = lockPositionMargins(ProductLine.LINEAR_PERPETUAL, userId, asset, symbol,
                normalizedMarginMode);
        long maxLockedDebitUnits = lockedMargins.stream()
                .mapToLong(PositionMargin::marginUnits)
                .reduce(0L, Math::addExact);
        BalanceSettlementState current = jdbcTemplate.queryForObject("""
                SELECT b.available_units, b.locked_units, d.deficit_units
                  FROM account_balances b
                  JOIN account_deficits d USING (user_id, asset)
                 WHERE b.user_id = ? AND b.asset = ?
                 FOR UPDATE OF b, d
        """, (rs, rowNum) -> new BalanceSettlementState(
                rs.getLong("available_units"),
                rs.getLong("locked_units"),
                rs.getLong("deficit_units")), userId, asset);
        long availableInput = normalizedMarginMode == MarginMode.ISOLATED ? 0L : current.availableUnits();
        long collectibleUnits = Math.min(requestedDebitUnits, Math.addExact(availableInput, maxLockedDebitUnits));
        if (collectibleUnits <= 0) {
            return new BalanceDebitResult(0L, PnlSettlementMath.netEquityUnits(current.availableUnits(),
                    current.lockedUnits(), current.deficitUnits()));
        }
        BalanceSettlementState next = PnlSettlementMath.apply(availableInput, current.lockedUnits(),
                current.deficitUnits(), Math.negateExact(collectibleUnits), maxLockedDebitUnits);
        if (normalizedMarginMode == MarginMode.ISOLATED) {
            next = new BalanceSettlementState(current.availableUnits(), next.lockedUnits(), next.deficitUnits());
        }
        if (next.deficitUnits() != current.deficitUnits()) {
            throw new IllegalStateException("liquidation fee must not create account deficit");
        }
        long lockedDebitUnits = Math.subtractExact(current.lockedUnits(), next.lockedUnits());
        reducePositionMargins(ProductLine.LINEAR_PERPETUAL, userId, asset, lockedDebitUnits, lockedMargins, now);
        int balanceRows = jdbcTemplate.update("""
                UPDATE account_balances
                   SET available_units = ?,
                       locked_units = ?,
                       updated_at = ?
                 WHERE user_id = ? AND asset = ?
                """, next.availableUnits(), next.lockedUnits(), Timestamp.from(now), userId, asset);
        requireSingleRow(balanceRows, "liquidation fee balance update");
        updateDeficitIfChanged(userId, asset, current.deficitUnits(), next.deficitUnits(), now,
                "liquidation fee deficit update");
        return new BalanceDebitResult(collectibleUnits,
                PnlSettlementMath.netEquityUnits(next.availableUnits(), next.lockedUnits(), next.deficitUnits()));
    }

    private BalanceDebitResult applyCappedDebitToProductBalance(AccountType accountType,
                                                                long userId,
                                                                String asset,
                                                                String symbol,
                                                                MarginMode marginMode,
                                                                long requestedDebitUnits,
                                                                Instant now) {
        jdbcTemplate.update("""
                INSERT INTO account_product_balances (
                    account_type, user_id, asset, available_units, locked_units, updated_at
                ) VALUES (?, ?, ?, 0, 0, ?)
                ON CONFLICT (account_type, user_id, asset) DO NOTHING
                """, accountType.name(), userId, asset, Timestamp.from(now));
        jdbcTemplate.update("""
                INSERT INTO account_product_deficits (account_type, user_id, asset, deficit_units, updated_at)
                VALUES (?, ?, ?, 0, ?)
                ON CONFLICT (account_type, user_id, asset) DO NOTHING
                """, accountType.name(), userId, asset, Timestamp.from(now));
        MarginMode normalizedMarginMode = MarginMode.defaultIfNull(marginMode);
        ProductLine resolvedProductLine = accountType.productLine().orElse(ProductLine.LINEAR_PERPETUAL);
        List<PositionMargin> lockedMargins = lockPositionMargins(resolvedProductLine, userId, asset, symbol,
                normalizedMarginMode);
        long maxLockedDebitUnits = lockedMargins.stream()
                .mapToLong(PositionMargin::marginUnits)
                .reduce(0L, Math::addExact);
        BalanceSettlementState current = jdbcTemplate.queryForObject("""
                SELECT b.available_units, b.locked_units, d.deficit_units
                  FROM account_product_balances b
                  JOIN account_product_deficits d USING (account_type, user_id, asset)
                 WHERE b.account_type = ? AND b.user_id = ? AND b.asset = ?
                 FOR UPDATE OF b, d
        """, (rs, rowNum) -> new BalanceSettlementState(
                rs.getLong("available_units"),
                rs.getLong("locked_units"),
                rs.getLong("deficit_units")), accountType.name(), userId, asset);
        long availableInput = normalizedMarginMode == MarginMode.ISOLATED ? 0L : current.availableUnits();
        long collectibleUnits = Math.min(requestedDebitUnits, Math.addExact(availableInput, maxLockedDebitUnits));
        if (collectibleUnits <= 0) {
            return new BalanceDebitResult(0L, PnlSettlementMath.netEquityUnits(current.availableUnits(),
                    current.lockedUnits(), current.deficitUnits()));
        }
        BalanceSettlementState next = PnlSettlementMath.apply(availableInput, current.lockedUnits(),
                current.deficitUnits(), Math.negateExact(collectibleUnits), maxLockedDebitUnits);
        if (normalizedMarginMode == MarginMode.ISOLATED) {
            next = new BalanceSettlementState(current.availableUnits(), next.lockedUnits(), next.deficitUnits());
        }
        if (next.deficitUnits() != current.deficitUnits()) {
            throw new IllegalStateException("liquidation fee must not create account deficit");
        }
        long lockedDebitUnits = Math.subtractExact(current.lockedUnits(), next.lockedUnits());
        reducePositionMargins(resolvedProductLine, userId, asset, lockedDebitUnits, lockedMargins, now);
        int balanceRows = jdbcTemplate.update("""
                UPDATE account_product_balances
                   SET available_units = ?,
                       locked_units = ?,
                       updated_at = ?
                 WHERE account_type = ?
                   AND user_id = ?
                   AND asset = ?
                """, next.availableUnits(), next.lockedUnits(), Timestamp.from(now), accountType.name(), userId,
                asset);
        requireSingleRow(balanceRows, "product liquidation fee balance update");
        updateProductDeficitIfChanged(accountType, userId, asset, current.deficitUnits(), next.deficitUnits(), now,
                "product liquidation fee deficit update");
        return new BalanceDebitResult(collectibleUnits,
                PnlSettlementMath.netEquityUnits(next.availableUnits(), next.lockedUnits(), next.deficitUnits()));
    }

    private Optional<Long> tryApplyLegacyAvailableDebitFastPath(long userId,
                                                                String asset,
                                                                MarginMode marginMode,
                                                                long amountUnits,
                                                                Instant now) {
        if (amountUnits >= 0 || marginMode != MarginMode.CROSS) {
            return Optional.empty();
        }
        List<Long> rows = jdbcTemplate.query("""
                UPDATE account_balances b
                   SET available_units = b.available_units + ?,
                       updated_at = ?
                 WHERE b.user_id = ?
                   AND b.asset = ?
                   AND b.available_units + ? >= 0
                 RETURNING b.available_units + b.locked_units - COALESCE((
                       SELECT d.deficit_units
                         FROM account_deficits d
                        WHERE d.user_id = b.user_id
                          AND d.asset = b.asset
                   ), 0) AS balance_after_units
                """, (rs, rowNum) -> rs.getLong("balance_after_units"),
                amountUnits, Timestamp.from(now), userId, asset, amountUnits);
        return rows == null ? Optional.empty() : rows.stream().findFirst();
    }

    private Optional<Long> tryApplyProductAvailableDebitFastPath(AccountType accountType,
                                                                 long userId,
                                                                 String asset,
                                                                 MarginMode marginMode,
                                                                 long amountUnits,
                                                                 Instant now) {
        if (amountUnits >= 0 || marginMode != MarginMode.CROSS) {
            return Optional.empty();
        }
        List<Long> rows = jdbcTemplate.query("""
                UPDATE account_product_balances b
                   SET available_units = b.available_units + ?,
                       updated_at = ?
                 WHERE b.account_type = ?
                   AND b.user_id = ?
                   AND b.asset = ?
                   AND b.available_units + ? >= 0
                RETURNING b.available_units + b.locked_units - COALESCE((
                       SELECT d.deficit_units
                         FROM account_product_deficits d
                        WHERE d.account_type = b.account_type
                          AND d.user_id = b.user_id
                          AND d.asset = b.asset
                   ), 0) AS balance_after_units
                """, (rs, rowNum) -> rs.getLong("balance_after_units"),
                amountUnits, Timestamp.from(now), accountType.name(), userId, asset, amountUnits);
        return rows == null ? Optional.empty() : rows.stream().findFirst();
    }

    private void updateDeficitIfChanged(long userId,
                                        String asset,
                                        long currentDeficitUnits,
                                        long nextDeficitUnits,
                                        Instant now,
                                        String operation) {
        if (currentDeficitUnits == nextDeficitUnits) {
            return;
        }
        int deficitRows = jdbcTemplate.update("""
                UPDATE account_deficits
                   SET deficit_units = ?,
                       updated_at = ?
                 WHERE user_id = ? AND asset = ?
                """, nextDeficitUnits, Timestamp.from(now), userId, asset);
        requireSingleRow(deficitRows, operation);
    }

    private void updateProductDeficitIfChanged(AccountType accountType,
                                               long userId,
                                               String asset,
                                               long currentDeficitUnits,
                                               long nextDeficitUnits,
                                               Instant now,
                                               String operation) {
        if (currentDeficitUnits == nextDeficitUnits) {
            return;
        }
        int deficitRows = jdbcTemplate.update("""
                UPDATE account_product_deficits
                   SET deficit_units = ?,
                       updated_at = ?
                 WHERE account_type = ?
                   AND user_id = ?
                   AND asset = ?
                """, nextDeficitUnits, Timestamp.from(now), accountType.name(), userId, asset);
        requireSingleRow(deficitRows, operation);
    }

    private boolean liquidationFeeReferenceExists(AccountType accountType, long userId, String asset,
                                                  String referenceId) {
        if (!isLegacyPerpetualAccount(accountType)) {
            return jdbcTemplate.query("""
                    SELECT 1
                      FROM account_product_ledger_entries
                     WHERE reference_type = 'LIQUIDATION_FEE'
                       AND reference_id = ?
                       AND user_id = ?
                       AND account_type = ?
                       AND asset = ?
                     FOR UPDATE
                    """, (rs, rowNum) -> 1, referenceId, userId, accountType.name(), asset)
                    .stream().findFirst().isPresent();
        }
        return jdbcTemplate.query("""
                SELECT 1
                  FROM account_ledger_entries
                 WHERE reference_type = 'LIQUIDATION_FEE'
                   AND reference_id = ?
                   AND user_id = ?
                   AND asset = ?
                 FOR UPDATE
                """, (rs, rowNum) -> 1, referenceId, userId, asset).stream().findFirst().isPresent();
    }

    private void insertProductSettlementLedger(long userId,
                                               AccountType accountType,
                                               String asset,
                                               long amountUnits,
                                               long balanceAfterUnits,
                                               String referenceType,
                                               String referenceId,
                                               String reason,
                                               Instant now) {
        int rows = jdbcTemplate.update("""
                INSERT INTO account_product_ledger_entries (
                    entry_id, user_id, account_type, asset, amount_units, balance_after_units,
                    reference_type, reference_id, reason, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (reference_type, reference_id, user_id, account_type, asset) DO NOTHING
                """, sequenceRepository.nextSequence("product-ledger-entry"), userId, accountType.name(), asset,
                amountUnits, balanceAfterUnits, referenceType, referenceId, reason, Timestamp.from(now));
        requireSingleRow(rows, referenceType.toLowerCase().replace('_', ' ') + " product ledger insert");
    }

    private boolean tryInsertProductSettlementLedger(long userId,
                                                     AccountType accountType,
                                                     String asset,
                                                     long amountUnits,
                                                     long balanceAfterUnits,
                                                     String referenceType,
                                                     String referenceId,
                                                     String reason,
                                                     Instant now) {
        int rows = jdbcTemplate.update("""
                INSERT INTO account_product_ledger_entries (
                    entry_id, user_id, account_type, asset, amount_units, balance_after_units,
                    reference_type, reference_id, reason, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (reference_type, reference_id, user_id, account_type, asset) DO NOTHING
                """, sequenceRepository.nextSequence("product-ledger-entry"), userId, accountType.name(), asset,
                amountUnits, balanceAfterUnits, referenceType, referenceId, reason, Timestamp.from(now));
        return rows == 1;
    }

    private void updateProductSettlementLedgerBalance(long userId,
                                                      AccountType accountType,
                                                      String asset,
                                                      String referenceType,
                                                      String referenceId,
                                                      long balanceAfterUnits) {
        int rows = jdbcTemplate.update("""
                UPDATE account_product_ledger_entries
                   SET balance_after_units = ?
                 WHERE reference_type = ?
                   AND reference_id = ?
                   AND user_id = ?
                   AND account_type = ?
                   AND asset = ?
                """, balanceAfterUnits, referenceType, referenceId, userId, accountType.name(), asset);
        requireSingleRow(rows, referenceType.toLowerCase().replace('_', ' ') + " product ledger update");
    }

    private List<PositionMargin> lockPositionMargins(long userId, String asset, String symbol, MarginMode marginMode) {
        return lockPositionMargins(ProductLine.LINEAR_PERPETUAL, userId, asset, symbol, marginMode);
    }

    private List<PositionMargin> lockPositionMargins(ProductLine productLine,
                                                     long userId,
                                                     String asset,
                                                     String symbol,
                                                     MarginMode marginMode) {
        ProductLine resolvedProductLine = productLine(productLine);
        MarginMode normalizedMarginMode = MarginMode.defaultIfNull(marginMode);
        if (normalizedMarginMode == MarginMode.ISOLATED) {
            return jdbcTemplate.query("""
                    SELECT symbol, asset, margin_mode, margin_units
                      FROM account_position_margins
                     WHERE product_line = ?
                       AND user_id = ?
                       AND asset = ?
                       AND symbol = ?
                       AND margin_mode = ?
                       AND margin_units > 0
                     ORDER BY updated_at ASC, symbol ASC, margin_mode ASC
                     FOR UPDATE
                    """, (rs, rowNum) -> new PositionMargin(
                    rs.getString("symbol"),
                    rs.getString("asset"),
                    MarginMode.fromNullableDbValue(rs.getString("margin_mode")),
                    rs.getLong("margin_units")), resolvedProductLine.name(), userId, asset, symbol,
                    normalizedMarginMode.name());
        }
        return jdbcTemplate.query("""
                SELECT symbol, asset, margin_mode, margin_units
                  FROM account_position_margins
                 WHERE product_line = ?
                   AND user_id = ?
                   AND asset = ?
                   AND margin_mode = ?
                   AND margin_units > 0
                 ORDER BY updated_at ASC, symbol ASC, margin_mode ASC
                 FOR UPDATE
                """, (rs, rowNum) -> new PositionMargin(
                rs.getString("symbol"),
                rs.getString("asset"),
                MarginMode.fromNullableDbValue(rs.getString("margin_mode")),
                rs.getLong("margin_units")), resolvedProductLine.name(), userId, asset, normalizedMarginMode.name());
    }

    private void reducePositionMargins(long userId,
                                       String asset,
                                       long amountUnits,
                                       List<PositionMargin> lockedMargins,
                                       Instant now) {
        reducePositionMargins(ProductLine.LINEAR_PERPETUAL, userId, asset, amountUnits, lockedMargins, now);
    }

    private void reducePositionMargins(ProductLine productLine,
                                       long userId,
                                       String asset,
                                       long amountUnits,
                                       List<PositionMargin> lockedMargins,
                                       Instant now) {
        ProductLine resolvedProductLine = productLine(productLine);
        long remaining = amountUnits;
        for (PositionMargin margin : lockedMargins) {
            if (remaining <= 0) {
                break;
            }
            long debit = Math.min(margin.marginUnits(), remaining);
            int rows = jdbcTemplate.update("""
                    UPDATE account_position_margins
                       SET margin_units = margin_units - ?,
                           updated_at = ?
                     WHERE user_id = ? AND symbol = ? AND asset = ?
                       AND margin_mode = ?
                       AND product_line = ?
                       AND margin_units >= ?
                    """, debit, Timestamp.from(now), userId, margin.symbol(), asset,
                    margin.marginMode().name(), resolvedProductLine.name(), debit);
            if (rows != 1) {
                throw new IllegalStateException("failed to reduce consumed position margin");
            }
            jdbcTemplate.update("""
                    DELETE FROM account_position_margins
                     WHERE user_id = ? AND symbol = ? AND asset = ? AND margin_mode = ?
                       AND product_line = ? AND margin_units = 0
                    """, userId, margin.symbol(), asset, margin.marginMode().name(), resolvedProductLine.name());
            remaining = Math.subtractExact(remaining, debit);
        }
        if (remaining != 0) {
            throw new IllegalStateException("insufficient position margin for locked debit");
        }
    }

    private SpotReservation lockSpotReservation(long orderId, long userId, String symbol) {
        return jdbcTemplate.query("""
                SELECT user_id, side, asset, reserved_units, settled_units, released_units
                  FROM account_spot_order_reservations
                 WHERE order_id = ?
                   AND user_id = ?
                   AND symbol = ?
                   AND status NOT IN ('RELEASED', 'SETTLED')
                 FOR UPDATE
                """, (rs, rowNum) -> new SpotReservation(
                rs.getLong("user_id"),
                OrderSide.valueOf(rs.getString("side")),
                rs.getString("asset"),
                rs.getLong("reserved_units"),
                rs.getLong("settled_units"),
                rs.getLong("released_units")), orderId, userId, symbol).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("active spot reservation not found for order " + orderId));
    }

    private void debitSpotLocked(long userId,
                                 String asset,
                                 long amountUnits,
                                 Instant now,
                                 long tradeId,
                                 long orderId,
                                 String reason) {
        if (amountUnits <= 0) {
            return;
        }
        ensureSpotBalance(userId, asset, now);
        int rows = jdbcTemplate.update("""
                UPDATE account_product_balances
                   SET locked_units = locked_units - ?,
                       updated_at = ?
                 WHERE account_type = 'SPOT'
                   AND user_id = ?
                   AND asset = ?
                   AND locked_units >= ?
                """, amountUnits, Timestamp.from(now), userId, asset, amountUnits);
        if (rows != 1) {
            throw new IllegalStateException("insufficient locked spot balance for order " + orderId);
        }
        insertSpotLedger(userId, asset, Math.negateExact(amountUnits), spotEquity(userId, asset),
                tradeId, orderId, reason, now);
    }

    private void releaseSpotLocked(long userId, String asset, long amountUnits, Instant now) {
        if (amountUnits <= 0) {
            return;
        }
        ensureSpotBalance(userId, asset, now);
        int rows = jdbcTemplate.update("""
                UPDATE account_product_balances
                   SET locked_units = locked_units - ?,
                       available_units = available_units + ?,
                       updated_at = ?
                 WHERE account_type = 'SPOT'
                   AND user_id = ?
                   AND asset = ?
                   AND locked_units >= ?
                """, amountUnits, amountUnits, Timestamp.from(now), userId, asset, amountUnits);
        if (rows != 1) {
            throw new IllegalStateException("insufficient locked spot balance for release");
        }
    }

    private void creditSpotAvailable(long userId,
                                     String asset,
                                     long amountUnits,
                                     Instant now,
                                     long tradeId,
                                     long orderId,
                                     String reason) {
        if (amountUnits <= 0) {
            return;
        }
        ensureSpotBalance(userId, asset, now);
        int rows = jdbcTemplate.update("""
                UPDATE account_product_balances
                   SET available_units = available_units + ?,
                       updated_at = ?
                 WHERE account_type = 'SPOT'
                   AND user_id = ?
                   AND asset = ?
                """, amountUnits, Timestamp.from(now), userId, asset);
        requireSingleRow(rows, "spot available credit");
        insertSpotLedger(userId, asset, amountUnits, spotEquity(userId, asset),
                tradeId, orderId, reason, now);
    }

    private void debitSpotAvailable(long userId,
                                    String asset,
                                    long amountUnits,
                                    Instant now,
                                    long tradeId,
                                    long orderId,
                                    String reason) {
        if (amountUnits <= 0) {
            return;
        }
        ensureSpotBalance(userId, asset, now);
        int rows = jdbcTemplate.update("""
                UPDATE account_product_balances
                   SET available_units = available_units - ?,
                       updated_at = ?
                 WHERE account_type = 'SPOT'
                   AND user_id = ?
                   AND asset = ?
                   AND available_units >= ?
                """, amountUnits, Timestamp.from(now), userId, asset, amountUnits);
        if (rows != 1) {
            throw new IllegalStateException("insufficient available spot balance for fee");
        }
        insertSpotLedger(userId, asset, Math.negateExact(amountUnits), spotEquity(userId, asset),
                tradeId, orderId, reason, now);
    }

    private void ensureSpotBalance(long userId, String asset, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO account_product_balances (
                    account_type, user_id, asset, available_units, locked_units, updated_at
                ) VALUES ('SPOT', ?, ?, 0, 0, ?)
                ON CONFLICT (account_type, user_id, asset) DO NOTHING
                """, userId, asset, Timestamp.from(now));
    }

    private long spotEquity(long userId, String asset) {
        Long equityUnits = jdbcTemplate.queryForObject("""
                SELECT available_units + locked_units
                  FROM account_product_balances
                 WHERE account_type = 'SPOT'
                   AND user_id = ?
                   AND asset = ?
                """, Long.class, userId, asset);
        return equityUnits == null ? 0L : equityUnits;
    }

    private void insertSpotLedger(long userId,
                                  String asset,
                                  long amountUnits,
                                  long balanceAfterUnits,
                                  long tradeId,
                                  long orderId,
                                  String reason,
                                  Instant now) {
        if (amountUnits == 0) {
            return;
        }
        String referenceId = tradeId + ":" + orderId + ":" + reason;
        int rows = jdbcTemplate.update("""
                INSERT INTO account_product_ledger_entries (
                    entry_id, user_id, account_type, asset, amount_units, balance_after_units,
                    reference_type, reference_id, reason, created_at
                ) VALUES (?, ?, 'SPOT', ?, ?, ?, 'SPOT_TRADE', ?, ?, ?)
                ON CONFLICT (reference_type, reference_id, user_id, account_type, asset) DO NOTHING
                """, sequenceRepository.nextSequence("product-ledger-entry"), userId, asset, amountUnits,
                balanceAfterUnits, referenceId, reason, Timestamp.from(now));
        requireSingleRow(rows, "spot trade ledger insert");
    }

    private void updateSpotReservation(long orderId,
                                       long settledUnits,
                                       long releasedUnits,
                                       String reason,
                                       Instant now) {
        int rows = jdbcTemplate.update("""
                UPDATE account_spot_order_reservations
                   SET settled_units = settled_units + ?,
                       released_units = released_units + ?,
                       status = CASE
                           WHEN settled_units + released_units + ? + ? >= reserved_units THEN 'SETTLED'
                           WHEN settled_units + ? > 0 THEN 'PARTIALLY_SETTLED'
                           WHEN released_units + ? > 0 THEN 'PARTIALLY_RELEASED'
                           ELSE status
                       END,
                       reason = ?,
                       updated_at = ?
                 WHERE order_id = ?
                   AND settled_units + released_units + ? + ? <= reserved_units
                """, settledUnits, releasedUnits, settledUnits, releasedUnits, settledUnits, releasedUnits,
                reason, Timestamp.from(now), orderId, settledUnits, releasedUnits);
        requireSingleRow(rows, "spot reservation settlement update");
    }

    private long spotFeeUnits(long quoteUnits, long feeRatePpm) {
        if (feeRatePpm == 0) {
            return 0L;
        }
        BigInteger numerator = BigInteger.valueOf(quoteUnits)
                .multiply(BigInteger.valueOf(Math.absExact(feeRatePpm)));
        return divideCeiling(numerator, BigInteger.valueOf(PPM));
    }

    private long multiplyToLong(long... values) {
        BigInteger product = BigInteger.ONE;
        for (long value : values) {
            if (value <= 0) {
                throw new IllegalArgumentException("spot settlement inputs must be positive");
            }
            product = product.multiply(BigInteger.valueOf(value));
        }
        return product.longValueExact();
    }

    private long divideCeiling(BigInteger numerator, BigInteger denominator) {
        if (numerator.signum() < 0 || denominator.signum() <= 0) {
            throw new IllegalArgumentException("positive numerator and denominator are required");
        }
        BigInteger[] quotientAndRemainder = numerator.divideAndRemainder(denominator);
        return (quotientAndRemainder[1].signum() == 0
                ? quotientAndRemainder[0]
                : quotientAndRemainder[0].add(BigInteger.ONE)).longValueExact();
    }

    private ProductTransferResponse duplicateProductTransfer(long userId,
                                                             AccountType source,
                                                             AccountType target,
                                                             String asset,
                                                             long amountUnits,
                                                             String referenceId,
                                                             String reason) {
        ProductTransferRecord existing = jdbcTemplate.query("""
                SELECT transfer_id, source_account_type, target_account_type, asset,
                       amount_units, status, reason, created_at
                  FROM account_product_transfers
                 WHERE user_id = ?
                   AND reference_id = ?
                 FOR UPDATE
                """, (rs, rowNum) -> new ProductTransferRecord(
                rs.getLong("transfer_id"),
                AccountType.valueOf(rs.getString("source_account_type")),
                AccountType.valueOf(rs.getString("target_account_type")),
                rs.getString("asset"),
                rs.getLong("amount_units"),
                rs.getString("status"),
                rs.getString("reason"),
                rs.getTimestamp("created_at").toInstant()), userId, referenceId).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("duplicate product transfer but transfer row missing"));
        if (existing.sourceAccountType() != source
                || existing.targetAccountType() != target
                || existing.amountUnits() != amountUnits
                || !Objects.equals(existing.asset(), asset)
                || !Objects.equals(existing.reason(), reason)) {
            throw new IllegalStateException("conflicting duplicate product transfer reference " + referenceId);
        }
        return new ProductTransferResponse(existing.transferId(), userId, source, target, asset, amountUnits,
                referenceId, existing.status(),
                productBalance(userId, source, asset)
                        .orElseThrow(() -> new IllegalStateException("source balance missing for duplicate transfer")),
                productBalance(userId, target, asset)
                        .orElseThrow(() -> new IllegalStateException("target balance missing for duplicate transfer")),
                existing.createdAt());
    }

    private void requireDuplicateProductBalanceAdjustmentMatches(long userId,
                                                                 AccountType accountType,
                                                                 String asset,
                                                                 long amountUnits,
                                                                 String referenceId,
                                                                 String reason) {
        AdjustmentReference existing = jdbcTemplate.query("""
                SELECT amount_units, reason
                  FROM account_product_ledger_entries
                 WHERE reference_type = 'PRODUCT_BALANCE_ADJUSTMENT'
                   AND reference_id = ?
                   AND user_id = ?
                   AND account_type = ?
                   AND asset = ?
                 FOR UPDATE
                """, (rs, rowNum) -> new AdjustmentReference(
                rs.getLong("amount_units"),
                rs.getString("reason")), referenceId, userId, accountType.name(), asset)
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("duplicate product adjustment but ledger missing"));
        if (existing.amountUnits() != amountUnits || !Objects.equals(existing.reason(), reason)) {
            throw new IllegalStateException("conflicting duplicate product balance adjustment reference " + referenceId);
        }
    }

    private long applyProductAvailableDelta(long userId,
                                            AccountType accountType,
                                            String asset,
                                            long amountUnits,
                                            Instant now) {
        if (isLegacyPerpetualAccount(accountType)) {
            return applyLegacyAvailableDelta(userId, asset, amountUnits, now);
        }
        jdbcTemplate.update("""
                INSERT INTO account_product_balances (
                    account_type, user_id, asset, available_units, locked_units, updated_at
                ) VALUES (?, ?, ?, 0, 0, ?)
                ON CONFLICT (account_type, user_id, asset) DO NOTHING
                """, accountType.name(), userId, asset, Timestamp.from(now));
        Long currentAvailable = jdbcTemplate.queryForObject("""
                SELECT available_units
                  FROM account_product_balances
                 WHERE account_type = ?
                   AND user_id = ?
                   AND asset = ?
                 FOR UPDATE
                """, Long.class, accountType.name(), userId, asset);
        long nextAvailable = Math.addExact(currentAvailable == null ? 0L : currentAvailable, amountUnits);
        if (nextAvailable < 0) {
            throw new IllegalArgumentException("insufficient available balance");
        }
        int rows = jdbcTemplate.update("""
                UPDATE account_product_balances
                   SET available_units = ?,
                       updated_at = ?
                 WHERE account_type = ?
                   AND user_id = ?
                   AND asset = ?
                """, nextAvailable, Timestamp.from(now), accountType.name(), userId, asset);
        requireSingleRow(rows, "product balance available update");
        return nextAvailable;
    }

    private long applyLegacyAvailableDelta(long userId, String asset, long amountUnits, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO account_balances (user_id, asset, available_units, locked_units, updated_at)
                VALUES (?, ?, 0, 0, ?)
                ON CONFLICT (user_id, asset) DO NOTHING
                """, userId, asset, Timestamp.from(now));
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
        requireSingleRow(rows, "legacy product balance available update");
        return nextAvailable;
    }

    private void insertProductTransferLedger(long userId,
                                             AccountType accountType,
                                             String asset,
                                             long amountUnits,
                                             long balanceAfterUnits,
                                             String referenceId,
                                             String reason,
                                             Instant now) {
        int rows = jdbcTemplate.update("""
                INSERT INTO account_product_ledger_entries (
                    entry_id, user_id, account_type, asset, amount_units, balance_after_units,
                    reference_type, reference_id, reason, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'PRODUCT_TRANSFER', ?, ?, ?)
                ON CONFLICT (reference_type, reference_id, user_id, account_type, asset) DO NOTHING
                """, sequenceRepository.nextSequence("product-ledger-entry"), userId, accountType.name(), asset,
                amountUnits, balanceAfterUnits, referenceId, reason, Timestamp.from(now));
        requireSingleRow(rows, "product transfer ledger insert");
    }

    private ProductBalanceResponse toProductBalance(AccountType accountType, BalanceResponse balance) {
        return new ProductBalanceResponse(balance.userId(), accountType, balance.asset(), balance.availableUnits(),
                balance.lockedUnits(), balance.equityUnits(), balance.updatedAt());
    }

    private AccountType requireAccountType(AccountType accountType) {
        if (accountType == null) {
            throw new IllegalArgumentException("accountType is required");
        }
        return accountType;
    }

    private AccountType accountTypeFromNullableDbValue(String accountType) {
        if (accountType == null || accountType.isBlank()) {
            return AccountType.USDT_PERPETUAL;
        }
        return AccountType.valueOf(accountType);
    }

    private AccountType nullableAccountType(String accountType) {
        return accountType == null || accountType.isBlank() ? null : AccountType.valueOf(accountType);
    }

    private String requireAdjustmentKind(String adjustmentKind) {
        if (!"BASIC".equals(adjustmentKind) && !"PRODUCT".equals(adjustmentKind)) {
            throw new IllegalArgumentException("adjustmentKind must be BASIC or PRODUCT");
        }
        return adjustmentKind;
    }

    private String adminAdjustmentReferenceKey(String adjustmentKind,
                                               long userId,
                                               AccountType accountType,
                                               String asset,
                                               String referenceId) {
        String accountSegment = accountType == null ? "" : accountType.name();
        return adjustmentKind + "|" + userId + "|" + accountSegment + "|" + asset + "|" + referenceId;
    }

    private boolean isLegacyPerpetualAccount(AccountType accountType) {
        return accountType == AccountType.USDT_PERPETUAL;
    }

    private PositionResponse toPositionResponse(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new PositionResponse(
                rs.getLong("user_id"),
                rs.getString("symbol"),
                longOrZero(rs, "instrument_version"),
                MarginMode.fromNullableDbValue(rs.getString("margin_mode")),
                PositionSide.fromNullableDbValue(rs.getString("position_side")),
                rs.getLong("signed_quantity_steps"),
                rs.getLong("entry_price_ticks"),
                rs.getLong("realized_pnl_units"),
                rs.getTimestamp("updated_at").toInstant());
    }

    private record OrderMarginReservation(
            AccountType accountType,
            long userId,
            String asset,
            long reservedUnits,
            long releasedUnits,
            long positionMarginUnits,
            MarginMode marginMode,
            PositionSide positionSide,
            long orderQuantitySteps,
            boolean reduceOnly) {
    }

    private record PositionMargin(String symbol,
                                  String asset,
                                  MarginMode marginMode,
                                  PositionSide positionSide,
                                  long marginUnits,
                                  AccountType accountType) {
        private PositionMargin(String symbol, String asset, MarginMode marginMode, long marginUnits) {
            this(symbol, asset, marginMode, PositionSide.NET, marginUnits, AccountType.USDT_PERPETUAL);
        }
    }

    private record SpotReservation(
            long userId,
            OrderSide side,
            String asset,
            long reservedUnits,
            long settledUnits,
            long releasedUnits) {
    }

    private record AdjustmentReference(long amountUnits, String reason) {
    }

    private record ProductTransferRecord(
            long transferId,
            AccountType sourceAccountType,
            AccountType targetAccountType,
            String asset,
            long amountUnits,
            String status,
            String reason,
            Instant createdAt) {
    }

    private record PositionCollateralTarget(
            long userId,
            String symbol,
            String asset,
            PositionSide positionSide,
            long instrumentVersion,
            long signedQuantitySteps) {
    }

    private record RiskRemovalSnapshot(
            long instrumentVersion,
            long signedQuantitySteps,
            long unrealizedPnlUnits,
            long maintenanceMarginUnits,
            String status,
            Instant eventTime) {
    }

    private record PositionMarginAdjustmentReference(
            String asset,
            long amountUnits,
            String reason,
            String symbol) {
    }

    private Long nullableVersion(long version) {
        return version <= 0 ? null : version;
    }

    private long longOrZero(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? 0L : value;
    }

    private Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private ProductLine productLine(ProductLine productLine) {
        return productLine == null ? ProductLine.LINEAR_PERPETUAL : productLine;
    }

    private AccountType accountType(ProductLine productLine) {
        return AccountType.valueOf(productLine(productLine).accountTypeCode());
    }

    private static String productLineExpression(String instrumentAlias) {
        return ProductLineSql.contractTypeProductLineCase(instrumentAlias + ".contract_type");
    }

    private static String accountTypeExpression(String instrumentAlias) {
        return ProductLineSql.contractTypeAccountTypeCase(instrumentAlias + ".contract_type");
    }

    private void requireSingleRow(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException("failed to write " + operation);
        }
    }

}
