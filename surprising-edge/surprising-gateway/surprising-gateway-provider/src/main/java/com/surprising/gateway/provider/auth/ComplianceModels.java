package com.surprising.gateway.provider.auth;

import java.time.Instant;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 合规领域在服务层与单表仓储之间共享的数据模型。
 */
public final class ComplianceModels {

    private ComplianceModels() {
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
            Instant updatedAt,
            String applicantType,
            String submittedDocuments,
            String faceVerificationStatus) {
        public KycProfile(long userId,
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
            this(userId, kycLevel, status, country, documentType, provider, providerReference,
                    reviewedByUserId, reviewedAt, rejectionReason, expiresAt, createdAt, updatedAt,
                    "INDIVIDUAL", "[]", "NOT_REQUIRED");
        }
    }

    public record KycSubmissionRequest(
            @NotBlank @Size(max = 20) String applicantType,
            @NotBlank @Size(max = 20) String kycLevel,
            @NotBlank @Size(min = 2, max = 2) String country,
            @NotBlank @Size(max = 40) String documentType,
            @Size(max = 40) String provider,
            @Size(max = 240) String providerReference,
            @Size(max = 8000) String submittedDocuments,
            @Size(max = 40) String faceVerificationStatus) {
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
