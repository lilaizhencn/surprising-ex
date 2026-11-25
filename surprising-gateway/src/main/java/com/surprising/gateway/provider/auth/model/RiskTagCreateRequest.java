package com.surprising.gateway.provider.auth.model;

public record RiskTagCreateRequest(
        String tagCode,
        String severity,
        String source,
        String reason) {
}
