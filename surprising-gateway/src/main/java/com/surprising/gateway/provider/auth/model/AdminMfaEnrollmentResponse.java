package com.surprising.gateway.provider.auth.model;

import java.time.Instant;

public record AdminMfaEnrollmentResponse(
        boolean enabled,
        String secret,
        String otpauthUri,
        Instant generatedAt) {
}
