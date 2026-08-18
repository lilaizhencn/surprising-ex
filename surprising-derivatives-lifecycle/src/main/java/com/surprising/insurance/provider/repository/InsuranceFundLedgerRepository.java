package com.surprising.insurance.provider.repository;

import com.surprising.insurance.api.model.AdminCursorPage;
import com.surprising.insurance.api.model.InsuranceFundLedgerResponse;
import com.surprising.insurance.provider.model.InsuranceLedgerReference;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 仅负责 insurance_fund_ledger 表。
 */
@Repository
public class InsuranceFundLedgerRepository {

    private final JdbcTemplate jdbcTemplate;

    public InsuranceFundLedgerRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean insert(long entryId,
                          String accountType,
                          String asset,
                          long amountUnits,
                          long balanceAfterUnits,
                          String referenceType,
                          String referenceId,
                          String reason,
                          Instant now) {
        return jdbcTemplate.update("""
                INSERT INTO insurance_fund_ledger (
                    entry_id, account_type, asset, amount_units, balance_after_units,
                    reference_type, reference_id, reason, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (reference_type, reference_id, account_type, asset) DO NOTHING
                """, entryId, accountType, asset, amountUnits, balanceAfterUnits,
                referenceType, referenceId, reason, Timestamp.from(now)) == 1;
    }

    public Optional<InsuranceLedgerReference> findReference(String referenceType,
                                                            String referenceId,
                                                            String accountType,
                                                            String asset) {
        return jdbcTemplate.query("""
                SELECT amount_units, reason
                  FROM insurance_fund_ledger
                 WHERE reference_type = ?
                   AND reference_id = ?
                   AND account_type = ?
                   AND asset = ?
                """, (rs, rowNum) -> new InsuranceLedgerReference(
                rs.getLong("amount_units"), rs.getString("reason")),
                referenceType, referenceId, accountType, asset).stream().findFirst();
    }

    public AdminCursorPage.CursorPage<InsuranceFundLedgerResponse> page(String accountType,
                                                                        String asset,
                                                                        int limit,
                                                                        String cursor,
                                                                        String sort) {
        String normalizedAsset = asset == null || asset.isBlank() ? null : asset;
        int safeLimit = AdminCursorPage.limit(limit, 1000);
        AdminCursorPage.SortSpec desc =
                new AdminCursorPage.SortSpec("createdAt", "created_at", "entry_id", true);
        AdminCursorPage.SortSpec asc =
                new AdminCursorPage.SortSpec("createdAt", "created_at", "entry_id", false);
        AdminCursorPage.SortSpec sortSpec = AdminCursorPage.parseSort(sort, desc, List.of(desc, asc));
        AdminCursorPage.Cursor decodedCursor = AdminCursorPage.decodeCursor(cursor);
        List<Object> args = new ArrayList<>();
        args.add(accountType);
        args.add(normalizedAsset);
        args.add(normalizedAsset);
        AdminCursorPage.addCursorArgs(args, decodedCursor);
        args.add(safeLimit + 1);
        List<InsuranceFundLedgerResponse> rows = jdbcTemplate.query("""
                SELECT *
                  FROM insurance_fund_ledger
                 WHERE account_type = ?
                   AND (CAST(? AS text) IS NULL OR asset = ?)
                %s
                 ORDER BY %s %s, %s %s
                 LIMIT ?
                """.formatted(AdminCursorPage.seekCondition(sortSpec, decodedCursor),
                        sortSpec.column(), sortSpec.directionSql(), sortSpec.idColumn(), sortSpec.directionSql()),
                (rs, rowNum) -> new InsuranceFundLedgerResponse(
                        rs.getLong("entry_id"),
                        rs.getString("asset"),
                        rs.getLong("amount_units"),
                        rs.getLong("balance_after_units"),
                        rs.getString("reference_type"),
                        rs.getString("reference_id"),
                        rs.getString("reason"),
                        rs.getTimestamp("created_at").toInstant()), args.toArray());
        return AdminCursorPage.page(rows, safeLimit, sortSpec, InsuranceFundLedgerResponse::createdAt,
                InsuranceFundLedgerResponse::entryId);
    }
}
