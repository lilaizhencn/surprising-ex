package com.surprising.gateway.provider.auth;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ComplianceRepository {

    private static final int MAX_QUERY_LIMIT = 500;
    private static final AdminCursorPage.SortSpec COMPLIANCE_USER_UPDATED_DESC =
            new AdminCursorPage.SortSpec("updatedAt", "updated_at", "user_id", true);
    private static final List<AdminCursorPage.SortSpec> COMPLIANCE_USER_SORTS = List.of(
            COMPLIANCE_USER_UPDATED_DESC,
            new AdminCursorPage.SortSpec("updatedAt", "updated_at", "user_id", false));
    private static final AdminCursorPage.SortSpec RISK_TAG_CREATED_DESC =
            new AdminCursorPage.SortSpec("createdAt", "created_at", "tag_id", true);
    private static final List<AdminCursorPage.SortSpec> RISK_TAG_SORTS = List.of(
            RISK_TAG_CREATED_DESC,
            new AdminCursorPage.SortSpec("createdAt", "created_at", "tag_id", false),
            new AdminCursorPage.SortSpec("updatedAt", "updated_at", "tag_id", true),
            new AdminCursorPage.SortSpec("updatedAt", "updated_at", "tag_id", false));
    private static final AdminCursorPage.SortSpec AML_CASE_UPDATED_DESC =
            new AdminCursorPage.SortSpec("updatedAt", "updated_at", "case_id", true);
    private static final List<AdminCursorPage.SortSpec> AML_CASE_SORTS = List.of(
            AML_CASE_UPDATED_DESC,
            new AdminCursorPage.SortSpec("updatedAt", "updated_at", "case_id", false),
            new AdminCursorPage.SortSpec("createdAt", "created_at", "case_id", true),
            new AdminCursorPage.SortSpec("createdAt", "created_at", "case_id", false));

    private final JdbcTemplate jdbcTemplate;

    public ComplianceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ComplianceUserSummary> users(Long userId, String kycStatus, String tagCode, int limit) {
        String normalizedKycStatus = normalizeNullableUpper(kycStatus);
        String normalizedTagCode = normalizeNullableTag(tagCode);
        int safeLimit = AdminCursorPage.limit(limit, MAX_QUERY_LIMIT);
        return jdbcTemplate.query("""
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
                 ORDER BY updated_at DESC, u.user_id DESC
                 LIMIT ?
                """, (rs, rowNum) -> new ComplianceUserSummary(
                rs.getLong("user_id"),
                rs.getString("username"),
                rs.getString("user_status"),
                defaultString(rs.getString("kyc_level"), "NONE"),
                defaultString(rs.getString("kyc_status"), "UNVERIFIED"),
                rs.getString("country"),
                rs.getInt("active_risk_tags"),
                rs.getInt("open_aml_cases"),
                rs.getTimestamp("updated_at").toInstant()),
                userId, userId, normalizedKycStatus, normalizedKycStatus,
                normalizedTagCode, normalizedTagCode, safeLimit);
    }

    public AdminCursorPage.CursorPage<ComplianceUserSummary> usersPage(Long userId,
                                                                       String kycStatus,
                                                                       String tagCode,
                                                                       int limit,
                                                                       String cursor,
                                                                       String sort) {
        String normalizedKycStatus = normalizeNullableUpper(kycStatus);
        String normalizedTagCode = normalizeNullableTag(tagCode);
        int safeLimit = AdminCursorPage.limit(limit, MAX_QUERY_LIMIT);
        AdminCursorPage.SortSpec sortSpec = AdminCursorPage.parseSort(
                sort, COMPLIANCE_USER_UPDATED_DESC, COMPLIANCE_USER_SORTS);
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
        List<ComplianceUserSummary> fetchedRows = jdbcTemplate.query(sql, (rs, rowNum) -> toComplianceUser(rs),
                args.toArray());
        return AdminCursorPage.page(fetchedRows, safeLimit, sortSpec,
                ComplianceUserSummary::updatedAt, ComplianceUserSummary::userId);
    }

    public KycProfile upsertKyc(long userId, long adminUserId, KycUpdateRequest request, Instant now) {
        String level = normalizeKycLevel(request.kycLevel());
        String status = normalizeKycStatus(request.status());
        String country = normalizeCountry(request.country());
        return jdbcTemplate.queryForObject("""
                INSERT INTO gateway_user_kyc_profiles (
                    user_id, kyc_level, status, country, document_type, provider, provider_reference,
                    reviewed_by_user_id, reviewed_at, rejection_reason, expires_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE
                   SET kyc_level = EXCLUDED.kyc_level,
                       status = EXCLUDED.status,
                       country = EXCLUDED.country,
                       document_type = EXCLUDED.document_type,
                       provider = EXCLUDED.provider,
                       provider_reference = EXCLUDED.provider_reference,
                       reviewed_by_user_id = EXCLUDED.reviewed_by_user_id,
                       reviewed_at = EXCLUDED.reviewed_at,
                       rejection_reason = EXCLUDED.rejection_reason,
                       expires_at = EXCLUDED.expires_at,
                       updated_at = EXCLUDED.updated_at
                RETURNING user_id, kyc_level, status, country, document_type, provider, provider_reference,
                          reviewed_by_user_id, reviewed_at, rejection_reason, expires_at, created_at, updated_at
                """, (rs, rowNum) -> new KycProfile(
                rs.getLong("user_id"),
                rs.getString("kyc_level"),
                rs.getString("status"),
                rs.getString("country"),
                rs.getString("document_type"),
                rs.getString("provider"),
                rs.getString("provider_reference"),
                nullableLong(rs, "reviewed_by_user_id"),
                nullableInstant(rs, "reviewed_at"),
                rs.getString("rejection_reason"),
                nullableInstant(rs, "expires_at"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()),
                userId, level, status, country, blankToNull(request.documentType()), blankToNull(request.provider()),
                blankToNull(request.providerReference()), adminUserId, Timestamp.from(now),
                blankToNull(request.rejectionReason()), timestamp(request.expiresAt()), Timestamp.from(now), Timestamp.from(now));
    }

    public KycProfile kyc(long userId) {
        return jdbcTemplate.query("""
                SELECT user_id, kyc_level, status, country, document_type, provider, provider_reference,
                       reviewed_by_user_id, reviewed_at, rejection_reason, expires_at, created_at, updated_at
                  FROM gateway_user_kyc_profiles
                 WHERE user_id = ?
                """, (rs, rowNum) -> new KycProfile(
                rs.getLong("user_id"),
                rs.getString("kyc_level"),
                rs.getString("status"),
                rs.getString("country"),
                rs.getString("document_type"),
                rs.getString("provider"),
                rs.getString("provider_reference"),
                nullableLong(rs, "reviewed_by_user_id"),
                nullableInstant(rs, "reviewed_at"),
                rs.getString("rejection_reason"),
                nullableInstant(rs, "expires_at"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()), userId).stream().findFirst().orElse(null);
    }

    public List<RiskTag> riskTags(Long userId, String status, int limit) {
        return riskTagsPage(userId, status, limit, null, null).items();
    }

    public AdminCursorPage.CursorPage<RiskTag> riskTagsPage(Long userId,
                                                            String status,
                                                            int limit,
                                                            String cursor,
                                                            String sort) {
        String normalizedStatus = normalizeNullableUpper(status);
        int safeLimit = AdminCursorPage.limit(limit, MAX_QUERY_LIMIT);
        AdminCursorPage.SortSpec sortSpec = AdminCursorPage.parseSort(sort, RISK_TAG_CREATED_DESC, RISK_TAG_SORTS);
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
        return AdminCursorPage.page(fetchedRows, safeLimit, sortSpec, riskTagTimestampExtractor(sortSpec),
                RiskTag::tagId);
    }

    public RiskTag createRiskTag(long userId, long adminUserId, RiskTagCreateRequest request, Instant now) {
        try {
            return jdbcTemplate.queryForObject("""
                    INSERT INTO gateway_user_risk_tags (
                        user_id, tag_code, severity, status, source, reason, created_by_user_id, created_at, updated_at
                    ) VALUES (?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?)
                    RETURNING tag_id, user_id, tag_code, severity, status, source, reason,
                              created_by_user_id, resolved_by_user_id, created_at, resolved_at, updated_at
                    """, (rs, rowNum) -> toRiskTag(rs),
                    userId, normalizeTagCode(request.tagCode()), normalizeSeverity(request.severity()),
                    blankToNull(request.source()), requiredText(request.reason(), "reason"), adminUserId,
                    Timestamp.from(now), Timestamp.from(now));
        } catch (DuplicateKeyException ex) {
            throw new IllegalArgumentException("active risk tag already exists", ex);
        }
    }

    public RiskTag resolveRiskTag(long tagId, long adminUserId, Instant now) {
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

    public List<AmlCase> amlCases(Long userId, String status, int limit) {
        return amlCasesPage(userId, status, limit, null, null).items();
    }

    public AdminCursorPage.CursorPage<AmlCase> amlCasesPage(Long userId,
                                                            String status,
                                                            int limit,
                                                            String cursor,
                                                            String sort) {
        String normalizedStatus = normalizeNullableUpper(status);
        int safeLimit = AdminCursorPage.limit(limit, MAX_QUERY_LIMIT);
        AdminCursorPage.SortSpec sortSpec = AdminCursorPage.parseSort(sort, AML_CASE_UPDATED_DESC, AML_CASE_SORTS);
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
        return AdminCursorPage.page(fetchedRows, safeLimit, sortSpec, amlCaseTimestampExtractor(sortSpec),
                AmlCase::caseId);
    }

    public AmlCase createAmlCase(long userId, long adminUserId, AmlCaseCreateRequest request, Instant now) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO gateway_user_aml_cases (
                    user_id, status, risk_score, source, summary, assigned_admin_user_id,
                    created_by_user_id, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING case_id, user_id, status, risk_score, source, summary, assigned_admin_user_id,
                          created_by_user_id, reviewed_by_user_id, reviewed_at, closed_at, created_at, updated_at
                """, (rs, rowNum) -> toAmlCase(rs),
                userId, normalizeAmlStatus(defaultString(request.status(), "OPEN")),
                boundedRiskScore(request.riskScore()), blankToNull(request.source()),
                requiredText(request.summary(), "summary"), request.assignedAdminUserId(),
                adminUserId, Timestamp.from(now), Timestamp.from(now));
    }

    public AmlCase updateAmlCaseStatus(long caseId, long adminUserId, AmlCaseStatusUpdateRequest request, Instant now) {
        String status = normalizeAmlStatus(request.status());
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
                status, request.riskScore() == null ? null : boundedRiskScore(request.riskScore()),
                adminUserId, Timestamp.from(now), closed, Timestamp.from(now), Timestamp.from(now), caseId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("aml case not found"));
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
                nullableLong(rs, "created_by_user_id"),
                nullableLong(rs, "resolved_by_user_id"),
                rs.getTimestamp("created_at").toInstant(),
                nullableInstant(rs, "resolved_at"),
                rs.getTimestamp("updated_at").toInstant());
    }

    private AmlCase toAmlCase(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AmlCase(
                rs.getLong("case_id"),
                rs.getLong("user_id"),
                rs.getString("status"),
                rs.getInt("risk_score"),
                rs.getString("source"),
                rs.getString("summary"),
                nullableLong(rs, "assigned_admin_user_id"),
                nullableLong(rs, "created_by_user_id"),
                nullableLong(rs, "reviewed_by_user_id"),
                nullableInstant(rs, "reviewed_at"),
                nullableInstant(rs, "closed_at"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private java.util.function.Function<RiskTag, Instant> riskTagTimestampExtractor(AdminCursorPage.SortSpec sort) {
        return switch (sort.field()) {
            case "createdAt" -> RiskTag::createdAt;
            case "updatedAt" -> RiskTag::updatedAt;
            default -> throw new IllegalArgumentException("unsupported sort: " + sort.token());
        };
    }

    private java.util.function.Function<AmlCase, Instant> amlCaseTimestampExtractor(AdminCursorPage.SortSpec sort) {
        return switch (sort.field()) {
            case "createdAt" -> AmlCase::createdAt;
            case "updatedAt" -> AmlCase::updatedAt;
            default -> throw new IllegalArgumentException("unsupported sort: " + sort.token());
        };
    }

    private String normalizeKycLevel(String value) {
        String normalized = defaultString(value, "NONE").trim().toUpperCase(Locale.ROOT);
        if (!List.of("NONE", "BASIC", "STANDARD", "ENHANCED", "INSTITUTIONAL").contains(normalized)) {
            throw new IllegalArgumentException("invalid kycLevel");
        }
        return normalized;
    }

    private String normalizeKycStatus(String value) {
        String normalized = defaultString(value, "UNVERIFIED").trim().toUpperCase(Locale.ROOT);
        if (!List.of("UNVERIFIED", "PENDING", "VERIFIED", "REJECTED", "EXPIRED").contains(normalized)) {
            throw new IllegalArgumentException("invalid kyc status");
        }
        return normalized;
    }

    private String normalizeAmlStatus(String value) {
        String normalized = defaultString(value, "OPEN").trim().toUpperCase(Locale.ROOT);
        if (!List.of("OPEN", "REVIEWING", "CLEARED", "ESCALATED", "RESTRICTED", "CLOSED").contains(normalized)) {
            throw new IllegalArgumentException("invalid aml status");
        }
        return normalized;
    }

    private String normalizeSeverity(String value) {
        String normalized = defaultString(value, "MEDIUM").trim().toUpperCase(Locale.ROOT);
        if (!List.of("LOW", "MEDIUM", "HIGH", "CRITICAL").contains(normalized)) {
            throw new IllegalArgumentException("invalid severity");
        }
        return normalized;
    }

    private String normalizeCountry(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z]{2}")) {
            throw new IllegalArgumentException("country must be ISO-3166 alpha-2");
        }
        return normalized;
    }

    private String normalizeNullableUpper(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeNullableTag(String value) {
        return value == null || value.isBlank() ? null : normalizeTagCode(value);
    }

    private String normalizeTagCode(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9_.:-]{2,64}")) {
            throw new IllegalArgumentException("invalid tagCode");
        }
        return normalized;
    }

    private int boundedRiskScore(Integer value) {
        int score = value == null ? 0 : value;
        if (score < 0 || score > 100) {
            throw new IllegalArgumentException("riskScore must be between 0 and 100");
        }
        return score;
    }

    private String requiredText(String value, String field) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized.length() > 2000 ? normalized.substring(0, 2000) : normalized;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private ComplianceUserSummary toComplianceUser(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ComplianceUserSummary(
                rs.getLong("user_id"),
                rs.getString("username"),
                rs.getString("user_status"),
                defaultString(rs.getString("kyc_level"), "NONE"),
                defaultString(rs.getString("kyc_status"), "UNVERIFIED"),
                rs.getString("country"),
                rs.getInt("active_risk_tags"),
                rs.getInt("open_aml_cases"),
                rs.getTimestamp("updated_at").toInstant());
    }

    private Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private Instant nullableInstant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    public record ComplianceUserSummary(
            long userId,
            String username,
            String userStatus,
            String kycLevel,
            String kycStatus,
            String country,
            int activeRiskTags,
            int openAmlCases,
            Instant updatedAt) {
    }

    public record KycProfile(
            long userId,
            String kycLevel,
            String status,
            String country,
            String documentType,
            String provider,
            String providerReference,
            Long reviewedByUserId,
            Instant reviewedAt,
            String rejectionReason,
            Instant expiresAt,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record RiskTag(
            long tagId,
            long userId,
            String tagCode,
            String severity,
            String status,
            String source,
            String reason,
            Long createdByUserId,
            Long resolvedByUserId,
            Instant createdAt,
            Instant resolvedAt,
            Instant updatedAt) {
    }

    public record AmlCase(
            long caseId,
            long userId,
            String status,
            int riskScore,
            String source,
            String summary,
            Long assignedAdminUserId,
            Long createdByUserId,
            Long reviewedByUserId,
            Instant reviewedAt,
            Instant closedAt,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record KycUpdateRequest(
            String kycLevel,
            String status,
            String country,
            String documentType,
            String provider,
            String providerReference,
            String rejectionReason,
            Instant expiresAt) {
    }

    public record RiskTagCreateRequest(
            String tagCode,
            String severity,
            String source,
            String reason) {
    }

    public record AmlCaseCreateRequest(
            String status,
            Integer riskScore,
            String source,
            String summary,
            Long assignedAdminUserId) {
    }

    public record AmlCaseStatusUpdateRequest(
            String status,
            Integer riskScore) {
    }
}
