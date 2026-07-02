package com.surprising.gateway.provider.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class AuthModels {

    private AuthModels() {
    }

    public record RegisterRequest(
            @NotBlank @Size(min = 3, max = 32) String username,
            @NotBlank @Size(min = 8, max = 128) String password,
            @Size(max = 254) String email) {
    }

    public record LoginRequest(
            @NotBlank String username,
            @NotBlank String password) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    public record AuthenticatedUser(
            long userId,
            String username,
            String email,
            String status,
            List<String> roles,
            Instant createdAt) {
    }

    public record TokenPair(
            String accessToken,
            String refreshToken,
            Instant accessTokenExpiresAt,
            Instant refreshTokenExpiresAt) {
    }

    public record AuthResponse(
            AuthenticatedUser user,
            String accessToken,
            String refreshToken,
            Instant accessTokenExpiresAt,
            Instant refreshTokenExpiresAt) {
    }

    public record JwtPrincipal(
            long userId,
            String username,
            List<String> roles,
            Instant expiresAt) {
    }
}
