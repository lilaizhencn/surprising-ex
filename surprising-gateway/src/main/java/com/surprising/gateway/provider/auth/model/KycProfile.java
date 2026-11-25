package com.surprising.gateway.provider.auth.model;

import java.time.Instant;

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
