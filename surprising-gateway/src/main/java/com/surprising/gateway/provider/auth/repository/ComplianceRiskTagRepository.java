package com.surprising.gateway.provider.auth.repository;

import com.surprising.gateway.provider.auth.utils.ComplianceValidation;

import com.surprising.trading.api.utils.AdminCursorPage;
import com.surprising.gateway.provider.auth.model.AuthPageQuery;

import com.surprising.gateway.provider.auth.model.ComplianceModels.RiskTag;
import com.surprising.gateway.provider.auth.model.ComplianceModels.RiskTagCreateRequest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 只负责 {@code gateway_user_risk_tags} 表。
 */
@Repository
public class ComplianceRiskTagRepository {

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
        return findPage(userId, status, new AuthPageQuery(limit, null, AuthPageQuery.Order.CREATED_DESC)).items();
    }

    public AdminCursorPage.CursorPage<RiskTag> findPage(Long userId,
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
        return jdbcTemplate.queryForObject("""
                INSERT INTO gateway_user_risk_tags (
                    user_id, tag_code, severity, status, source, reason, created_by_user_id, created_at, updated_at
                ) VALUES (?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?)
                RETURNING tag_id, user_id, tag_code, severity, status, source, reason,
                          created_by_user_id, resolved_by_user_id, created_at, resolved_at, updated_at
                """, (rs, rowNum) -> toRiskTag(rs),
                userId, request.tagCode(),
                request.severity(),
                request.source(),
                request.reason(), adminUserId,
                Timestamp.from(now), Timestamp.from(now));
    }

    public java.util.Optional<RiskTag> resolve(long tagId, long adminUserId, Instant now) {
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
                .findFirst();
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
