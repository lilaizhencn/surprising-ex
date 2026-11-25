package com.surprising.gateway.provider.auth.model;

public record AmlCaseCreateRequest(
        String status,
        Integer riskScore,
        String source,
        String summary,
        Long assignedAdminUserId) {
}
