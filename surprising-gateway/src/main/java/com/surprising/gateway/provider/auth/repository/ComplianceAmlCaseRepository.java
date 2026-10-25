package com.surprising.gateway.provider.auth.repository;

import com.surprising.gateway.provider.auth.utils.ComplianceValidation;

import com.surprising.trading.api.utils.AdminCursorPage;
import com.surprising.gateway.provider.auth.model.AuthPageQuery;

import com.surprising.gateway.provider.auth.model.ComplianceModels.AmlCase;
import com.surprising.gateway.provider.auth.model.ComplianceModels.AmlCaseCreateRequest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 只负责 {@code gateway_user_aml_cases} 表。
 */
@Repository
public class ComplianceAmlCaseRepository {

    private static final AdminCursorPage.SortSpec UPDATED_DESC =
            new AdminCursorPage.SortSpec("updatedAt", "updated_at", "case_id", true);
    private static final List<AdminCursorPage.SortSpec> SORTS = List.of(
            UPDATED_DESC,
            new AdminCursorPage.SortSpec("updatedAt", "updated_at", "case_id", false),
            new AdminCursorPage.SortSpec("createdAt", "created_at", "case_id", true),
            new AdminCursorPage.SortSpec("createdAt", "created_at", "case_id", false));

    private final JdbcTemplate jdbcTemplate;

    public ComplianceAmlCaseRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<AmlCase> find(Long userId, String status, int limit) {
        return findPage(userId, status, new AuthPageQuery(limit, null, AuthPageQuery.Order.UPDATED_DESC)).items();
    }

    public AdminCursorPage.CursorPage<AmlCase> findPage(Long userId,
                                                        String status,
                                                        AuthPageQuery pageQuery) {
        int safeLimit = pageQuery.limit();
        AdminCursorPage.SortSpec sortSpec = SORTS.stream()
                .filter(spec -> spec.field().equals(pageQuery.order().field()) && spec.descending() == pageQuery.order().descending())
                .findFirst().orElseThrow(() -> new IllegalStateException("unsupported native page order"));
        AdminCursorPage.Cursor decodedCursor = pageQuery.after();
        List<Object> args = new ArrayList<>();
        args.add(userId);
        args.add(userId);
        args.add(status);
        args.add(status);
        String sql = """
                SELECT case_id, user_id, status, risk_score, source, summary, assigned_admin_user_id,
                       created_by_user_id, reviewed_by_user_id, reviewed_at, closed_at, created_at, updated_at
                  FROM gateway_user_aml_cases
                 WHERE (CAST(? AS text) IS NULL OR user_id = ?)
                   AND (CAST(? AS text) IS NULL OR status = ?)
                """ + AdminCursorPage.seekCondition(sortSpec, decodedCursor) + """
                 ORDER BY %s %s, case_id %s
                 LIMIT ?
                """.formatted(sortSpec.column(), sortSpec.directionSql(), sortSpec.directionSql());
        AdminCursorPage.addCursorArgs(args, decodedCursor);
        args.add(safeLimit + 1);
        List<AmlCase> fetchedRows = jdbcTemplate.query(sql, (rs, rowNum) -> toAmlCase(rs), args.toArray());
        return AdminCursorPage.page(
                fetchedRows, safeLimit, sortSpec, timestampExtractor(sortSpec), AmlCase::caseId);
    }

    public AmlCase create(long userId, long adminUserId, AmlCaseCreateRequest request, Instant now) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO gateway_user_aml_cases (
                    user_id, status, risk_score, source, summary, assigned_admin_user_id,
                    created_by_user_id, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING case_id, user_id, status, risk_score, source, summary, assigned_admin_user_id,
                          created_by_user_id, reviewed_by_user_id, reviewed_at, closed_at, created_at, updated_at
                """, (rs, rowNum) -> toAmlCase(rs),
                userId, request.status(),
                request.riskScore(),
                request.source(),
                request.summary(),
                request.assignedAdminUserId(), adminUserId, Timestamp.from(now), Timestamp.from(now));
    }

    public java.util.Optional<AmlCase> updateStatus(long caseId,
                                long adminUserId,
                                String status, Integer riskScore, Instant closedAt, Instant now) {
        return jdbcTemplate.query("""
                UPDATE gateway_user_aml_cases
                   SET status = ?,
                       risk_score = COALESCE(?, risk_score),
                       reviewed_by_user_id = ?,
                       reviewed_at = ?,
                       closed_at = COALESCE(?, closed_at),
                       updated_at = ?
                 WHERE case_id = ?
                RETURNING case_id, user_id, status, risk_score, source, summary, assigned_admin_user_id,
                          created_by_user_id, reviewed_by_user_id, reviewed_at, closed_at, created_at, updated_at
                """, (rs, rowNum) -> toAmlCase(rs),
                status, riskScore, adminUserId, Timestamp.from(now),
                ComplianceValidation.timestamp(closedAt), Timestamp.from(now), caseId)
                .stream()
                .findFirst();
    }

    private AmlCase toAmlCase(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AmlCase(
                rs.getLong("case_id"),
                rs.getLong("user_id"),
                rs.getString("status"),
                rs.getInt("risk_score"),
                rs.getString("source"),
                rs.getString("summary"),
                ComplianceValidation.nullableLong(rs, "assigned_admin_user_id"),
                ComplianceValidation.nullableLong(rs, "created_by_user_id"),
                ComplianceValidation.nullableLong(rs, "reviewed_by_user_id"),
                ComplianceValidation.nullableInstant(rs, "reviewed_at"),
                ComplianceValidation.nullableInstant(rs, "closed_at"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private java.util.function.Function<AmlCase, Instant> timestampExtractor(AdminCursorPage.SortSpec sort) {
        return switch (sort.field()) {
            case "createdAt" -> AmlCase::createdAt;
            case "updatedAt" -> AmlCase::updatedAt;
            default -> throw new IllegalArgumentException("unsupported sort: " + sort.token());
        };
    }
}
