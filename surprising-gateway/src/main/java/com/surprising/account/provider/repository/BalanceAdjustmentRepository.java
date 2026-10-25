package com.surprising.account.provider.repository;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.AdminBalanceAdjustmentRecord;
import com.surprising.trading.api.utils.AdminCursorPage;
import com.surprising.account.provider.model.AccountHistoryPageQuery;
import com.surprising.account.provider.exception.AccountCommandRejectedException;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.product.api.ProductLine;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 只访问 account_balance_adjustments；请求先持久化，原始事后余额和时间由已提交回放补齐。 */
@Repository
public class BalanceAdjustmentRepository {
    private final JdbcTemplate jdbcTemplate;

    public BalanceAdjustmentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 提交 Core 前独立提交首次来源；相同资金请求重试不覆盖管理员、原因或已完成事实。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void prepare(ProductLine product, UUID commandId, String adjustmentKind, Long adminUserId,
                        String adminUsername, long userId, String asset, long amountUnits,
                        String referenceId, String reason) {
        if (product == null || commandId == null || userId <= 0
                || adminUserId != null && adminUserId <= 0
                || !"BASIC".equals(adjustmentKind) && !"PRODUCT".equals(adjustmentKind))
            throw new IllegalArgumentException("invalid balance adjustment identity");
        List<Long> rows = jdbcTemplate.query("""
                INSERT INTO account_balance_adjustments (
                    product_line, command_id, adjustment_kind, admin_user_id, admin_username,
                    user_id, asset, amount_units, reference_id, reason
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (product_line, command_id) DO UPDATE SET command_id = EXCLUDED.command_id
                WHERE account_balance_adjustments.adjustment_kind = EXCLUDED.adjustment_kind
                  AND account_balance_adjustments.user_id = EXCLUDED.user_id
                  AND account_balance_adjustments.asset = EXCLUDED.asset
                  AND account_balance_adjustments.amount_units = EXCLUDED.amount_units
                  AND account_balance_adjustments.reference_id = EXCLUDED.reference_id
                RETURNING adjustment_id
                """, (rs, rowNum) -> rs.getLong("adjustment_id"), product.name(), commandId,
                adjustmentKind, adminUserId, emptyToNull(adminUsername), userId, asset, amountUnits,
                referenceId, adminUserId == null ? null : reason);
        if (rows.size() != 1) throw new AccountCommandRejectedException(
                CoreResultCode.IDEMPOTENCY_CONFLICT.name(), "conflicting balance adjustment reference");
    }

    public AdminCursorPage.CursorPage<AdminBalanceAdjustmentRecord> page(ProductLine product,
                                                                         Long adminUserId,
                                                                         Long userId,
                                                                         String adjustmentKind,
                                                                         AccountType accountType,
                                                                         String asset,
                                                                         String referenceId,
                                                                         AccountHistoryPageQuery pageQuery) {
        if (product == null || accountType != null && accountType.productLine().orElse(null) != product)
            throw new IllegalArgumentException("adjustment account does not belong to product");
        int safeLimit = pageQuery.limit();
        AdminCursorPage.SortSpec sortSpec = new AdminCursorPage.SortSpec(
                "createdAt", "created_at", "adjustment_id", pageQuery.descending());
        AdminCursorPage.Cursor decodedCursor = pageQuery.after();
        List<Object> args = new ArrayList<>();
        args.add(product.name());
        args.add(adminUserId); args.add(adminUserId);
        args.add(userId); args.add(userId);
        args.add(adjustmentKind); args.add(adjustmentKind);
        args.add(accountType != null);
        args.add(asset); args.add(asset);
        args.add(referenceId); args.add(referenceId);
        AdminCursorPage.addCursorArgs(args, decodedCursor);
        args.add(safeLimit + 1);
        List<AdminBalanceAdjustmentRecord> rows = jdbcTemplate.query("""
                SELECT adjustment_id, adjustment_kind, admin_user_id, admin_username, user_id,
                       asset, amount_units, balance_after_units, reference_id, reason, created_at
                  FROM account_balance_adjustments
                 WHERE product_line = ? AND admin_user_id IS NOT NULL AND created_at IS NOT NULL
                   AND (CAST(? AS text) IS NULL OR admin_user_id = ?)
                   AND (CAST(? AS text) IS NULL OR user_id = ?)
                   AND (CAST(? AS text) IS NULL OR adjustment_kind = ?)
                   AND (? = false OR adjustment_kind = 'PRODUCT')
                   AND (CAST(? AS text) IS NULL OR asset = ?)
                   AND (CAST(? AS text) IS NULL OR reference_id = ?)
                %s
                 ORDER BY %s %s, %s %s
                 LIMIT ?
                """.formatted(AdminCursorPage.seekCondition(sortSpec, decodedCursor),
                        sortSpec.column(), sortSpec.directionSql(), sortSpec.idColumn(), sortSpec.directionSql()),
                (rs, rowNum) -> toRecord(rs, product), args.toArray());
        return AdminCursorPage.page(rows, safeLimit, sortSpec, AdminBalanceAdjustmentRecord::createdAt,
                AdminBalanceAdjustmentRecord::adjustmentId);
    }

    private static AdminBalanceAdjustmentRecord toRecord(java.sql.ResultSet row, ProductLine product)
            throws java.sql.SQLException {
        String kind = row.getString("adjustment_kind");
        return new AdminBalanceAdjustmentRecord(row.getLong("adjustment_id"), kind,
                row.getLong("admin_user_id"), row.getString("admin_username"), row.getLong("user_id"),
                "PRODUCT".equals(kind) ? AccountType.valueOf(product.accountTypeCode()) : null,
                row.getString("asset"), row.getLong("amount_units"),
                row.getBigDecimal("balance_after_units").longValueExact(),
                row.getString("reference_id"), row.getString("reason"), row.getTimestamp("created_at").toInstant());
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
