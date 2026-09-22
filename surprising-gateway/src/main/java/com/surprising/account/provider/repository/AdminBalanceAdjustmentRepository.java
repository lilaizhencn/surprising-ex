package com.surprising.account.provider.repository;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.AdminBalanceAdjustmentRecord;
import com.surprising.account.api.model.AdminCursorPage;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AdminBalanceAdjustmentRepository {

    private final JdbcTemplate jdbcTemplate;

    public AdminBalanceAdjustmentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public AdminBalanceAdjustmentRecord record(String adjustmentKind,
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
        String referenceKey = referenceKey(normalizedKind, userId, accountType, asset, referenceId);
        return jdbcTemplate.queryForObject("""
                INSERT INTO account_admin_balance_adjustments (
                    reference_key, adjustment_kind, admin_user_id, admin_username, user_id, account_type,
                    asset, amount_units, balance_after_units, reference_id, reason, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (reference_key) DO UPDATE
                   SET reference_key = EXCLUDED.reference_key
                RETURNING adjustment_id, adjustment_kind, admin_user_id, admin_username, user_id, account_type,
                          asset, amount_units, balance_after_units, reference_id, reason, created_at
                """, (rs, rowNum) -> toRecord(rs),
                referenceKey, normalizedKind, adminUserId, emptyToNull(adminUsername), userId,
                accountType == null ? null : accountType.name(), asset, amountUnits, balanceAfterUnits,
                referenceId, reason, Timestamp.from(Instant.now()));
    }

    public List<AdminBalanceAdjustmentRecord> entries(Long adminUserId,
                                                       Long userId,
                                                       String adjustmentKind,
                                                       AccountType accountType,
                                                       String asset,
                                                       String referenceId,
                                                       int limit) {
        return page(adminUserId, userId, adjustmentKind, accountType, asset, referenceId,
                limit, null, null).items();
    }

    public AdminCursorPage.CursorPage<AdminBalanceAdjustmentRecord> page(Long adminUserId,
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
        AdminCursorPage.SortSpec sortSpec = createdAtSort(sort, "adjustment_id");
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
                (rs, rowNum) -> toRecord(rs), args.toArray());
        return AdminCursorPage.page(rows, safeLimit, sortSpec, AdminBalanceAdjustmentRecord::createdAt,
                AdminBalanceAdjustmentRecord::adjustmentId);
    }

    private static AdminBalanceAdjustmentRecord toRecord(java.sql.ResultSet resultSet)
            throws java.sql.SQLException {
        return new AdminBalanceAdjustmentRecord(
                resultSet.getLong("adjustment_id"),
                resultSet.getString("adjustment_kind"),
                resultSet.getLong("admin_user_id"),
                resultSet.getString("admin_username"),
                resultSet.getLong("user_id"),
                nullableAccountType(resultSet.getString("account_type")),
                resultSet.getString("asset"),
                resultSet.getLong("amount_units"),
                resultSet.getLong("balance_after_units"),
                resultSet.getString("reference_id"),
                resultSet.getString("reason"),
                resultSet.getTimestamp("created_at").toInstant());
    }

    private static AdminCursorPage.SortSpec createdAtSort(String sort, String idColumn) {
        AdminCursorPage.SortSpec createdAtDesc = new AdminCursorPage.SortSpec(
                "createdAt", "created_at", idColumn, true);
        AdminCursorPage.SortSpec createdAtAsc = new AdminCursorPage.SortSpec(
                "createdAt", "created_at", idColumn, false);
        return AdminCursorPage.parseSort(sort, createdAtDesc, List.of(createdAtDesc, createdAtAsc));
    }

    private static AccountType nullableAccountType(String accountType) {
        return accountType == null || accountType.isBlank() ? null : AccountType.valueOf(accountType);
    }

    private static String requireAdjustmentKind(String adjustmentKind) {
        if (!"BASIC".equals(adjustmentKind) && !"PRODUCT".equals(adjustmentKind)) {
            throw new IllegalArgumentException("adjustmentKind must be BASIC or PRODUCT");
        }
        return adjustmentKind;
    }

    private static String referenceKey(String adjustmentKind,
                                       long userId,
                                       AccountType accountType,
                                       String asset,
                                       String referenceId) {
        String accountSegment = accountType == null ? "" : accountType.name();
        return adjustmentKind + "|" + userId + "|" + accountSegment + "|" + asset + "|" + referenceId;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
