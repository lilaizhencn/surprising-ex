package com.surprising.account.provider.repository;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.AdminCursorPage;
import com.surprising.account.api.model.ProductTransferRecordResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ProductTransferRepository {

    private final JdbcTemplate jdbcTemplate;

    public ProductTransferRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ProductTransferRecordResponse> entries(Long userId,
                                                       AccountType accountType,
                                                       String asset,
                                                       int limit) {
        return page(userId, accountType, asset, limit, null, null).items();
    }

    public AdminCursorPage.CursorPage<ProductTransferRecordResponse> page(Long userId,
                                                                          AccountType accountType,
                                                                          String asset,
                                                                          int limit,
                                                                          String cursor,
                                                                          String sort) {
        String normalizedAsset = emptyToNull(asset);
        String normalizedAccountType = accountType == null ? null : accountType.name();
        int safeLimit = AdminCursorPage.limit(limit, 1000);
        AdminCursorPage.SortSpec sortSpec = createdAtSort(sort, "transfer_id");
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

    public int insert(long transferId,
                      long userId,
                      AccountType sourceAccountType,
                      AccountType targetAccountType,
                      String asset,
                      long amountUnits,
                      String referenceId,
                      String reason,
                      Instant now) {
        return jdbcTemplate.update("""
                INSERT INTO account_product_transfers (
                    transfer_id, user_id, source_account_type, target_account_type, asset,
                    amount_units, reference_id, status, reason, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'COMPLETED', ?, ?, ?)
                ON CONFLICT (user_id, reference_id) DO NOTHING
                """, transferId, userId, sourceAccountType.name(), targetAccountType.name(), asset,
                amountUnits, referenceId, reason, Timestamp.from(now), Timestamp.from(now));
    }

    public Optional<TransferRecord> findByReference(long userId, String referenceId) {
        return jdbcTemplate.query("""
                SELECT transfer_id, source_account_type, target_account_type, asset,
                       amount_units, status, reason, created_at
                  FROM account_product_transfers
                 WHERE user_id = ?
                   AND reference_id = ?
                """, (rs, rowNum) -> new TransferRecord(
                        rs.getLong("transfer_id"),
                        AccountType.valueOf(rs.getString("source_account_type")),
                        AccountType.valueOf(rs.getString("target_account_type")),
                        rs.getString("asset"),
                        rs.getLong("amount_units"),
                        rs.getString("status"),
                        rs.getString("reason"),
                        rs.getTimestamp("created_at").toInstant()), userId, referenceId)
                .stream().findFirst();
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

    public record TransferRecord(
            long transferId,
            AccountType sourceAccountType,
            AccountType targetAccountType,
            String asset,
            long amountUnits,
            String status,
            String reason,
            Instant createdAt) {
    }
}
