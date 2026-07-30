package com.surprising.account.provider.repository;

import com.surprising.account.api.model.AccountLedgerEntryResponse;
import com.surprising.account.api.model.AdminCursorPage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
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
}
