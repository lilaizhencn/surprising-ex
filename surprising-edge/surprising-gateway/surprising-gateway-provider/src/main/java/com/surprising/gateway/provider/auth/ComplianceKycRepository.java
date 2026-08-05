package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.ComplianceModels.KycProfile;
import com.surprising.gateway.provider.auth.ComplianceModels.KycSubmissionRequest;
import com.surprising.gateway.provider.auth.ComplianceModels.KycUpdateRequest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 只负责 {@code gateway_user_kyc_profiles} 表。
 */
@Repository
public class ComplianceKycRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ComplianceKycRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
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
                          reviewed_by_user_id, reviewed_at, rejection_reason, expires_at, created_at, updated_at,
                          applicant_type, submitted_documents::text, face_verification_status
                """, (rs, rowNum) -> toProfile(rs),
                userId, ComplianceValidation.kycLevel(request.kycLevel()),
                ComplianceValidation.kycStatus(request.status()), ComplianceValidation.country(request.country()),
                ComplianceValidation.blankToNull(request.documentType()),
                ComplianceValidation.provider(request.provider()),
                ComplianceValidation.providerReference(ComplianceValidation.provider(request.provider()),
                        request.providerReference()), adminUserId, Timestamp.from(now),
                ComplianceValidation.blankToNull(request.rejectionReason()),
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
                       updated_at = EXCLUDED.updated_at
                RETURNING user_id, kyc_level, status, country, document_type, provider, provider_reference,
                          reviewed_by_user_id, reviewed_at, rejection_reason, expires_at, created_at, updated_at,
                          applicant_type, submitted_documents::text, face_verification_status
                """, (rs, rowNum) -> toProfile(rs),
                userId, ComplianceValidation.kycLevel(request.kycLevel()),
                ComplianceValidation.country(request.country()),
                ComplianceValidation.blankToNull(request.documentType()),
                ComplianceValidation.provider(request.provider()),
                ComplianceValidation.providerReference(ComplianceValidation.provider(request.provider()),
                        request.providerReference()),
                documents(submittedDocuments),
                applicantType(request.applicantType()),
                faceStatus(request.faceVerificationStatus()),
                Timestamp.from(now), Timestamp.from(now));
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

    private String documents(String value) {
        String normalized = value == null || value.isBlank() ? "[]" : value.trim();
        try {
            JsonNode parsed = objectMapper.readTree(normalized);
            if (parsed == null || !parsed.isArray()) {
                throw new IllegalArgumentException("submittedDocuments must be a JSON array");
            }
            if (parsed.size() > 10) {
                throw new IllegalArgumentException("submittedDocuments may contain at most 10 items");
            }
            Set<String> allowedTypes = Set.of(
                    "ID_CARD", "PASSPORT", "ADDRESS_PROOF", "BUSINESS_LICENSE", "FACE_IMAGE");
            for (JsonNode document : parsed) {
                if (!document.isObject()) {
                    throw new IllegalArgumentException("each submitted document must be an object");
                }
                JsonNode type = document.get("type");
                JsonNode reference = document.get("reference");
                String normalizedType = type == null ? "" : type.asText("").trim().toUpperCase(Locale.ROOT);
                String normalizedReference = reference == null ? "" : reference.asText("").trim();
                if (!allowedTypes.contains(normalizedType) || normalizedReference.isBlank()
                        || normalizedReference.length() > 240) {
                    throw new IllegalArgumentException("submitted document type or reference is invalid");
                }
            }
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("submittedDocuments must be valid JSON", ex);
        }
        if (normalized.length() > 8000) {
            throw new IllegalArgumentException("submittedDocuments must be a JSON array");
        }
        return normalized;
    }

    private String applicantType(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase();
        if (!normalized.equals("INDIVIDUAL") && !normalized.equals("BUSINESS")) {
            throw new IllegalArgumentException("applicantType must be INDIVIDUAL or BUSINESS");
        }
        return normalized;
    }

    private String faceStatus(String value) {
        String normalized = value == null || value.isBlank() ? "NOT_REQUIRED" : value.trim().toUpperCase();
        if (!normalized.matches("NOT_REQUIRED|PENDING")) {
            throw new IllegalArgumentException("faceVerificationStatus may only be NOT_REQUIRED or PENDING");
        }
        return normalized;
    }
}
