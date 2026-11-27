package com.surprising.gateway.provider.auth.model;

import java.time.Instant;

public record KycProviderResult(String providerReference, String externalUserId, String eventKey,
                                String status, String rejectionReason, Instant occurredAt, String levelName,
                                Instant evidenceExpiresAt) {}
