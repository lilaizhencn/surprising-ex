package com.surprising.account.provider.repository;

import com.surprising.account.api.model.AccountType;
import com.surprising.trading.api.utils.AdminCursorPage;
import com.surprising.account.provider.model.AccountHistoryPageQuery;
import com.surprising.account.api.model.ProductTransferRecordResponse;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ProductTransferRepository {

    private final JdbcTemplate jdbcTemplate;

    public ProductTransferRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public AdminCursorPage.CursorPage<ProductTransferRecordResponse> page(Long userId,
                                                                          AccountType accountType,
                                                                          String asset,
                                                                          AccountHistoryPageQuery pageQuery) {
        String normalizedAccountType = accountType == null ? null : accountType.name();
        int safeLimit = pageQuery.limit();
        AdminCursorPage.SortSpec sortSpec = new AdminCursorPage.SortSpec(
                "createdAt", "created_at", "transfer_id", pageQuery.descending());
        AdminCursorPage.Cursor decodedCursor = pageQuery.after();
        List<Object> args = new ArrayList<>();
        args.add(userId);
        args.add(userId);
        args.add(normalizedAccountType);
        args.add(normalizedAccountType);
        args.add(normalizedAccountType);
        args.add(asset);
        args.add(asset);
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

}
