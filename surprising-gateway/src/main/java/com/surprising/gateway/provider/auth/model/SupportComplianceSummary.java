package com.surprising.gateway.provider.auth.model;

import java.time.Instant;

public record SupportComplianceSummary(
        String kycLevel,
        String kycStatus,
        String country,
        Instant kycExpiresAt,
        int activeRiskTags,
        long criticalRiskTags,
        int openAmlCases,
        int maxAmlRiskScore) {
}
