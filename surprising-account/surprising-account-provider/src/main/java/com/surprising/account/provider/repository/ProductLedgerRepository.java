package com.surprising.account.provider.repository;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.AdminCursorPage;
import com.surprising.account.api.model.ProductLedgerEntryResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ProductLedgerRepository {

    private final JdbcTemplate jdbcTemplate;

    public ProductLedgerRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ProductLedgerEntryResponse> entries(Long userId,
                                                    AccountType accountType,
                                                    String asset,
                                                    String referenceType,
                                                    int limit) {
        return page(userId, accountType, asset, referenceType, limit, null, null).items();
    }

    public AdminCursorPage.CursorPage<ProductLedgerEntryResponse> page(Long userId,
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
        AdminCursorPage.SortSpec sortSpec = createdAtSort(sort, "entry_id");
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

    public int insertBalanceAdjustment(long entryId,
                                       long userId,
                                       AccountType accountType,
                                       String asset,
                                       long amountUnits,
                                       String referenceId,
                                       String reason,
                                       Instant now) {
        return jdbcTemplate.update("""
                INSERT INTO account_product_ledger_entries (
                    entry_id, user_id, account_type, asset, amount_units, balance_after_units,
                    reference_type, reference_id, reason, created_at
                ) VALUES (?, ?, ?, ?, ?, 0, 'PRODUCT_BALANCE_ADJUSTMENT', ?, ?, ?)
                ON CONFLICT (reference_type, reference_id, user_id, account_type, asset) DO NOTHING
                """, entryId, userId, accountType.name(), asset, amountUnits, referenceId, reason,
                Timestamp.from(now));
    }

    public Optional<AdjustmentReference> findBalanceAdjustment(long userId,
                                                                AccountType accountType,
                                                                String asset,
                                                                String referenceId) {
        return jdbcTemplate.query("""
                SELECT amount_units, reason
                  FROM account_product_ledger_entries
                 WHERE reference_type = 'PRODUCT_BALANCE_ADJUSTMENT'
                   AND reference_id = ?
                   AND user_id = ?
                   AND account_type = ?
                   AND asset = ?
                """, (rs, rowNum) -> new AdjustmentReference(
                        rs.getLong("amount_units"),
                        rs.getString("reason")), referenceId, userId, accountType.name(), asset)
                .stream().findFirst();
    }

    public int updateBalanceAdjustmentBalance(long userId,
                                              AccountType accountType,
                                              String asset,
                                              String referenceId,
                                              long balanceAfterUnits) {
        return jdbcTemplate.update("""
                UPDATE account_product_ledger_entries
                   SET balance_after_units = ?
                 WHERE reference_type = 'PRODUCT_BALANCE_ADJUSTMENT'
                   AND reference_id = ?
                   AND user_id = ?
                   AND account_type = ?
                   AND asset = ?
                """, balanceAfterUnits, referenceId, userId, accountType.name(), asset);
    }

    public int insertTransfer(long entryId,
                              long userId,
                              AccountType accountType,
                              String asset,
                              long amountUnits,
                              long balanceAfterUnits,
                              String referenceId,
                              String reason,
                              Instant now) {
        return jdbcTemplate.update("""
                INSERT INTO account_product_ledger_entries (
                    entry_id, user_id, account_type, asset, amount_units, balance_after_units,
                    reference_type, reference_id, reason, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'PRODUCT_TRANSFER', ?, ?, ?)
                ON CONFLICT (reference_type, reference_id, user_id, account_type, asset) DO NOTHING
                """, entryId, userId, accountType.name(), asset, amountUnits, balanceAfterUnits,
                referenceId, reason, Timestamp.from(now));
    }

    public int insertDeficitSettlement(long entryId,
                                       AccountType accountType,
                                       long userId,
                                       String asset,
                                       long amountUnits,
                                       long balanceAfterUnits,
                                       String referenceType,
                                       String referenceId,
                                       String reason,
                                       Instant now) {
        return jdbcTemplate.update("""
                INSERT INTO account_product_ledger_entries (
                    entry_id, account_type, user_id, asset, amount_units, balance_after_units,
                    reference_type, reference_id, reason, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (reference_type, reference_id, user_id, account_type, asset) DO NOTHING
                """, entryId, accountType.name(), userId, asset, amountUnits, balanceAfterUnits,
                referenceType, referenceId, reason, Timestamp.from(now));
    }

    public int insertFunding(long entryId,
                             AccountType accountType,
                             long userId,
                             String asset,
                             long amountUnits,
                             String referenceId,
                             String reason,
                             Instant now) {
        return jdbcTemplate.update("""
                INSERT INTO account_product_ledger_entries (
                    entry_id, user_id, account_type, asset, amount_units, balance_after_units,
                    reference_type, reference_id, reason, created_at
                ) VALUES (?, ?, ?, ?, ?, 0, 'FUNDING', ?, ?, ?)
                ON CONFLICT (reference_type, reference_id, user_id, account_type, asset) DO NOTHING
                """, entryId, userId, accountType.name(), asset, amountUnits, referenceId, reason,
                Timestamp.from(now));
    }

    public int updateFundingBalance(long userId,
                                    AccountType accountType,
                                    String asset,
                                    String referenceId,
                                    long balanceAfterUnits) {
        return jdbcTemplate.update("""
                UPDATE account_product_ledger_entries
                   SET balance_after_units = ?
                 WHERE reference_type = 'FUNDING'
                   AND reference_id = ?
                   AND user_id = ?
                   AND account_type = ?
                   AND asset = ?
                """, balanceAfterUnits, referenceId, userId, accountType.name(), asset);
    }

    public int insertAdl(long entryId,
                         AccountType accountType,
                         long userId,
                         String asset,
                         long amountUnits,
                         long balanceAfterUnits,
                         String referenceType,
                         String referenceId,
                         String reason,
                         Instant now) {
        return jdbcTemplate.update("""
                INSERT INTO account_product_ledger_entries (
                    entry_id, account_type, user_id, asset, amount_units, balance_after_units,
                    reference_type, reference_id, reason, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (reference_type, reference_id, user_id, account_type, asset) DO NOTHING
                """, entryId, accountType.name(), userId, asset, amountUnits, balanceAfterUnits,
                referenceType, referenceId, reason, Timestamp.from(now));
    }

    public int insertSettlement(long entryId,
                                long userId,
                                AccountType accountType,
                                String asset,
                                long amountUnits,
                                long balanceAfterUnits,
                                String referenceType,
                                String referenceId,
                                String reason,
                                String symbol,
                                Instant now) {
        return jdbcTemplate.update("""
                INSERT INTO account_product_ledger_entries (
                    entry_id, user_id, account_type, asset, amount_units, balance_after_units,
                    reference_type, reference_id, reason, symbol, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (reference_type, reference_id, user_id, account_type, asset) DO NOTHING
                """, entryId, userId, accountType.name(), asset, amountUnits, balanceAfterUnits,
                referenceType, referenceId, reason, symbol, Timestamp.from(now));
    }

    public int updateSettlementBalance(long userId,
                                       AccountType accountType,
                                       String asset,
                                       String referenceType,
                                       String referenceId,
                                       long balanceAfterUnits) {
        return jdbcTemplate.update("""
                UPDATE account_product_ledger_entries
                   SET balance_after_units = ?
                 WHERE reference_type = ?
                   AND reference_id = ?
                   AND user_id = ?
                   AND account_type = ?
                   AND asset = ?
                """, balanceAfterUnits, referenceType, referenceId,
                userId, accountType.name(), asset);
    }

    public Optional<PositionMarginAdjustmentRow> findPositionMarginAdjustmentBySymbol(
            long userId, AccountType accountType, String symbol, String referenceId) {
        return jdbcTemplate.query("""
                SELECT asset, amount_units, reason, symbol
                  FROM account_product_ledger_entries
                 WHERE reference_type = 'POSITION_MARGIN_ADJUSTMENT'
                   AND reference_id = ?
                   AND user_id = ?
                   AND account_type = ?
                   AND symbol = ?
                """, (rs, rowNum) -> new PositionMarginAdjustmentRow(
                        rs.getString("asset"), rs.getLong("amount_units"),
                        rs.getString("reason"), rs.getString("symbol")),
                referenceId, userId, accountType.name(), symbol).stream().findFirst();
    }

    public Optional<PositionMarginAdjustmentRow> findPositionMarginAdjustmentByAsset(
            long userId, AccountType accountType, String asset, String referenceId) {
        return jdbcTemplate.query("""
                SELECT asset, amount_units, reason, symbol
                  FROM account_product_ledger_entries
                 WHERE reference_type = 'POSITION_MARGIN_ADJUSTMENT'
                   AND reference_id = ?
                   AND user_id = ?
                   AND account_type = ?
                   AND asset = ?
                """, (rs, rowNum) -> new PositionMarginAdjustmentRow(
                        rs.getString("asset"), rs.getLong("amount_units"),
                        rs.getString("reason"), rs.getString("symbol")),
                referenceId, userId, accountType.name(), asset).stream().findFirst();
    }

    public boolean exists(long userId,
                          AccountType accountType,
                          String asset,
                          String referenceType,
                          String referenceId) {
        return !jdbcTemplate.query("""
                SELECT 1
                  FROM account_product_ledger_entries
                 WHERE reference_type = ?
                   AND reference_id = ?
                   AND user_id = ?
                   AND account_type = ?
                   AND asset = ?
                """, (rs, rowNum) -> 1, referenceType, referenceId,
                userId, accountType.name(), asset).isEmpty();
    }

    public int insertSpotTrade(long entryId,
                               long userId,
                               String asset,
                               long amountUnits,
                               long balanceAfterUnits,
                               String referenceId,
                               String reason,
                               Instant now) {
        return jdbcTemplate.update("""
                INSERT INTO account_product_ledger_entries (
                    entry_id, user_id, account_type, asset, amount_units, balance_after_units,
                    reference_type, reference_id, reason, created_at
                ) VALUES (?, ?, 'SPOT', ?, ?, ?, 'SPOT_TRADE', ?, ?, ?)
                ON CONFLICT (reference_type, reference_id, user_id, account_type, asset) DO NOTHING
                """, entryId, userId, asset, amountUnits, balanceAfterUnits, referenceId, reason,
                Timestamp.from(now));
    }

    private static AdminCursorPage.SortSpec createdAtSort(String sort, String idColumn) {
        AdminCursorPage.SortSpec createdAtDesc = new AdminCursorPage.SortSpec(
                "createdAt", "created_at", idColumn, true);
        AdminCursorPage.SortSpec createdAtAsc = new AdminCursorPage.SortSpec(
                "createdAt", "created_at", idColumn, false);
        return AdminCursorPage.parseSort(sort, createdAtDesc, List.of(createdAtDesc, createdAtAsc));
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record AdjustmentReference(long amountUnits, String reason) {
    }

    public record PositionMarginAdjustmentRow(
            String asset,
            long amountUnits,
            String reason,
            String symbol) {
    }
}
