package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.AuthModels.AuthResponse;
import com.surprising.gateway.provider.auth.AuthModels.AuthenticatedUser;
import com.surprising.gateway.provider.auth.AuthModels.AdminMfaEnrollmentResponse;
import com.surprising.gateway.provider.auth.AuthModels.AdminMfaStatusResponse;
import com.surprising.gateway.provider.auth.AuthModels.AdminMfaVerificationRequest;
import com.surprising.gateway.provider.auth.AuthModels.AdminPermissionQueryResponse;
import com.surprising.gateway.provider.auth.AuthModels.AdminRefreshSessionQueryResponse;
import com.surprising.gateway.provider.auth.AuthModels.AdminRolePermissionsRequest;
import com.surprising.gateway.provider.auth.AuthModels.AdminRolePermissionsResponse;
import com.surprising.gateway.provider.auth.AuthModels.AdminRoleQueryResponse;
import com.surprising.gateway.provider.auth.AuthModels.AdminSessionRevokeResponse;
import com.surprising.gateway.provider.auth.AuthModels.AdminUserQueryResponse;
import com.surprising.gateway.provider.auth.AuthModels.LoginLogQueryResponse;
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
import java.util.List;
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
    private final TotpService totpService;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthService(GatewayProperties properties,
                       UserAuthRepository repository,
                       PasswordHasher passwordHasher,
                       JwtTokenService jwtTokenService,
                       TotpService totpService) {
        this.properties = properties;
        this.repository = repository;
        this.passwordHasher = passwordHasher;
        this.jwtTokenService = jwtTokenService;
        this.totpService = totpService;
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
        if ("FROZEN".equals(credential.status())) {
            repository.loginLog(credential.userId(), "FAILED", "USER_" + credential.status(),
                    userAgent(httpRequest), ipAddress(httpRequest), now);
            throw new IllegalStateException("user is not active");
        }
        AuthenticatedUser user = repository.user(credential.userId())
                .orElseThrow(() -> new IllegalStateException("user not found"));
        enforceAdminMfa(user, request.totpCode(), httpRequest, now);
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
        if ("FROZEN".equals(user.status())) {
            throw new IllegalStateException("user is not active");
        }
        return authResponse(user, httpRequest, now);
    }

    public JwtPrincipal authenticateBearer(String authorizationHeader) {
        String token = bearerToken(authorizationHeader);
        JwtPrincipal principal = jwtTokenService.verifyAccessToken(token);
        AuthenticatedUser user = repository.user(principal.userId())
                .orElseThrow(() -> new IllegalArgumentException("user not found"));
        if ("FROZEN".equals(user.status())) {
            throw new IllegalStateException("user is not active");
        }
        return new JwtPrincipal(user.userId(), user.username(), user.status(), user.roles(), principal.expiresAt());
    }

    public JwtPrincipal authenticateAdminBearer(String authorizationHeader) {
        JwtPrincipal principal = authenticateBearer(authorizationHeader);
        if (principal.roles().stream().noneMatch(properties.getSecurity().getAdminRoles()::contains)) {
            throw new IllegalStateException("admin role required");
        }
        return principal;
    }

    public JwtPrincipal requireAdminPermission(String authorizationHeader, String permissionCode) {
        JwtPrincipal principal = authenticateAdminBearer(authorizationHeader);
        requireAdminPermission(principal.userId(), principal.roles(), permissionCode);
        return principal;
    }

    public void requireAdminPermission(long userId, List<String> roles, String permissionCode) {
        String normalizedPermission = normalizePermission(permissionCode);
        if (roles != null && roles.contains("SUPER_ADMIN")) {
            return;
        }
        List<String> permissions = repository.permissionsForUser(userId);
        if (matchesPermission(permissions, normalizedPermission)) {
            return;
        }
        throw new IllegalStateException("admin permission required: " + normalizedPermission);
    }

    public AdminUserQueryResponse adminUsers(String authorizationHeader, String query, String status, int limit) {
        authenticateAdminBearer(authorizationHeader);
        var users = repository.users(query, status, limit);
        return new AdminUserQueryResponse(users.size(), users);
    }

    public AdminUserQueryResponse adminUsers(String authorizationHeader,
                                             String query,
                                             String status,
                                             int limit,
                                             String cursor,
                                             String sort) {
        authenticateAdminBearer(authorizationHeader);
        var page = repository.usersPage(query, status, limit, cursor, sort);
        return new AdminUserQueryResponse(page.items().size(), page.items(), page.nextCursor(),
                page.hasMore(), page.sort(), page.limit());
    }

    public AuthenticatedUser adminUser(String authorizationHeader, long userId) {
        authenticateAdminBearer(authorizationHeader);
        return repository.user(userId).orElseThrow(() -> new IllegalArgumentException("user not found"));
    }

    public AdminRefreshSessionQueryResponse adminRefreshSessions(String authorizationHeader,
                                                                 Long userId,
                                                                 Boolean active,
                                                                 int limit) {
        authenticateAdminBearer(authorizationHeader);
        var sessions = repository.refreshSessions(userId, active, limit);
        return new AdminRefreshSessionQueryResponse(sessions.size(), sessions);
    }

    public AdminRefreshSessionQueryResponse adminRefreshSessions(String authorizationHeader,
                                                                 Long userId,
                                                                 Boolean active,
                                                                 int limit,
                                                                 String cursor,
                                                                 String sort) {
        authenticateAdminBearer(authorizationHeader);
        var page = repository.refreshSessionsPage(userId, active, limit, cursor, sort);
        return new AdminRefreshSessionQueryResponse(page.items().size(), page.items(), page.nextCursor(),
                page.hasMore(), page.sort(), page.limit());
    }

    @Transactional
    public AdminSessionRevokeResponse revokeRefreshSession(String authorizationHeader, long sessionId) {
        authenticateAdminBearer(authorizationHeader);
        Instant now = Instant.now();
        int revoked = repository.revokeRefreshSessionForAdmin(sessionId, now);
        return new AdminSessionRevokeResponse(revoked, now);
    }

    @Transactional
    public AdminSessionRevokeResponse revokeUserRefreshSessions(String authorizationHeader, long userId) {
        authenticateAdminBearer(authorizationHeader);
        repository.user(userId).orElseThrow(() -> new IllegalArgumentException("user not found"));
        Instant now = Instant.now();
        int revoked = repository.revokeUserRefreshSessions(userId, now);
        return new AdminSessionRevokeResponse(revoked, now);
    }

    @Transactional
    public AuthenticatedUser updateUserStatus(String authorizationHeader, long userId, String status) {
        authenticateAdminBearer(authorizationHeader);
        return repository.updateStatus(userId, status, Instant.now())
                .orElseThrow(() -> new IllegalArgumentException("user not found"));
    }

    @Transactional
    public AuthenticatedUser replaceUserRoles(String authorizationHeader, long userId, List<String> roles) {
        JwtPrincipal admin = authenticateAdminBearer(authorizationHeader);
        if (roles == null || roles.isEmpty()) {
            throw new IllegalArgumentException("roles are required");
        }
        repository.user(userId).orElseThrow(() -> new IllegalArgumentException("user not found"));
        if (admin.userId() == userId && roles.stream().noneMatch(properties.getSecurity().getAdminRoles()::contains)) {
            throw new IllegalArgumentException("cannot remove own admin role");
        }
        Instant now = Instant.now();
        repository.replaceRoles(userId, roles, now);
        return repository.user(userId).orElseThrow(() -> new IllegalArgumentException("user not found"));
    }

    public LoginLogQueryResponse loginLogs(String authorizationHeader, Long userId, String result, int limit) {
        return loginLogs(authorizationHeader, userId, result, limit, null, null);
    }

    public LoginLogQueryResponse loginLogs(String authorizationHeader,
                                           Long userId,
                                           String result,
                                           int limit,
                                           String cursor,
                                           String sort) {
        authenticateAdminBearer(authorizationHeader);
        var page = repository.loginLogPage(userId, result, limit, cursor, sort);
        return new LoginLogQueryResponse(page.items().size(), page.items(),
                page.nextCursor(), page.hasMore(), page.sort(), page.limit());
    }

    public AdminRoleQueryResponse adminRoles(String authorizationHeader) {
        requireAdminPermission(authorizationHeader, "admin.permissions.read");
        var roles = repository.roleSummaries();
        return new AdminRoleQueryResponse(roles.size(), roles);
    }

    public AdminPermissionQueryResponse adminPermissions(String authorizationHeader) {
        requireAdminPermission(authorizationHeader, "admin.permissions.read");
        var permissions = repository.permissions();
        return new AdminPermissionQueryResponse(permissions.size(), permissions);
    }

    public AdminRolePermissionsResponse adminRolePermissions(String authorizationHeader, String roleCode) {
        requireAdminPermission(authorizationHeader, "admin.permissions.read");
        String normalizedRole = normalizeRole(roleCode);
        return new AdminRolePermissionsResponse(normalizedRole, repository.rolePermissions(normalizedRole));
    }

    @Transactional
    public AdminRolePermissionsResponse replaceRolePermissions(String authorizationHeader,
                                                               String roleCode,
                                                               AdminRolePermissionsRequest request) {
        requireAdminPermission(authorizationHeader, "admin.permissions.write");
        String normalizedRole = normalizeRole(roleCode);
        if ("SUPER_ADMIN".equals(normalizedRole)) {
            throw new IllegalArgumentException("SUPER_ADMIN permissions are managed by system policy");
        }
        Instant now = Instant.now();
        repository.replaceRolePermissions(normalizedRole, request == null ? List.of() : request.permissions(), now);
        return new AdminRolePermissionsResponse(normalizedRole, repository.rolePermissions(normalizedRole));
    }

    public AdminMfaStatusResponse adminMfaStatus(String authorizationHeader) {
        JwtPrincipal principal = authenticateAdminBearer(authorizationHeader);
        return repository.mfaCredential(principal.userId())
                .map(credential -> new AdminMfaStatusResponse(credential.enabled(), credential.verifiedAt()))
                .orElseGet(() -> new AdminMfaStatusResponse(false, null));
    }

    @Transactional
    public AdminMfaEnrollmentResponse enrollAdminMfa(String authorizationHeader) {
        JwtPrincipal principal = authenticateAdminBearer(authorizationHeader);
        Instant now = Instant.now();
        String secret = totpService.newSecret();
        repository.upsertMfaSecret(principal.userId(), totpService.encryptSecret(secret), now);
        return new AdminMfaEnrollmentResponse(
                false,
                secret,
                totpService.provisioningUri(principal.username(), secret),
                now);
    }

    @Transactional
    public AdminMfaStatusResponse confirmAdminMfa(String authorizationHeader, AdminMfaVerificationRequest request) {
        JwtPrincipal principal = authenticateAdminBearer(authorizationHeader);
        UserAuthRepository.MfaCredential credential = repository.mfaCredential(principal.userId())
                .orElseThrow(() -> new IllegalArgumentException("mfa enrollment not found"));
        String secret = totpService.decryptSecret(credential.totpSecretCiphertext());
        if (!totpService.verify(secret, request == null ? null : request.totpCode(), Instant.now())) {
            throw new IllegalArgumentException("invalid totp code");
        }
        Instant now = Instant.now();
        repository.enableMfa(principal.userId(), now);
        return new AdminMfaStatusResponse(true, now);
    }

    @Transactional
    public AdminMfaStatusResponse disableAdminMfa(String authorizationHeader, AdminMfaVerificationRequest request) {
        JwtPrincipal principal = authenticateAdminBearer(authorizationHeader);
        var credential = repository.mfaCredential(principal.userId()).orElse(null);
        if (credential != null && credential.enabled()) {
            String secret = totpService.decryptSecret(credential.totpSecretCiphertext());
            if (!totpService.verify(secret, request == null ? null : request.totpCode(), Instant.now())) {
                throw new IllegalArgumentException("invalid totp code");
            }
        }
        repository.disableMfa(principal.userId(), Instant.now());
        return new AdminMfaStatusResponse(false, null);
    }

    private void enforceAdminMfa(AuthenticatedUser user,
                                 String totpCode,
                                 HttpServletRequest httpRequest,
                                 Instant now) {
        if (!isAdmin(user.roles())) {
            return;
        }
        var credential = repository.mfaCredential(user.userId()).orElse(null);
        if (credential != null && credential.enabled()) {
            String secret = totpService.decryptSecret(credential.totpSecretCiphertext());
            if (!totpService.verify(secret, totpCode, now)) {
                repository.loginLog(user.userId(), "FAILED", "MFA_INVALID",
                        userAgent(httpRequest), ipAddress(httpRequest), now);
                throw new IllegalArgumentException("invalid or missing totp code");
            }
            return;
        }
        if (properties.getSecurity().isRequireAdminMfa()) {
            repository.loginLog(user.userId(), "FAILED", "MFA_NOT_ENROLLED",
                    userAgent(httpRequest), ipAddress(httpRequest), now);
            throw new IllegalStateException("admin mfa enrollment required");
        }
    }

    private boolean isAdmin(List<String> roles) {
        return roles != null && roles.stream().anyMatch(properties.getSecurity().getAdminRoles()::contains);
    }

    private boolean matchesPermission(List<String> permissions, String required) {
        if (permissions == null || permissions.isEmpty()) {
            return false;
        }
        if (permissions.contains("*") || permissions.contains(required)) {
            return true;
        }
        for (String permission : permissions) {
            if (permission != null && permission.contains("*") && wildcardPermissionMatches(permission, required)) {
                return true;
            }
        }
        String current = required;
        while (current.contains(".")) {
            current = current.substring(0, current.lastIndexOf('.'));
            if (permissions.contains(current + ".*")) {
                return true;
            }
        }
        return false;
    }

    private boolean wildcardPermissionMatches(String pattern, String required) {
        StringBuilder regex = new StringBuilder();
        for (char item : pattern.toCharArray()) {
            if (item == '*') {
                regex.append("[a-z0-9._-]*");
            } else if (Character.isLetterOrDigit(item) || item == '_') {
                regex.append(item);
            } else {
                regex.append('\\').append(item);
            }
        }
        return required.matches(regex.toString());
    }

    private String normalizePermission(String permissionCode) {
        String normalized = permissionCode == null ? "" : permissionCode.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9*][a-z0-9.*_-]{1,127}")) {
            throw new IllegalArgumentException("invalid permission code");
        }
        return normalized;
    }

    private String normalizeRole(String roleCode) {
        String normalized = roleCode == null ? "" : roleCode.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9_]{2,64}")) {
            throw new IllegalArgumentException("invalid role code");
        }
        return normalized;
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
