package com.surprising.insurance.api.model;

import java.time.Instant;

public record InsuranceCoverageResponse(
        long coverageId,
        long userId,
        String asset,
        long requestedUnits,
        long coveredUnits,
        long remainingDeficitUnits,
        String status,
        String reason,
        Instant createdAt,
        Instant updatedAt) {
}
