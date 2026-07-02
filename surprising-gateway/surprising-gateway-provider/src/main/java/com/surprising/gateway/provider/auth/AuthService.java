package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.AuthModels.AuthResponse;
import com.surprising.gateway.provider.auth.AuthModels.AuthenticatedUser;
import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.AuthModels.LoginRequest;
import com.surprising.gateway.provider.auth.AuthModels.RefreshRequest;
import com.surprising.gateway.provider.auth.AuthModels.RegisterRequest;
import com.surprising.gateway.provider.config.GatewayProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final String USERNAME_PATTERN = "^[A-Za-z0-9_]{3,32}$";

    private final GatewayProperties properties;
    private final UserAuthRepository repository;
    private final PasswordHasher passwordHasher;
    private final JwtTokenService jwtTokenService;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthService(GatewayProperties properties,
                       UserAuthRepository repository,
                       PasswordHasher passwordHasher,
                       JwtTokenService jwtTokenService) {
        this.properties = properties;
        this.repository = repository;
        this.passwordHasher = passwordHasher;
        this.jwtTokenService = jwtTokenService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request, HttpServletRequest httpRequest) {
        String username = normalizeUsername(request.username());
        validatePassword(request.password());
        String email = normalizeEmail(request.email());
        Instant now = Instant.now();
        AuthenticatedUser user = repository.createUser(username, email, passwordHasher.hash(request.password()), now);
        repository.ensureDefaultRole(user.userId(), now);
        AuthenticatedUser withRoles = repository.user(user.userId()).orElse(user);
        repository.loginLog(withRoles.userId(), "SUCCESS", "REGISTER", userAgent(httpRequest), ipAddress(httpRequest), now);
        return authResponse(withRoles, httpRequest, now);
    }

    @Transactional
    public AuthResponse login(LoginRequest request, HttpServletRequest httpRequest) {
        String username = normalizeUsername(request.username());
        Instant now = Instant.now();
        UserAuthRepository.UserCredential credential = repository.credentialByUsername(username).orElse(null);
        if (credential == null || !passwordHasher.matches(request.password(), credential.passwordHash())) {
            repository.loginLog(credential == null ? 0L : credential.userId(), "FAILED", "BAD_CREDENTIALS",
                    userAgent(httpRequest), ipAddress(httpRequest), now);
            throw new IllegalArgumentException("invalid username or password");
        }
        if (!"NORMAL".equals(credential.status())) {
            repository.loginLog(credential.userId(), "FAILED", "USER_" + credential.status(),
                    userAgent(httpRequest), ipAddress(httpRequest), now);
            throw new IllegalStateException("user is not active");
        }
        AuthenticatedUser user = repository.user(credential.userId())
                .orElseThrow(() -> new IllegalStateException("user not found"));
        repository.loginLog(user.userId(), "SUCCESS", "LOGIN", userAgent(httpRequest), ipAddress(httpRequest), now);
        return authResponse(user, httpRequest, now);
    }

    @Transactional
    public AuthResponse refresh(RefreshRequest request, HttpServletRequest httpRequest) {
        Instant now = Instant.now();
        String tokenHash = hashRefreshToken(request.refreshToken());
        UserAuthRepository.RefreshSession session = repository.refreshSession(tokenHash)
                .orElseThrow(() -> new IllegalArgumentException("invalid refresh token"));
        if (session.revokedAt() != null || !session.expiresAt().isAfter(now)) {
            throw new IllegalArgumentException("invalid refresh token");
        }
        repository.revokeRefreshSession(session.sessionId(), now);
        AuthenticatedUser user = repository.user(session.userId())
                .orElseThrow(() -> new IllegalStateException("user not found"));
        if (!"NORMAL".equals(user.status())) {
            throw new IllegalStateException("user is not active");
        }
        return authResponse(user, httpRequest, now);
    }

    public JwtPrincipal authenticateBearer(String authorizationHeader) {
        String token = bearerToken(authorizationHeader);
        JwtPrincipal principal = jwtTokenService.verifyAccessToken(token);
        AuthenticatedUser user = repository.user(principal.userId())
                .orElseThrow(() -> new IllegalArgumentException("user not found"));
        if (!"NORMAL".equals(user.status())) {
            throw new IllegalStateException("user is not active");
        }
        return principal;
    }

    private AuthResponse authResponse(AuthenticatedUser user, HttpServletRequest request, Instant now) {
        String accessToken = jwtTokenService.createAccessToken(user.userId(), user.username(), user.roles(), now);
        String refreshToken = newRefreshToken();
        Instant refreshExpiresAt = now.plus(properties.getSecurity().getRefreshTokenTtl());
        repository.saveRefreshSession(user.userId(), hashRefreshToken(refreshToken), refreshExpiresAt,
                userAgent(request), ipAddress(request), now);
        return new AuthResponse(user, accessToken, refreshToken,
                now.plus(properties.getSecurity().getAccessTokenTtl()), refreshExpiresAt);
    }

    private String normalizeUsername(String username) {
        String normalized = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches(USERNAME_PATTERN)) {
            throw new IllegalArgumentException("username must be 3-32 letters, digits or underscore");
        }
        return normalized;
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 128) {
            throw new IllegalArgumentException("password length must be 8-128");
        }
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 254 || !normalized.contains("@")) {
            throw new IllegalArgumentException("invalid email");
        }
        return normalized;
    }

    private String newRefreshToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hashRefreshToken(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to hash refresh token", ex);
        }
    }

    private String bearerToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new IllegalArgumentException("missing bearer token");
        }
        return authorizationHeader.substring(7).trim();
    }

    private String userAgent(HttpServletRequest request) {
        return request == null ? null : truncate(request.getHeader("User-Agent"), 300);
    }

    private String ipAddress(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return truncate(forwarded.split(",")[0].trim(), 80);
        }
        return truncate(request.getRemoteAddr(), 80);
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
