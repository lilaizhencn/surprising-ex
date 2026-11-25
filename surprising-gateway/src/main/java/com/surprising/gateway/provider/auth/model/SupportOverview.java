package com.surprising.gateway.provider.auth.model;

import java.time.Instant;

public record SupportOverview(
        Instant generatedAt,
        SupportUserSummary user,
        SupportComplianceSummary compliance) {
}
