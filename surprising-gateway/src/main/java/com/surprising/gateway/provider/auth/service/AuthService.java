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
import com.surprising.gateway.provider.auth.model.AuthModels.RegisterRequest;
import com.surprising.gateway.provider.auth.state.AuthState;
import com.surprising.gateway.provider.auth.state.AuthStateSync;
import com.surprising.gateway.provider.auth.state.SessionState;
import com.surprising.gateway.provider.auth.state.UserState;
import com.surprising.gateway.provider.config.GatewayProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
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
    private final com.surprising.gateway.provider.legal.service.LegalConsentService legal;
    private final AuthState authState;
    private final AuthStateSync authStateSync;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthService(GatewayProperties properties,
                       AuthPersistenceService repository,
                       PasswordHasher passwordHasher,
                       JwtTokenService jwtTokenService,
                       EmailVerificationService emailVerificationService,
                       LoginVerificationService loginVerification,
                       GatewayUserAccessBlockRepository accessBlocks, CurrentAuthClient currentClient,
                       com.surprising.affiliate.service.AffiliateService affiliates,
                       com.surprising.gateway.provider.legal.service.LegalConsentService legal,
                       AuthState authState, AuthStateSync authStateSync) {
        this.authState = authState;
        this.authStateSync = authStateSync;
        this.affiliates = affiliates;
        this.properties = properties;
        this.repository = repository;
        this.passwordHasher = passwordHasher;
        this.jwtTokenService = jwtTokenService;
        this.emailVerificationService = emailVerificationService;
        this.loginVerification = loginVerification;
        this.accessBlocks = accessBlocks;
        this.currentClient = currentClient;
        this.legal = legal;
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
        legal.validateRegistration(httpRequest==null?List.of():httpRequest.acknowledgements());
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
        boolean passwordMatches = passwordHasher.matches(request.password(), credential == null ? null : credential.passwordHash());
        if (credential == null || !passwordMatches) {
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
        return loginIdentity(userId, request, "TELEGRAM_LOGIN");
    }

    /** Called only after Google signature, audience and presentation have been verified. */
    @Transactional(noRollbackFor = LoginVerificationService.VerificationFailure.class)
    public AuthModels.LoginResult loginGoogle(long userId, AuthClientMetadata request) {
        return loginIdentity(userId, request, "GOOGLE_LOGIN");
    }

    private AuthModels.LoginResult loginIdentity(long userId, AuthClientMetadata request, String source) {
        Instant now = Instant.now();
        var user = repository.user(userId).orElseThrow();
        if ("FROZEN".equals(user.status())) throw new IllegalStateException("user is not active");
        requireAccessAllowed(userId, deviceId(request), ipAddress(request));
        var credential = repository.credential(userId).orElseThrow();
        var challenge = loginVerification.begin(userId, credential.passwordHash(), now);
        if (challenge != null) return challenge;
        repository.loginLog(userId, "SUCCESS", source, userAgent(request), ipAddress(request), now);
        return authResponse(user, request, now);
    }

    /** 登出：作废当前登录会话；Postgres 会话记录置为已撤销，Valkey 会话立即删除。 */
    @Transactional
    /** 当前登录用户的资料（读 Valkey 用户状态），不带令牌；会话本身是滑动有效期，没有固定到期时间。 */
    public AuthResponse currentSession(JwtPrincipal principal) {
        UserState user = authState.user(principal.userId());
        if (user == null) throw new IllegalArgumentException("access session is no longer active");
        return new AuthResponse(new AuthenticatedUser(user.userId(), user.username(), user.email(), user.status(),
                user.roles(), user.createdAt(), user.displayName()), null, null);
    }

    public void logout(JwtPrincipal principal) {
        if (principal == null || principal.sessionId() <= 0) return;
        repository.revokeRefreshSession(principal.sessionId(), Instant.now());
        authState.endSessions(List.of(principal.sessionId()));
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
        JwtPrincipal principal = verifyBearer(authorizationHeader);
        currentClient.authenticated(principal.userId(), null);
        return principal;
    }

    /**
     * 登录后的请求校验：JWT 验签 + Valkey 里的会话和用户状态，不访问 Postgres。
     * Valkey 里没有会话/用户就是登录无效（401）。
     */
    public JwtPrincipal verifyBearer(String authorizationHeader) {
        JwtPrincipal cached = currentClient.cachedBearer(authorizationHeader);
        if (cached != null) return cached;
        String token = bearerToken(authorizationHeader);
        JwtPrincipal principal = jwtTokenService.verifySessionToken(token);
        if (principal.sessionId() <= 0) {
            throw new IllegalArgumentException("access token has no session");
        }
        SessionState session = authState.session(principal.sessionId());
        if (session == null || session.userId() != principal.userId()) {
            throw new IllegalArgumentException("access session is no longer active");
        }
        UserState user = authState.user(principal.userId());
        if (user == null) {
            throw new IllegalArgumentException("access session is no longer active");
        }
        if ("FROZEN".equals(user.status())) {
            throw new IllegalStateException("user is not active");
        }
        AuthClientMetadata client = currentClient.current();
        if (client != null) requireStateAccessAllowed(user, null, client.ipAddress());
        JwtPrincipal verified = new JwtPrincipal(user.userId(), user.username(), user.status(), user.roles(),
                principal.expiresAt(), principal.sessionId());
        currentClient.cacheBearer(authorizationHeader, verified);
        return verified;
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
        SessionState session = authState.session(principal.sessionId());
        if (session == null || session.userId() != principal.userId() || session.authenticatedAtMillis() <= 0) {
            return null;
        }
        return Instant.ofEpochMilli(session.authenticatedAtMillis());
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
        UserState user = authState.user(userId);
        List<String> permissions = user == null ? List.of() : user.permissions();
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
        requireAdminPermission(authorizationHeader, "admin.users.read");
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

    /** 踢掉当前用户除本次登录以外的所有会话。 */
    @Transactional
    public AdminSessionRevokeResponse revokeOtherSessions(String authorizationHeader) {
        JwtPrincipal principal = authenticateBearer(authorizationHeader);
        Instant now = Instant.now();
        int revoked = repository.revokeActiveForUserExcept(principal.userId(), principal.sessionId(), now);
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
        repository.revokeUserRefreshSessions(userId, now);
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

    /** 创建登录会话：Postgres 记会话行（设备列表/后台展示用），Valkey 写会话和用户状态，签发登录令牌。 */
    private AuthResponse authResponse(AuthenticatedUser user, AuthClientMetadata request, Instant now,
                                      boolean requiresEmailVerification) {
        boolean admin = user.roles().stream().anyMatch(properties.getSecurity().getAdminRoles()::contains);
        Duration ttl = admin ? properties.getSecurity().getAdminSessionTtl() : properties.getSecurity().getSessionTtl();
        Instant sessionExpiresAt = now.plus(ttl);
        String deviceId = deviceId(request);
        requireAccessAllowed(user.userId(), deviceId, ipAddress(request));
        if(!admin || request==null || !request.administrativeClient())
            legal.recordAndRequire(user.userId(),request==null?List.of():request.acknowledgements(),now);
        long sessionId = repository.saveRefreshSession(user.userId(), randomSessionHash(), sessionExpiresAt,
                userAgent(request), ipAddress(request), deviceId, now, now);
        authStateSync.reloadUser(user.userId());
        authState.startSession(new SessionState(sessionId, user.userId(), deviceId, now.toEpochMilli(),
                ttl.toSeconds(), now.toEpochMilli()), ttl);
        String token = jwtTokenService.createSessionToken(user.userId(), user.username(), user.roles(), sessionId, now);
        return new AuthResponse(user, token, sessionExpiresAt, requiresEmailVerification);
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







    /** 会话行的 token_hash 列必须有值且唯一；登录令牌不落库，这里存一个随机值的摘要。 */
    private String randomSessionHash() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        try {
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
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

    private void requireStateAccessAllowed(UserState user, String deviceId, String ipAddress) {
        if ((deviceId != null && user.blockedDevices().contains(deviceId))
                || (ipAddress != null && user.blockedIps().contains(ipAddress))) {
            throw new IllegalStateException("device or IP is blocked for this account");
        }
    }

    /** 请求路径：按 Valkey 里的用户封禁 IP 判断，不查库。 */
    public void requireIpAccessAllowed(long userId, String ipAddress) {
        UserState user = authState.user(userId);
        if (user == null) throw new IllegalArgumentException("user not found");
        requireStateAccessAllowed(user, null, ipAddress);
    }

    public void requireApiKeyAccess(long userId, String ipAddress, String permission) {
        var user = authState.user(userId);
        if (user == null) throw new IllegalArgumentException("user not found");
        if ("FROZEN".equals(user.status())) throw new IllegalStateException("user is not active");
        requireStateAccessAllowed(user, null, ipAddress);
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
