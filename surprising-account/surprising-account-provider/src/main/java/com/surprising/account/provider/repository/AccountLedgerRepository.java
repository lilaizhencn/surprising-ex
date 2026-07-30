package com.surprising.account.provider.repository;

import com.surprising.account.api.model.AccountLedgerEntryResponse;
import com.surprising.account.api.model.AdminCursorPage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AccountLedgerRepository {

    private final JdbcTemplate jdbcTemplate;

    public AccountLedgerRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<AccountLedgerEntryResponse> entries(Long userId,
                                                    String asset,
                                                    String referenceType,
                                                    int limit) {
        return page(userId, asset, referenceType, limit, null, null).items();
    }

    public AdminCursorPage.CursorPage<AccountLedgerEntryResponse> page(Long userId,
                                                                       String asset,
                                                                       String referenceType,
                                                                       int limit,
                                                                       String cursor,
                                                                       String sort) {
        String normalizedAsset = emptyToNull(asset);
        String normalizedReferenceType = emptyToNull(referenceType);
        int safeLimit = AdminCursorPage.limit(limit, 1000);
        AdminCursorPage.SortSpec sortSpec = createdAtSort(sort, "entry_id");
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

    public int insertBalanceAdjustment(long entryId,
                                       long userId,
                                       String asset,
                                       long amountUnits,
                                       String referenceId,
                                       String reason,
                                       Instant now) {
        return jdbcTemplate.update("""
                INSERT INTO account_ledger_entries (
                    entry_id, user_id, asset, amount_units, balance_after_units,
                    reference_type, reference_id, reason, created_at
                ) VALUES (?, ?, ?, ?, 0, 'BALANCE_ADJUSTMENT', ?, ?, ?)
                ON CONFLICT (reference_type, reference_id, user_id, asset) DO NOTHING
                """, entryId, userId, asset, amountUnits, referenceId, reason, Timestamp.from(now));
    }

    public Optional<AdjustmentReference> findBalanceAdjustment(long userId,
                                                                String asset,
                                                                String referenceId) {
        return jdbcTemplate.query("""
                SELECT amount_units, reason
                  FROM account_ledger_entries
                 WHERE reference_type = 'BALANCE_ADJUSTMENT'
                   AND reference_id = ?
                   AND user_id = ?
                   AND asset = ?
                """, (rs, rowNum) -> new AdjustmentReference(
                        rs.getLong("amount_units"),
                        rs.getString("reason")), referenceId, userId, asset)
                .stream().findFirst();
    }

    public int updateBalanceAdjustmentBalance(long userId,
                                              String asset,
                                              String referenceId,
                                              long balanceAfterUnits) {
        return jdbcTemplate.update("""
                UPDATE account_ledger_entries
                   SET balance_after_units = ?
                 WHERE reference_type = 'BALANCE_ADJUSTMENT'
                   AND reference_id = ?
                   AND user_id = ?
                   AND asset = ?
                """, balanceAfterUnits, referenceId, userId, asset);
    }

    private static AdminCursorPage.SortSpec createdAtSort(String sort, String idColumn) {
        AdminCursorPage.SortSpec createdAtDesc = new AdminCursorPage.SortSpec(
                "createdAt", "created_at", idColumn, true);
        AdminCursorPage.SortSpec createdAtAsc = new AdminCursorPage.SortSpec(
                "createdAt", "created_at", idColumn, false);
        return AdminCursorPage.parseSort(sort, createdAtDesc, List.of(createdAtDesc, createdAtAsc));
    }

    private static Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record AdjustmentReference(long amountUnits, String reason) {
    }
}
