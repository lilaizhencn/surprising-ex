package com.surprising.gateway.provider.auth.model;

public record AmlCaseStatusUpdateRequest(
        String status,
        Integer riskScore) {
}
