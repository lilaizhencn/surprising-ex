package com.surprising.account.provider.repository;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.utils.AdminCursorPage;
import com.surprising.account.provider.model.AccountHistoryPageQuery;
import com.surprising.account.api.model.ProductLedgerEntryResponse;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 产品账本单表仓储。
 *
 * <p>仅提供历史投影查询；realtime 的已提交日志导出是现金流水的写入方，不在网关修改账本。</p>
 */
@Repository
public class ProductLedgerRepository {

    private final JdbcTemplate jdbcTemplate;

    public ProductLedgerRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public AdminCursorPage.CursorPage<ProductLedgerEntryResponse> page(Long userId,
                                                                       AccountType accountType,
                                                                       String asset,
                                                                       String referenceType,
                                                                       AccountHistoryPageQuery pageQuery) {
        String normalizedAccountType = accountType == null ? null : accountType.name();
        int safeLimit = pageQuery.limit();
        AdminCursorPage.SortSpec sortSpec = new AdminCursorPage.SortSpec(
                "createdAt", "created_at", "entry_id", pageQuery.descending());
        AdminCursorPage.Cursor decodedCursor = pageQuery.after();
        List<Object> args = new ArrayList<>();
        args.add(userId);
        args.add(userId);
        args.add(normalizedAccountType);
        args.add(normalizedAccountType);
        args.add(asset);
        args.add(asset);
        args.add(referenceType);
        args.add(referenceType);
        AdminCursorPage.addCursorArgs(args, decodedCursor);
        args.add(safeLimit + 1);
        List<ProductLedgerEntryResponse> rows = jdbcTemplate.query("""
                SELECT entry_id, user_id, account_type, asset, amount_units, balance_after_units,
                       reference_type, reference_id, reason, instrument_id, created_at
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
                        rs.getString("instrument_id"),
                        rs.getTimestamp("created_at").toInstant()), args.toArray());
        return AdminCursorPage.page(rows, safeLimit, sortSpec, ProductLedgerEntryResponse::createdAt,
                ProductLedgerEntryResponse::entryId);
    }

}
