package com.surprising.gateway.provider.auth.model;

import java.time.Instant;

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
