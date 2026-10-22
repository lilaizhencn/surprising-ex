package com.surprising.insurance.provider.repository;

import com.surprising.insurance.api.utils.AdminCursorPage;
import com.surprising.insurance.provider.model.InsuranceHistoryPageQuery;
import com.surprising.insurance.api.model.InsuranceCoverageResponse;
import com.surprising.insurance.provider.model.CoreLiquidationProjection;
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

    public void insertCompleted(long coverageId,
                                String accountType,
                                CoreLiquidationProjection deficit,
                                long coveredUnits,
                                long remainingDeficit,
                                Instant now) {
        String status = remainingDeficit == 0 ? "COVERED" : "PARTIALLY_COVERED";
        int inserted = jdbcTemplate.update("""
                INSERT INTO insurance_deficit_coverages (
                    coverage_id, account_type, user_id, asset, requested_units, covered_units,
                    remaining_deficit_units, reserve_command_id, finalize_command_id,
                    status, reason, completed_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'AERON_LIQUIDATION_COVERAGE', ?, ?, ?)
                ON CONFLICT (finalize_command_id) DO NOTHING
                """, coverageId, accountType, deficit.userId(), deficit.asset(), deficit.deficitUnits(),
                coveredUnits, remainingDeficit, "AERON:" + deficit.liquidationId(),
                "AERON:" + deficit.liquidationId(), status, Timestamp.from(now), Timestamp.from(now),
                Timestamp.from(now));
        if (inserted != 0 && inserted != 1) {
            throw new IllegalStateException("failed to write Aeron insurance coverage audit");
        }
    }


    public AdminCursorPage.CursorPage<InsuranceCoverageResponse> page(String accountType,
                                                                      Long userId,
                                                                      String asset,
                                                                      InsuranceHistoryPageQuery query) {
        int safeLimit = query.limit();
        AdminCursorPage.SortSpec sortSpec =
                new AdminCursorPage.SortSpec("createdAt", "created_at", "coverage_id", query.descending());
        AdminCursorPage.Cursor decodedCursor = query.afterTime() == null ? null
                : new AdminCursorPage.Cursor(query.afterTime(), query.afterId());
        List<Object> args = new ArrayList<>();
        args.add(accountType);
        args.add(userId);
        args.add(userId);
        args.add(asset);
        args.add(asset);
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

}
