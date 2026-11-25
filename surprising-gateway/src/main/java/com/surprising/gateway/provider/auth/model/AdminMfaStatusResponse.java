package com.surprising.gateway.provider.auth.model;

import java.time.Instant;

public record AdminMfaStatusResponse(
        boolean enabled,
        Instant verifiedAt) {
}
