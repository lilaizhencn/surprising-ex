package com.surprising.gateway.provider.auth.model;

import java.time.Instant;

/** 登录结果：accessToken 是登录令牌（JWT），有效性由 Valkey 会话决定；浏览器登录时令牌只放在 Cookie 里，body 里为空。 */
public record AuthResponse(
        AuthenticatedUser user,
        String accessToken,
        Instant sessionExpiresAt,
        boolean requiresEmailVerification,
        boolean browserSession,
        OperatingAccount operatingAccount) implements LoginResult {
    public AuthResponse(AuthenticatedUser user, String accessToken, Instant sessionExpiresAt) {
        this(user, accessToken, sessionExpiresAt, false, false, null);
    }
    public AuthResponse(AuthenticatedUser user, String accessToken, Instant sessionExpiresAt,
                        boolean requiresEmailVerification) {
        this(user, accessToken, sessionExpiresAt, requiresEmailVerification, false, null);
    }
    public AuthResponse(AuthenticatedUser user, String accessToken, Instant sessionExpiresAt,
                        boolean requiresEmailVerification, boolean browserSession) {
        this(user, accessToken, sessionExpiresAt, requiresEmailVerification, browserSession, null);
    }
}
