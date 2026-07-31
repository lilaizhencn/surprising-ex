package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.ComplianceModels.AmlCase;
import com.surprising.gateway.provider.auth.ComplianceModels.AmlCaseCreateRequest;
import com.surprising.gateway.provider.auth.ComplianceModels.AmlCaseStatusUpdateRequest;
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

    private static final int MAX_QUERY_LIMIT = 500;
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
        return findPage(userId, status, limit, null, null).items();
    }

    public AdminCursorPage.CursorPage<AmlCase> findPage(Long userId,
                                                        String status,
                                                        int limit,
                                                        String cursor,
                                                        String sort) {
        String normalizedStatus = ComplianceValidation.nullableUpper(status);
        int safeLimit = AdminCursorPage.limit(limit, MAX_QUERY_LIMIT);
        AdminCursorPage.SortSpec sortSpec = AdminCursorPage.parseSort(sort, UPDATED_DESC, SORTS);
        AdminCursorPage.Cursor decodedCursor = AdminCursorPage.decodeCursor(cursor);
        List<Object> args = new ArrayList<>();
        args.add(userId);
        args.add(userId);
        args.add(normalizedStatus);
        args.add(normalizedStatus);
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
                userId, ComplianceValidation.amlStatus(
                        ComplianceValidation.defaultString(request.status(), "OPEN")),
                ComplianceValidation.riskScore(request.riskScore()),
                ComplianceValidation.blankToNull(request.source()),
                ComplianceValidation.requiredText(request.summary(), "summary"),
                request.assignedAdminUserId(), adminUserId, Timestamp.from(now), Timestamp.from(now));
    }

    public AmlCase updateStatus(long caseId,
                                long adminUserId,
                                AmlCaseStatusUpdateRequest request,
                                Instant now) {
        String status = ComplianceValidation.amlStatus(request.status());
        boolean closed = List.of("CLEARED", "CLOSED").contains(status);
        return jdbcTemplate.query("""
                UPDATE gateway_user_aml_cases
                   SET status = ?,
                       risk_score = COALESCE(?, risk_score),
                       reviewed_by_user_id = ?,
                       reviewed_at = ?,
                       closed_at = CASE WHEN ? THEN ? ELSE closed_at END,
                       updated_at = ?
                 WHERE case_id = ?
                RETURNING case_id, user_id, status, risk_score, source, summary, assigned_admin_user_id,
                          created_by_user_id, reviewed_by_user_id, reviewed_at, closed_at, created_at, updated_at
                """, (rs, rowNum) -> toAmlCase(rs),
                status, request.riskScore() == null ? null : ComplianceValidation.riskScore(request.riskScore()),
                adminUserId, Timestamp.from(now), closed, Timestamp.from(now), Timestamp.from(now), caseId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("aml case not found"));
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
