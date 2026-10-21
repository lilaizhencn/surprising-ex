package com.surprising.gateway.provider.auth.model;

import java.time.Instant;

/** 登录因子和挑战的原生持久化投影；Factor字段顺序也是既有指纹的一部分。 */
public final class LoginVerificationRows {
    private LoginVerificationRows() { }
    public record Factor(
            String method,
            String destination,
            boolean enabled,
            Instant verifiedAt,
            Instant updatedAt) {}

    public record Challenge(
            long userId,
            String purpose,
            String tokenHash,
            String fingerprint,
            String methods,
            String emailHash,
            String phoneHash,
            String destination,
            Instant expiresAt,
            boolean consumed,
            int attempts,
            boolean targetEnabled) {}
}
