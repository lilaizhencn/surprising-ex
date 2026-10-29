package com.surprising.gateway.provider.auth.repository;

import com.surprising.gateway.provider.auth.utils.ComplianceValidation;

import com.surprising.gateway.provider.auth.model.ComplianceModels.KycProfile;
import com.surprising.gateway.provider.auth.model.ComplianceModels.KycSubmissionRequest;
import com.surprising.gateway.provider.auth.model.ComplianceModels.KycUpdateRequest;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 只负责 {@code gateway_user_kyc_profiles} 表。
 */
@Repository
public class ComplianceKycRepository {

    private final JdbcTemplate jdbcTemplate;

    public ComplianceKycRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public KycProfile upsert(long userId, long adminUserId, KycUpdateRequest request, Instant now) {
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
                       provider_event_at = GREATEST(gateway_user_kyc_profiles.provider_event_at, EXCLUDED.reviewed_at),
                       rejection_reason = EXCLUDED.rejection_reason,
                       face_verification_status = CASE
                           WHEN EXCLUDED.status = 'VERIFIED' AND gateway_user_kyc_profiles.face_verification_status <> 'NOT_REQUIRED' THEN 'VERIFIED'
                           WHEN EXCLUDED.status = 'REJECTED' AND gateway_user_kyc_profiles.face_verification_status <> 'NOT_REQUIRED' THEN 'FAILED'
                           ELSE gateway_user_kyc_profiles.face_verification_status END,
                       expires_at = EXCLUDED.expires_at,
                       updated_at = EXCLUDED.updated_at
                RETURNING user_id, kyc_level, status, country, document_type, provider, provider_reference,
                          reviewed_by_user_id, reviewed_at, rejection_reason, expires_at, created_at, updated_at,
                          applicant_type, submitted_documents::text, face_verification_status
                """, (rs, rowNum) -> toProfile(rs),
                userId, request.kycLevel(),
                request.status(), request.country(),
                request.documentType(),
                request.provider(),
                request.providerReference(), adminUserId, Timestamp.from(now),
                request.rejectionReason(),
                ComplianceValidation.timestamp(request.expiresAt()), Timestamp.from(now), Timestamp.from(now));
    }

    public KycProfile submit(long userId, KycSubmissionRequest request, Instant now) {
        return submit(userId, request, request.submittedDocuments(), now);
    }

    public KycProfile submit(long userId, KycSubmissionRequest request, String submittedDocuments, Instant now) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO gateway_user_kyc_profiles (
                    user_id, kyc_level, status, country, document_type, provider, provider_reference,
                    submitted_documents, applicant_type, face_verification_status,
                    reviewed_by_user_id, reviewed_at, rejection_reason, expires_at, created_at, updated_at
                ) VALUES (?, ?, 'PENDING', ?, ?, ?, ?, ?::jsonb, ?, ?, NULL, NULL, NULL, NULL, ?, ?)
                ON CONFLICT (user_id) DO UPDATE
                   SET kyc_level = EXCLUDED.kyc_level,
                       status = 'PENDING',
                       country = EXCLUDED.country,
                       document_type = EXCLUDED.document_type,
                       provider = EXCLUDED.provider,
                       provider_reference = EXCLUDED.provider_reference,
                       submitted_documents = EXCLUDED.submitted_documents,
                       applicant_type = EXCLUDED.applicant_type,
                       face_verification_status = EXCLUDED.face_verification_status,
                       reviewed_by_user_id = NULL,
                       reviewed_at = NULL,
                       rejection_reason = NULL,
                       expires_at = NULL,
                       provider_event_at = NULL,
                       provider_redirect_url = NULL,
                       updated_at = EXCLUDED.updated_at
                RETURNING user_id, kyc_level, status, country, document_type, provider, provider_reference,
                          reviewed_by_user_id, reviewed_at, rejection_reason, expires_at, created_at, updated_at,
                          applicant_type, submitted_documents::text, face_verification_status
                """, (rs, rowNum) -> toProfile(rs),
                userId, request.kycLevel(),
                request.country(),
                request.documentType(),
                request.provider(),
                request.providerReference(),
                submittedDocuments,
                request.applicantType(),
                request.faceVerificationStatus(),
                Timestamp.from(now), Timestamp.from(now));
    }

    /** 首次申请也创建可锁定的资料行，防止并发请求启动两个第三方会话。调用方必须持有事务。 */
    public KycProfile lockForSubmission(long userId, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO gateway_user_kyc_profiles(user_id, created_at, updated_at)
                VALUES (?, ?, ?) ON CONFLICT (user_id) DO NOTHING
                """, userId, Timestamp.from(now), Timestamp.from(now));
        return findForUpdate(userId);
    }

    public void saveProviderSession(long userId, String redirectUrl, Instant startedAt) {
        jdbcTemplate.update("UPDATE gateway_user_kyc_profiles SET provider_redirect_url=?, provider_event_at=? WHERE user_id=?",
                redirectUrl, Timestamp.from(startedAt.minusMillis(1)), userId);
    }

    public String providerRedirectUrl(long userId) {
        return jdbcTemplate.queryForObject("SELECT provider_redirect_url FROM gateway_user_kyc_profiles WHERE user_id=?", String.class, userId);
    }

    public boolean acceptProviderEvent(long userId, Instant occurredAt) {
        return jdbcTemplate.update("""
                UPDATE gateway_user_kyc_profiles SET provider_event_at=?
                 WHERE user_id=? AND (provider_event_at IS NULL OR provider_event_at<?)
                """, Timestamp.from(occurredAt), userId, Timestamp.from(occurredAt)) == 1;
    }

    public void updateExpiry(long userId, Instant expiresAt) {
        jdbcTemplate.update("UPDATE gateway_user_kyc_profiles SET expires_at=? WHERE user_id=?", Timestamp.from(expiresAt), userId);
    }

    public KycProfile find(long userId) {
        return jdbcTemplate.query("""
                SELECT user_id, kyc_level, status, country, document_type, provider, provider_reference,
                       reviewed_by_user_id, reviewed_at, rejection_reason, expires_at, created_at, updated_at,
                       applicant_type, submitted_documents::text, face_verification_status
                  FROM gateway_user_kyc_profiles
                 WHERE user_id = ?
                """, (rs, rowNum) -> toProfile(rs), userId).stream().findFirst().orElse(null);
    }

    /** 调用方事务持有当前资料行，回调资格校验与状态更新不能跨越新的申请会话。 */
    public KycProfile findForUpdate(long userId) {
        return jdbcTemplate.query("""
                SELECT user_id, kyc_level, status, country, document_type, provider, provider_reference,
                       reviewed_by_user_id, reviewed_at, rejection_reason, expires_at, created_at, updated_at,
                       applicant_type, submitted_documents::text, face_verification_status
                  FROM gateway_user_kyc_profiles
                 WHERE user_id = ? FOR UPDATE
                """, (rs, rowNum) -> toProfile(rs), userId).stream().findFirst().orElse(null);
    }

    public KycProfile applyProviderResult(long userId, String provider, String status, String rejectionReason, Instant reviewedAt, String faceStatus, Instant now, Instant expiresAt) {
        return jdbcTemplate.queryForObject("""
                UPDATE gateway_user_kyc_profiles
                   SET status = ?, rejection_reason = ?,
                       reviewed_by_user_id = NULL,
                       reviewed_at = ?,
                       face_verification_status = ?, expires_at = ?,
                       updated_at = ?
                 WHERE user_id = ? AND provider = ?
                RETURNING user_id, kyc_level, status, country, document_type, provider, provider_reference,
                          reviewed_by_user_id, reviewed_at, rejection_reason, expires_at, created_at, updated_at,
                          applicant_type, submitted_documents::text, face_verification_status
                """, (rs, rowNum) -> toProfile(rs), status, rejectionReason,
                ComplianceValidation.timestamp(reviewedAt), faceStatus, ComplianceValidation.timestamp(expiresAt), Timestamp.from(now), userId, provider);
    }

    private KycProfile toProfile(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new KycProfile(
                rs.getLong("user_id"),
                rs.getString("kyc_level"),
                rs.getString("status"),
                rs.getString("country"),
                rs.getString("document_type"),
                rs.getString("provider"),
                rs.getString("provider_reference"),
                ComplianceValidation.nullableLong(rs, "reviewed_by_user_id"),
                ComplianceValidation.nullableInstant(rs, "reviewed_at"),
                rs.getString("rejection_reason"),
                ComplianceValidation.nullableInstant(rs, "expires_at"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getString("applicant_type"), rs.getString("submitted_documents"),
                rs.getString("face_verification_status"));
    }

}
