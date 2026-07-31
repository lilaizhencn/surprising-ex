package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.ComplianceModels.KycProfile;
import com.surprising.gateway.provider.auth.ComplianceModels.KycUpdateRequest;
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
                       rejection_reason = EXCLUDED.rejection_reason,
                       expires_at = EXCLUDED.expires_at,
                       updated_at = EXCLUDED.updated_at
                RETURNING user_id, kyc_level, status, country, document_type, provider, provider_reference,
                          reviewed_by_user_id, reviewed_at, rejection_reason, expires_at, created_at, updated_at
                """, (rs, rowNum) -> toProfile(rs),
                userId, ComplianceValidation.kycLevel(request.kycLevel()),
                ComplianceValidation.kycStatus(request.status()), ComplianceValidation.country(request.country()),
                ComplianceValidation.blankToNull(request.documentType()),
                ComplianceValidation.blankToNull(request.provider()),
                ComplianceValidation.blankToNull(request.providerReference()), adminUserId, Timestamp.from(now),
                ComplianceValidation.blankToNull(request.rejectionReason()),
                ComplianceValidation.timestamp(request.expiresAt()), Timestamp.from(now), Timestamp.from(now));
    }

    public KycProfile find(long userId) {
        return jdbcTemplate.query("""
                SELECT user_id, kyc_level, status, country, document_type, provider, provider_reference,
                       reviewed_by_user_id, reviewed_at, rejection_reason, expires_at, created_at, updated_at
                  FROM gateway_user_kyc_profiles
                 WHERE user_id = ?
                """, (rs, rowNum) -> toProfile(rs), userId).stream().findFirst().orElse(null);
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
                rs.getTimestamp("updated_at").toInstant());
    }
}
