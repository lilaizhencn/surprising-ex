package com.surprising.gateway.provider.auth.model;

import java.time.Instant;

public record AuthChallenge(
            long challengeId,
            long userId,
            String purpose,
            String channel,
            String destination,
            String codeHash,
            Instant expiresAt,
            int attempts,
            Instant consumedAt) {
    }
