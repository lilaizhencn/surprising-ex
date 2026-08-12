package com.surprising.insurance.provider.repository;

import com.surprising.insurance.api.model.AdminCursorPage;
import com.surprising.insurance.api.model.InsuranceCoverageResponse;
import com.surprising.insurance.provider.model.InsuranceDeficitRow;
import com.surprising.insurance.provider.model.InsurancePendingCoverage;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 仅负责 insurance_deficit_coverages 表。
 */
@Repository
public class InsuranceCoverageRepository {

    private final JdbcTemplate jdbcTemplate;

    public InsuranceCoverageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(long coverageId,
                       InsuranceDeficitRow deficit,
                       long coveredUnits,
                       long remainingDeficit,
                       String reserveCommandId,
                       String finalizeCommandId,
                       Instant now) {
        requireSingle(jdbcTemplate.update("""
                INSERT INTO insurance_deficit_coverages (
                    coverage_id, account_type, user_id, asset, requested_units, covered_units,
                    remaining_deficit_units, reserve_command_id, finalize_command_id,
                    status, reason, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING_RESERVE',
                          'DEFICIT_COVERAGE', ?, ?)
                """, coverageId, deficit.accountType(), deficit.userId(), deficit.asset(), deficit.deficitUnits(),
                coveredUnits, remainingDeficit, reserveCommandId, finalizeCommandId,
                Timestamp.from(now), Timestamp.from(now)), "insurance deficit coverage insert");
    }

    public void markPendingFinalize(long coverageId, Instant now) {
        int rows = jdbcTemplate.update("""
                UPDATE insurance_deficit_coverages
                   SET status = 'PENDING_FINALIZE', updated_at = ?
                 WHERE coverage_id = ? AND status = 'PENDING_RESERVE'
                """, Timestamp.from(now), coverageId);
        if (rows != 0 && rows != 1) {
            throw new IllegalStateException("failed to update insurance coverage progress");
        }
    }

    public void markFailed(InsurancePendingCoverage coverage, Instant now) {
        requireSingle(jdbcTemplate.update("""
                UPDATE insurance_deficit_coverages
                   SET status = 'FAILED', error_code = ?, error_message = ?,
                       completed_at = ?, updated_at = ?
                 WHERE coverage_id = ? AND status IN ('PENDING_RESERVE', 'PENDING_FINALIZE')
                """, coverage.errorCode(), truncate(coverage.errorMessage()), Timestamp.from(now),
                Timestamp.from(now), coverage.coverageId()), "insurance coverage failed transition");
    }

    public void markCompleted(long coverageId, long remainingDeficitUnits, Instant now) {
        requireSingle(jdbcTemplate.update("""
                UPDATE insurance_deficit_coverages
                   SET remaining_deficit_units = ?,
                       status = CASE WHEN ? = 0 THEN 'COVERED' ELSE 'PARTIALLY_COVERED' END,
                       completed_at = ?, updated_at = ?
                 WHERE coverage_id = ? AND status IN ('PENDING_RESERVE', 'PENDING_FINALIZE')
                """, remainingDeficitUnits, remainingDeficitUnits, Timestamp.from(now),
                Timestamp.from(now), coverageId), "insurance coverage completion");
    }

    public AdminCursorPage.CursorPage<InsuranceCoverageResponse> page(String accountType,
                                                                      Long userId,
                                                                      String asset,
                                                                      int limit,
                                                                      String cursor,
                                                                      String sort) {
        String normalizedAsset = asset == null || asset.isBlank() ? null : asset;
        int safeLimit = AdminCursorPage.limit(limit, 1000);
        AdminCursorPage.SortSpec desc =
                new AdminCursorPage.SortSpec("createdAt", "created_at", "coverage_id", true);
        AdminCursorPage.SortSpec asc =
                new AdminCursorPage.SortSpec("createdAt", "created_at", "coverage_id", false);
        AdminCursorPage.SortSpec sortSpec = AdminCursorPage.parseSort(sort, desc, List.of(desc, asc));
        AdminCursorPage.Cursor decodedCursor = AdminCursorPage.decodeCursor(cursor);
        List<Object> args = new ArrayList<>();
        args.add(accountType);
        args.add(userId);
        args.add(userId);
        args.add(normalizedAsset);
        args.add(normalizedAsset);
        AdminCursorPage.addCursorArgs(args, decodedCursor);
        args.add(safeLimit + 1);
        List<InsuranceCoverageResponse> rows = jdbcTemplate.query("""
                SELECT *
                  FROM insurance_deficit_coverages
                 WHERE account_type = ?
                   AND (CAST(? AS text) IS NULL OR user_id = ?)
                   AND (CAST(? AS text) IS NULL OR asset = ?)
                %s
                 ORDER BY %s %s, %s %s
                 LIMIT ?
                """.formatted(AdminCursorPage.seekCondition(sortSpec, decodedCursor),
                        sortSpec.column(), sortSpec.directionSql(), sortSpec.idColumn(), sortSpec.directionSql()),
                (rs, rowNum) -> new InsuranceCoverageResponse(
                        rs.getLong("coverage_id"),
                        rs.getLong("user_id"),
                        rs.getString("asset"),
                        rs.getLong("requested_units"),
                        rs.getLong("covered_units"),
                        rs.getLong("remaining_deficit_units"),
                        rs.getString("status"),
                        rs.getString("reason"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()), args.toArray());
        return AdminCursorPage.page(rows, safeLimit, sortSpec, InsuranceCoverageResponse::createdAt,
                InsuranceCoverageResponse::coverageId);
    }

    private void requireSingle(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException("failed to write " + operation);
        }
    }

    private String truncate(String value) {
        if (value == null || value.length() <= 1000) {
            return value;
        }
        return value.substring(0, 1000);
    }
}
