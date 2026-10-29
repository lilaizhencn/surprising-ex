package com.surprising.gateway.provider.auth.service;

import com.surprising.gateway.provider.auth.model.AuthPageQuery;
import com.surprising.gateway.provider.auth.model.UserQueryFilter;
import com.surprising.gateway.provider.auth.model.LoginVerificationModels;

import com.surprising.gateway.provider.auth.model.AuthModels;
import com.surprising.gateway.provider.auth.repository.GatewayRefreshSessionRepository;
import com.surprising.gateway.provider.auth.repository.GatewayUserAccessBlockRepository;
import com.surprising.gateway.provider.auth.repository.GatewayUserRepository;
import com.surprising.gateway.provider.auth.model.AuthClientMetadata;
import com.surprising.gateway.provider.auth.model.CreatedAtPageQuery;
import com.surprising.gateway.provider.auth.port.CurrentAuthClient;

import com.surprising.gateway.provider.auth.model.AuthModels.AuthResponse;
import com.surprising.gateway.provider.auth.model.AuthModels.AuthenticatedUser;
import com.surprising.gateway.provider.auth.model.AuthModels.AdminMfaStatusResponse;
import com.surprising.gateway.provider.auth.model.AuthModels.AdminPermissionQueryResponse;
import com.surprising.gateway.provider.auth.model.AuthModels.AdminRefreshSessionQueryResponse;
import com.surprising.gateway.provider.auth.model.AuthModels.AdminRolePermissionsRequest;
import com.surprising.gateway.provider.auth.model.AuthModels.AdminRolePermissionsResponse;
import com.surprising.gateway.provider.auth.model.AuthModels.AdminRoleQueryResponse;
import com.surprising.gateway.provider.auth.model.AuthModels.AdminSessionRevokeResponse;
import com.surprising.gateway.provider.auth.model.AuthModels.AdminUserQueryResponse;
import com.surprising.gateway.provider.auth.model.AuthModels.LoginLogQueryResponse;
import com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.model.AuthModels.LoginRequest;
import com.surprising.gateway.provider.auth.model.AuthModels.EmailVerificationRequest;
import com.surprising.gateway.provider.auth.model.AuthModels.RefreshRequest;
import com.surprising.gateway.provider.auth.model.AuthModels.RegisterRequest;
import com.surprising.gateway.provider.config.GatewayProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import com.surprising.gateway.provider.auth.utils.AuthCodeFormats;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {



    private final com.surprising.affiliate.service.AffiliateService affiliates;
    private final GatewayProperties properties;
    private final AuthPersistenceService repository;
    private final PasswordHasher passwordHasher;
    private final JwtTokenService jwtTokenService;
    private final EmailVerificationService emailVerificationService;
    private final LoginVerificationService loginVerification;
    private final GatewayUserAccessBlockRepository accessBlocks;
    private final CurrentAuthClient currentClient;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthService(GatewayProperties properties,
                       AuthPersistenceService repository,
                       PasswordHasher passwordHasher,
                       JwtTokenService jwtTokenService,
                       EmailVerificationService emailVerificationService,
                       LoginVerificationService loginVerification,
                       GatewayUserAccessBlockRepository accessBlocks, CurrentAuthClient currentClient,
                       com.surprising.affiliate.service.AffiliateService affiliates) {
        this.affiliates = affiliates;
        this.properties = properties;
        this.repository = repository;
        this.passwordHasher = passwordHasher;
        this.jwtTokenService = jwtTokenService;
        this.emailVerificationService = emailVerificationService;
        this.loginVerification = loginVerification;
        this.accessBlocks = accessBlocks;
        this.currentClient = currentClient;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request, AuthClientMetadata httpRequest) {
        String username = request.username();
        String email = request.email();
        String phone = request.phone();
        if (username != null) {
            throw new IllegalArgumentException("username registration is disabled; use email or phone");
        }
        if (email == null && phone == null) {
            throw new IllegalArgumentException("email or phone is required");
        }
        if (email != null && phone != null) {
            throw new IllegalArgumentException("choose email or phone registration");
        }
        if (phone != null && !properties.getSecurity().isPhoneRegistrationEnabled()) {
            throw new IllegalArgumentException("phone registration is not enabled");
        }
        Instant now = Instant.now();
        String passwordHash = passwordHasher.hash(request.password());
        AuthenticatedUser user = phone == null
                ? repository.createUser(username, email, passwordHash, now)
                : repository.createUser(username, email, phone, passwordHash, now);
        affiliates.register(user.userId(), request.inviteCode());
        repository.ensureDefaultRole(user.userId(), now);
        AuthenticatedUser withRoles = repository.user(user.userId()).orElse(user);
        if (email != null && properties.getSecurity().isRequireEmailVerification()) {
            emailVerificationService.issueEmailVerification(user.userId(), email, ipAddress(httpRequest), now);
        }
        repository.loginLog(withRoles.userId(), "SUCCESS", "REGISTER", userAgent(httpRequest), ipAddress(httpRequest), now);
        return authResponse(withRoles, httpRequest, now,
                email != null && properties.getSecurity().isRequireEmailVerification());
    }

    @Transactional(noRollbackFor = LoginVerificationService.VerificationFailure.class)
    public AuthModels.LoginResult login(LoginRequest request, AuthClientMetadata httpRequest) {
        String identifier = request.identifier();
        currentClient.loginAttempt(identifier);
        Instant now = Instant.now();
        GatewayUserRepository.UserCredential credential = credential(identifier).orElse(null);
        if (credential == null || !passwordHasher.matches(request.password(), credential.passwordHash())) {
            repository.loginLog(credential == null ? 0L : credential.userId(), "FAILED", "BAD_CREDENTIALS",
                    userAgent(httpRequest), ipAddress(httpRequest), now);
            throw new IllegalArgumentException("invalid identifier or password");
        }
        if ("FROZEN".equals(credential.status())) {
            repository.loginLog(credential.userId(), "FAILED", "USER_" + credential.status(),
                    userAgent(httpRequest), ipAddress(httpRequest), now);
            throw new IllegalStateException("user is not active");
        }
        AuthenticatedUser user = repository.user(credential.userId())
                .orElseThrow(() -> new IllegalStateException("user not found"));
        requireAccessAllowed(user.userId(), deviceId(httpRequest), ipAddress(httpRequest));
        var challenge = loginVerification.begin(user.userId(), credential.passwordHash(), now);
        if (challenge != null) return challenge;
        repository.loginLog(user.userId(), "SUCCESS", "LOGIN", userAgent(httpRequest), ipAddress(httpRequest), now);
        return authResponse(user, httpRequest, now);
    }

    /** Called only after a server-validated Telegram identity has been consumed. */
    @Transactional(noRollbackFor = LoginVerificationService.VerificationFailure.class)
    public AuthModels.LoginResult loginTelegram(long userId, AuthClientMetadata request) {
        Instant now = Instant.now();
        var user = repository.user(userId).orElseThrow();
        if ("FROZEN".equals(user.status())) throw new IllegalStateException("user is not active");
        requireAccessAllowed(userId, deviceId(request), ipAddress(request));
        var credential = repository.credential(userId).orElseThrow();
        var challenge = loginVerification.begin(userId, credential.passwordHash(), now);
        if (challenge != null) return challenge;
        repository.loginLog(userId, "SUCCESS", "TELEGRAM_LOGIN", userAgent(request), ipAddress(request), now);
        return authResponse(user, request, now);
    }

    @Transactional
    public AuthResponse refresh(RefreshRequest request, AuthClientMetadata httpRequest) {
        Instant now = Instant.now();
        String tokenHash = hashRefreshToken(request.refreshToken());
        GatewayRefreshSessionRepository.RefreshSession session = repository.refreshSession(tokenHash)
                .orElseThrow(() -> new IllegalArgumentException("invalid refresh token"));
        if (session.revokedAt() != null || !session.expiresAt().isAfter(now)) {
            throw new IllegalArgumentException("invalid refresh token");
        }
        if (session.deviceId() != null && !session.deviceId().equals(deviceId(httpRequest))) {
            throw new IllegalArgumentException("refresh token belongs to another device");
        }
        requireAccessAllowed(session.userId(), session.deviceId(), ipAddress(httpRequest));
        if (repository.consumeRefreshSession(session.sessionId(), now) != 1) {
            throw new IllegalArgumentException("invalid refresh token");
        }
        AuthenticatedUser user = repository.user(session.userId())
                .orElseThrow(() -> new IllegalStateException("user not found"));
        if ("FROZEN".equals(user.status())) {
            throw new IllegalStateException("user is not active");
        }
        return authResponse(user, httpRequest, now, false, session.authenticatedAt());
    }

    @Transactional
    public void logout(RefreshRequest request) {
        if (request == null || request.refreshToken() == null || request.refreshToken().isBlank()) {
            return;
        }
        repository.refreshSession(hashRefreshToken(request.refreshToken()))
                .ifPresent(session -> repository.revokeRefreshSession(session.sessionId(), Instant.now()));
    }

    @Transactional
    public boolean verifyEmail(String authorizationHeader, EmailVerificationRequest request) {
        JwtPrincipal principal = authenticateBearer(authorizationHeader);
        AuthenticatedUser user = repository.user(principal.userId())
                .orElseThrow(() -> new IllegalStateException("user not found"));
        if (user.email() == null || !user.email().equalsIgnoreCase(request.email())) {
            throw new IllegalArgumentException("email does not belong to current user");
        }
        return emailVerificationService.verifyEmail(principal.userId(), request.email(), request.code(), Instant.now());
    }

    @Transactional
    public EmailVerificationService.IssuedChallenge resendEmailVerification(String authorizationHeader) {
        JwtPrincipal principal = authenticateBearer(authorizationHeader);
        AuthenticatedUser user = repository.user(principal.userId())
                .orElseThrow(() -> new IllegalStateException("user not found"));
        if (user.email() == null) {
            throw new IllegalArgumentException("current user has no email");
        }
        return emailVerificationService.issueEmailVerification(
                principal.userId(), user.email(), null, Instant.now());
    }

    public JwtPrincipal authenticateBearer(String authorizationHeader) {
        String token = bearerToken(authorizationHeader);
        JwtPrincipal principal = jwtTokenService.verifyAccessToken(token);
        AuthenticatedUser user = repository.user(principal.userId())
                .orElseThrow(() -> new IllegalArgumentException("user not found"));
        if (principal.sessionId() > 0
                && !repository.activeRefreshSession(principal.userId(), principal.sessionId(), Instant.now())) {
            throw new IllegalArgumentException("access session is no longer active");
        }
        if ("FROZEN".equals(user.status())) {
            throw new IllegalStateException("user is not active");
        }
        AuthClientMetadata client = currentClient.current();
        if (client != null) requireAccessAllowed(user.userId(), null, client.ipAddress());
        currentClient.authenticated(user.userId(), null);
        return new JwtPrincipal(user.userId(), user.username(), user.status(), user.roles(), principal.expiresAt(),
                principal.sessionId());
    }

    public JwtPrincipal authenticateAdminBearer(String authorizationHeader) {
        JwtPrincipal principal = authenticateBearer(authorizationHeader);
        if (principal.roles().stream().noneMatch(properties.getSecurity().getAdminRoles()::contains)) {
            throw new IllegalStateException("admin role required");
        }
        return principal;
    }

    /** Only a verified active bearer principal can select this server-side session fact. */
    public Instant primaryAuthenticatedAt(JwtPrincipal principal) {
        return repository.primaryAuthenticatedAt(principal.userId(), principal.sessionId(), Instant.now())
                .orElse(null);
    }

    public JwtPrincipal requireAdminPermission(String authorizationHeader, String permissionCode) {
        JwtPrincipal principal = authenticateAdminBearer(authorizationHeader);
        requireAdminPermission(principal.userId(), principal.roles(), permissionCode);
        return principal;
    }

    public void requireAdminPermission(long userId, List<String> roles, String permissionCode) {
        String normalizedPermission = AuthCodeFormats.permission(permissionCode);
        if (roles != null && roles.contains("SUPER_ADMIN")) {
            return;
        }
        List<String> permissions = repository.permissionsForUser(userId);
        if (matchesPermission(permissions, normalizedPermission)) {
            return;
        }
        throw new IllegalStateException("admin permission required: " + normalizedPermission);
    }


    public AdminUserQueryResponse adminUsers(String authorizationHeader, UserQueryFilter filter,
                                             AuthPageQuery pageQuery) {
        authenticateAdminBearer(authorizationHeader);
        var page = repository.usersPage(filter, pageQuery);
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
                                                                 AuthPageQuery pageQuery) {
        authenticateAdminBearer(authorizationHeader);
        var page = repository.refreshSessionsPage(userId, active, pageQuery);
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
    public AdminSessionRevokeResponse revokeOtherRefreshSessions(String authorizationHeader,
                                                                 String refreshToken) {
        JwtPrincipal principal = authenticateBearer(authorizationHeader);
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new IllegalArgumentException("refreshToken is required");
        }
        var currentSession = repository.refreshSession(hashRefreshToken(refreshToken))
                .orElseThrow(() -> new IllegalArgumentException("refresh session not found"));
        if (currentSession.revokedAt() != null || !currentSession.expiresAt().isAfter(Instant.now())) {
            throw new IllegalArgumentException("refresh session is not active");
        }
        if (currentSession.userId() != principal.userId()) {
            throw new IllegalArgumentException("refresh session does not belong to authenticated user");
        }
        Instant now = Instant.now();
        int revoked = repository.revokeActiveForUserExcept(principal.userId(), currentSession.sessionId(), now);
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

    public LoginLogQueryResponse loginLogs(String authorizationHeader, Long userId, String result, CreatedAtPageQuery query) {
        authenticateAdminBearer(authorizationHeader);
        var page = repository.loginLogPage(userId, result, query);
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
        String normalizedRole = roleCode;
        return new AdminRolePermissionsResponse(normalizedRole, repository.rolePermissions(normalizedRole));
    }

    @Transactional
    public AdminRolePermissionsResponse replaceRolePermissions(String authorizationHeader,
                                                               String roleCode,
                                                               AdminRolePermissionsRequest request) {
        requireAdminPermission(authorizationHeader, "admin.permissions.write");
        String normalizedRole = roleCode;
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

    @Transactional(noRollbackFor = LoginVerificationService.VerificationFailure.class)
    public AuthResponse verifyLogin(LoginVerificationModels.VerifyRequest request, AuthClientMetadata httpRequest) {
        Instant now = Instant.now();
        long userId = loginVerification.verify(request, now);
        AuthenticatedUser user = repository.user(userId).orElseThrow();
        if ("FROZEN".equals(user.status())) throw new IllegalStateException("user is not active");
        requireAccessAllowed(userId, deviceId(httpRequest), ipAddress(httpRequest));
        repository.loginLog(userId, "SUCCESS", "LOGIN", userAgent(httpRequest), ipAddress(httpRequest), now);
        return authResponse(user, httpRequest, now);
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





    private AuthResponse authResponse(AuthenticatedUser user, AuthClientMetadata request, Instant now) {
        return authResponse(user, request, now, false);
    }

    private AuthResponse authResponse(AuthenticatedUser user,
                                      AuthClientMetadata request,
                                      Instant now,
                                      boolean requiresEmailVerification) {
        return authResponse(user, request, now, requiresEmailVerification, now);
    }

    private AuthResponse authResponse(AuthenticatedUser user, AuthClientMetadata request, Instant now,
                                      boolean requiresEmailVerification, Instant authenticatedAt) {
        String refreshToken = newRefreshToken();
        Instant refreshExpiresAt = now.plus(properties.getSecurity().getRefreshTokenTtl());
        String deviceId = deviceId(request);
        requireAccessAllowed(user.userId(), deviceId, ipAddress(request));
        long sessionId = repository.saveRefreshSession(user.userId(), hashRefreshToken(refreshToken), refreshExpiresAt,
                userAgent(request), ipAddress(request), deviceId, now, authenticatedAt);
        String accessToken = sessionId > 0
                ? jwtTokenService.createAccessToken(user.userId(), user.username(), user.roles(), sessionId, now)
                : jwtTokenService.createAccessToken(user.userId(), user.username(), user.roles(), now);
        return new AuthResponse(user, accessToken, refreshToken,
                now.plus(properties.getSecurity().getAccessTokenTtl()), refreshExpiresAt,
                requiresEmailVerification);
    }





    private java.util.Optional<GatewayUserRepository.UserCredential> credential(String identifier) {
        if (identifier.contains("@")) {
            return repository.credentialByEmail(identifier);
        }
        if (identifier.startsWith("+")) {
            return repository.credentialByPhone(identifier);
        }
        return repository.credentialByUsername(identifier);
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

    private String userAgent(AuthClientMetadata metadata) {
        return metadata == null ? null : metadata.userAgent();
    }

    private String ipAddress(AuthClientMetadata metadata) {
        return metadata == null ? null : metadata.ipAddress();
    }

    private String deviceId(AuthClientMetadata metadata) {
        return metadata == null ? null : metadata.deviceId();
    }

    private void requireAccessAllowed(long userId, String deviceId, String ipAddress) {
        if (accessBlocks.blocked(userId, "DEVICE", deviceId) || accessBlocks.blocked(userId, "IP", ipAddress)) {
            throw new IllegalStateException("device or IP is blocked for this account");
        }
    }

    public void requireIpAccessAllowed(long userId, String ipAddress) {
        requireAccessAllowed(userId, null, ipAddress);
    }

    public void requireApiKeyAccess(long userId, String ipAddress, String permission) {
        var user = repository.user(userId).orElseThrow(() -> new IllegalArgumentException("user not found"));
        if ("FROZEN".equals(user.status())) throw new IllegalStateException("user is not active");
        requireIpAccessAllowed(userId, ipAddress);
        requireUserGatewayWrite(user.status(), "TRADE".equals(permission), "WITHDRAW".equals(permission));
    }

    public void recordSecurityChange(long userId, Instant now) {
        repository.recordSecurityChange(userId, now);
    }

    public java.time.Instant withdrawalRestrictedUntil(long userId) {
        return repository.withdrawalRestrictedUntil(userId).orElse(null);
    }


    /** 根据可信账户状态决定能否发起业务写操作；路径/HTTP绑定由Controller提供动作类别。 */
    public void requireUserGatewayWrite(String status, boolean tradingWrite, boolean withdrawalWrite) {
        String normalized = status == null ? "" : status.trim().toUpperCase(java.util.Locale.ROOT);
        if ("TRADE_DISABLED".equals(normalized) && tradingWrite)
            throw new IllegalStateException("user trading is disabled");
        if ("WITHDRAW_DISABLED".equals(normalized) && withdrawalWrite)
            throw new IllegalStateException("user withdrawal is disabled");
    }

}
