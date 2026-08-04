package com.surprising.account.provider.repository;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.AdminCursorPage;
import com.surprising.account.api.model.ProductLedgerEntryResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 产品账本单表仓储。
 *
 * <p>在线账户 reducer 不依赖本仓储。写入入口只有异步账本投影，查询入口只读取本表，
 * 这样不会把旧的逐类写入方法重新变成资金热路径。</p>
 */
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

    /**
     * 将用户分区终态中的不可变余额变更投影到账本。
     *
     * <p>该方法只用于异步审计投影，不得被账户 reducer 或任何在线下单路径调用。相同引用
     * 的重复投影必须内容一致；发现冲突时停住投影，不能静默覆盖原始账本。</p>
     */
    public void projectCommandDelta(long entryId,
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
        if (entryId <= 0L || userId <= 0L || accountType == null || asset == null || asset.isBlank()
                || amountUnits == 0L || referenceType == null
                || referenceType.isBlank() || referenceId == null || referenceId.isBlank()) {
            throw new IllegalArgumentException("产品账本异步投影参数无效");
        }
        String normalizedAsset = asset.trim().toUpperCase(java.util.Locale.ROOT);
        String normalizedReferenceType = referenceType.trim().toUpperCase(java.util.Locale.ROOT);
        String normalizedReferenceId = referenceId.trim();
        String normalizedReason = reason == null || reason.isBlank() ? normalizedReferenceType : reason.trim();
        String normalizedSymbol = symbol == null || symbol.isBlank()
                ? null : symbol.trim().toUpperCase(java.util.Locale.ROOT);
        Instant createdAt = now == null ? Instant.now() : now;
        int inserted = jdbcTemplate.update("""
                INSERT INTO account_product_ledger_entries (
                    entry_id, user_id, account_type, asset, amount_units, balance_after_units,
                    reference_type, reference_id, reason, symbol, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (reference_type, reference_id, user_id, account_type, asset) DO NOTHING
                """, entryId, userId, accountType.name(), normalizedAsset, amountUnits,
                balanceAfterUnits, normalizedReferenceType, normalizedReferenceId, normalizedReason,
                normalizedSymbol, Timestamp.from(createdAt));
        if (inserted == 1) {
            return;
        }
        ExistingProjection existing = jdbcTemplate.query("""
                SELECT amount_units, balance_after_units, reason, symbol
                  FROM account_product_ledger_entries
                 WHERE reference_type = ?
                   AND reference_id = ?
                   AND user_id = ?
                   AND account_type = ?
                   AND asset = ?
                """, (rs, rowNum) -> new ExistingProjection(rs.getLong("amount_units"),
                        rs.getLong("balance_after_units"), rs.getString("reason"), rs.getString("symbol")),
                normalizedReferenceType, normalizedReferenceId, userId, accountType.name(), normalizedAsset)
                .stream().findFirst().orElseThrow(
                        () -> new IllegalStateException("产品账本幂等记录不存在: " + normalizedReferenceId));
        if (existing.amountUnits() != amountUnits || existing.balanceAfterUnits() != balanceAfterUnits
                || !java.util.Objects.equals(existing.reason(), normalizedReason)
                || !java.util.Objects.equals(existing.symbol(), normalizedSymbol)) {
            throw new IllegalStateException("产品账本异步投影发生幂等冲突: " + normalizedReferenceId);
        }
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

    private record ExistingProjection(long amountUnits,
                                      long balanceAfterUnits,
                                      String reason,
                                      String symbol) {
    }
}
