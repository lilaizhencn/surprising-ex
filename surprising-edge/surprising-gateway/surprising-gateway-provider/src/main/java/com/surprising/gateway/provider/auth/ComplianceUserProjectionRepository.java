package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.ComplianceModels.ComplianceUserSummary;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 提供合规控制台的用户列表投影。
 *
 * <p>不可拆原因：列表的 KYC 状态、活动风险标签、未结 AML case 和游标顺序必须基于同一数据库快照
 * 完成筛选与分页；拆成多个单表查询会导致跨页重复或遗漏。该查询只访问 gateway 自有合规表，
 * 不访问交易主库，也不得扩展为资金、订单或运营报表。</p>
 */
@Repository
public class ComplianceUserProjectionRepository {

    private static final int MAX_QUERY_LIMIT = 500;
    private static final AdminCursorPage.SortSpec UPDATED_DESC =
            new AdminCursorPage.SortSpec("updatedAt", "updated_at", "user_id", true);
    private static final List<AdminCursorPage.SortSpec> SORTS = List.of(
            UPDATED_DESC,
            new AdminCursorPage.SortSpec("updatedAt", "updated_at", "user_id", false));

    private final JdbcTemplate jdbcTemplate;

    public ComplianceUserProjectionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public AdminCursorPage.CursorPage<ComplianceUserSummary> usersPage(Long userId,
                                                                       String kycStatus,
                                                                       String tagCode,
                                                                       int limit,
                                                                       String cursor,
                                                                       String sort) {
        String normalizedKycStatus = ComplianceValidation.nullableUpper(kycStatus);
        String normalizedTagCode = ComplianceValidation.nullableTag(tagCode);
        int safeLimit = AdminCursorPage.limit(limit, MAX_QUERY_LIMIT);
        AdminCursorPage.SortSpec sortSpec = AdminCursorPage.parseSort(sort, UPDATED_DESC, SORTS);
        AdminCursorPage.Cursor decodedCursor = AdminCursorPage.decodeCursor(cursor);
        List<Object> args = new ArrayList<>();
        args.add(userId);
        args.add(userId);
        args.add(normalizedKycStatus);
        args.add(normalizedKycStatus);
        args.add(normalizedTagCode);
        args.add(normalizedTagCode);
        String sql = """
                SELECT *
                  FROM (
                        SELECT u.user_id,
                               u.username,
                               u.status AS user_status,
                               k.kyc_level,
                               k.status AS kyc_status,
                               k.country,
                               COALESCE((
                                   SELECT COUNT(*)
                                     FROM gateway_user_risk_tags t
                                    WHERE t.user_id = u.user_id
                                      AND t.status = 'ACTIVE'
                               ), 0) AS active_risk_tags,
                               COALESCE((
                                   SELECT COUNT(*)
                                     FROM gateway_user_aml_cases c
                                    WHERE c.user_id = u.user_id
                                      AND c.status IN ('OPEN', 'REVIEWING', 'ESCALATED', 'RESTRICTED')
                               ), 0) AS open_aml_cases,
                               GREATEST(u.updated_at, COALESCE(k.updated_at, u.updated_at)) AS updated_at
                          FROM gateway_users u
                          LEFT JOIN gateway_user_kyc_profiles k ON k.user_id = u.user_id
                         WHERE (CAST(? AS text) IS NULL OR u.user_id = ?)
                           AND (CAST(? AS text) IS NULL OR k.status = ?)
                           AND (CAST(? AS text) IS NULL OR EXISTS (
                               SELECT 1
                                 FROM gateway_user_risk_tags t
                                WHERE t.user_id = u.user_id
                                  AND t.status = 'ACTIVE'
                                  AND t.tag_code = ?
                           ))
                       ) q
                 WHERE TRUE
                """ + AdminCursorPage.seekCondition(sortSpec, decodedCursor) + """
                 ORDER BY %s %s, user_id %s
                 LIMIT ?
                """.formatted(sortSpec.column(), sortSpec.directionSql(), sortSpec.directionSql());
        AdminCursorPage.addCursorArgs(args, decodedCursor);
        args.add(safeLimit + 1);
        List<ComplianceUserSummary> fetchedRows = jdbcTemplate.query(
                sql, (rs, rowNum) -> toComplianceUser(rs), args.toArray());
        return AdminCursorPage.page(fetchedRows, safeLimit, sortSpec,
                ComplianceUserSummary::updatedAt, ComplianceUserSummary::userId);
    }

    private ComplianceUserSummary toComplianceUser(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ComplianceUserSummary(
                rs.getLong("user_id"),
                rs.getString("username"),
                rs.getString("user_status"),
                ComplianceValidation.defaultString(rs.getString("kyc_level"), "NONE"),
                ComplianceValidation.defaultString(rs.getString("kyc_status"), "UNVERIFIED"),
                rs.getString("country"),
                rs.getInt("active_risk_tags"),
                rs.getInt("open_aml_cases"),
                rs.getTimestamp("updated_at").toInstant());
    }
}
