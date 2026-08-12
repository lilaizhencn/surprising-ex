package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.ComplianceModels.RiskTag;
import com.surprising.gateway.provider.auth.ComplianceModels.RiskTagCreateRequest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 只负责 {@code gateway_user_risk_tags} 表。
 */
@Repository
public class ComplianceRiskTagRepository {

    private static final int MAX_QUERY_LIMIT = 500;
    private static final AdminCursorPage.SortSpec CREATED_DESC =
            new AdminCursorPage.SortSpec("createdAt", "created_at", "tag_id", true);
    private static final List<AdminCursorPage.SortSpec> SORTS = List.of(
            CREATED_DESC,
            new AdminCursorPage.SortSpec("createdAt", "created_at", "tag_id", false),
            new AdminCursorPage.SortSpec("updatedAt", "updated_at", "tag_id", true),
            new AdminCursorPage.SortSpec("updatedAt", "updated_at", "tag_id", false));

    private final JdbcTemplate jdbcTemplate;

    public ComplianceRiskTagRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<RiskTag> find(Long userId, String status, int limit) {
        return findPage(userId, status, limit, null, null).items();
    }

    public AdminCursorPage.CursorPage<RiskTag> findPage(Long userId,
                                                        String status,
                                                        int limit,
                                                        String cursor,
                                                        String sort) {
        String normalizedStatus = ComplianceValidation.nullableUpper(status);
        int safeLimit = AdminCursorPage.limit(limit, MAX_QUERY_LIMIT);
        AdminCursorPage.SortSpec sortSpec = AdminCursorPage.parseSort(sort, CREATED_DESC, SORTS);
        AdminCursorPage.Cursor decodedCursor = AdminCursorPage.decodeCursor(cursor);
        List<Object> args = new ArrayList<>();
        args.add(userId);
        args.add(userId);
        args.add(normalizedStatus);
        args.add(normalizedStatus);
        String sql = """
                SELECT tag_id, user_id, tag_code, severity, status, source, reason,
                       created_by_user_id, resolved_by_user_id, created_at, resolved_at, updated_at
                  FROM gateway_user_risk_tags
                 WHERE (CAST(? AS text) IS NULL OR user_id = ?)
                   AND (CAST(? AS text) IS NULL OR status = ?)
                """ + AdminCursorPage.seekCondition(sortSpec, decodedCursor) + """
                 ORDER BY %s %s, tag_id %s
                 LIMIT ?
                """.formatted(sortSpec.column(), sortSpec.directionSql(), sortSpec.directionSql());
        AdminCursorPage.addCursorArgs(args, decodedCursor);
        args.add(safeLimit + 1);
        List<RiskTag> fetchedRows = jdbcTemplate.query(sql, (rs, rowNum) -> toRiskTag(rs), args.toArray());
        return AdminCursorPage.page(
                fetchedRows, safeLimit, sortSpec, timestampExtractor(sortSpec), RiskTag::tagId);
    }

    public RiskTag create(long userId, long adminUserId, RiskTagCreateRequest request, Instant now) {
        try {
            return jdbcTemplate.queryForObject("""
                    INSERT INTO gateway_user_risk_tags (
                        user_id, tag_code, severity, status, source, reason, created_by_user_id, created_at, updated_at
                    ) VALUES (?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?)
                    RETURNING tag_id, user_id, tag_code, severity, status, source, reason,
                              created_by_user_id, resolved_by_user_id, created_at, resolved_at, updated_at
                    """, (rs, rowNum) -> toRiskTag(rs),
                    userId, ComplianceValidation.tagCode(request.tagCode()),
                    ComplianceValidation.severity(request.severity()),
                    ComplianceValidation.blankToNull(request.source()),
                    ComplianceValidation.requiredText(request.reason(), "reason"), adminUserId,
                    Timestamp.from(now), Timestamp.from(now));
        } catch (DuplicateKeyException ex) {
            throw new IllegalArgumentException("active risk tag already exists", ex);
        }
    }

    public RiskTag resolve(long tagId, long adminUserId, Instant now) {
        return jdbcTemplate.query("""
                UPDATE gateway_user_risk_tags
                   SET status = 'RESOLVED',
                       resolved_by_user_id = ?,
                       resolved_at = ?,
                       updated_at = ?
                 WHERE tag_id = ?
                RETURNING tag_id, user_id, tag_code, severity, status, source, reason,
                          created_by_user_id, resolved_by_user_id, created_at, resolved_at, updated_at
                """, (rs, rowNum) -> toRiskTag(rs), adminUserId, Timestamp.from(now), Timestamp.from(now), tagId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("risk tag not found"));
    }

    private RiskTag toRiskTag(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RiskTag(
                rs.getLong("tag_id"),
                rs.getLong("user_id"),
                rs.getString("tag_code"),
                rs.getString("severity"),
                rs.getString("status"),
                rs.getString("source"),
                rs.getString("reason"),
                ComplianceValidation.nullableLong(rs, "created_by_user_id"),
                ComplianceValidation.nullableLong(rs, "resolved_by_user_id"),
                rs.getTimestamp("created_at").toInstant(),
                ComplianceValidation.nullableInstant(rs, "resolved_at"),
                rs.getTimestamp("updated_at").toInstant());
    }

    private java.util.function.Function<RiskTag, Instant> timestampExtractor(AdminCursorPage.SortSpec sort) {
        return switch (sort.field()) {
            case "createdAt" -> RiskTag::createdAt;
            case "updatedAt" -> RiskTag::updatedAt;
            default -> throw new IllegalArgumentException("unsupported sort: " + sort.token());
        };
    }
}
