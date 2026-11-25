package com.surprising.gateway.provider.auth.model;

import java.time.Instant;

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
