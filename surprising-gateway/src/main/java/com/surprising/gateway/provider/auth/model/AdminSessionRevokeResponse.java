package com.surprising.gateway.provider.auth.model;

import java.time.Instant;

public record AdminSessionRevokeResponse(
        int revoked,
        Instant revokedAt) {
}
