package com.surprising.gateway.provider.auth.service;

import com.surprising.gateway.provider.auth.model.AuthPageQuery;
import com.surprising.gateway.provider.auth.model.UserQueryFilter;
import com.surprising.gateway.provider.auth.model.LoginVerificationModels;

import com.surprising.gateway.provider.auth.model.AuthModels;
import com.surprising.gateway.provider.auth.repository.LoginSessionRepository;
import com.surprising.gateway.provider.auth.repository.GatewayUserAccessBlockRepository;
import com.surprising.gateway.provider.auth.repository.GatewayUserRepository;
import com.surprising.gateway.provider.auth.model.AuthClientMetadata;
import com.surprising.gateway.provider.auth.model.CreatedAtPageQuery;
import com.surprising.gateway.provider.auth.port.CurrentAuthClient;

import com.surprising.gateway.provider.auth.model.AuthModels.AuthResponse;
import com.surprising.gateway.provider.auth.model.AuthModels.AuthenticatedUser;
import com.surprising.gateway.provider.auth.model.AuthModels.AdminMfaStatusResponse;
import com.surprising.gateway.provider.auth.model.AuthModels.AdminPermissionQueryResponse;
import com.surprising.gateway.provider.auth.model.AuthModels.AdminSessionQueryResponse;
import com.surprising.gateway.provider.auth.model.AuthModels.AdminRolePermissionsRequest;
import com.surprising.gateway.provider.auth.model.AuthModels.AdminRolePermissionsResponse;
import com.surprising.gateway.provider.auth.model.AuthModels.AdminRoleQueryResponse;
import com.surprising.gateway.provider.auth.model.AuthModels.AdminSessionRevokeResponse;
import com.surprising.gateway.provider.auth.model.AuthModels.AdminUserQueryResponse;
import com.surprising.gateway.provider.auth.model.AuthModels.LoginLogQueryResponse;
import com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.model.AuthModels.OperatingAccount;
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
import com.surprising.gateway.provider.auth.utils.AuthCodeFormats;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
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

    /** 当前登录用户的资料（读 Valkey 用户状态），不带令牌；会话本身是滑动有效期，没有固定到期时间。 */
    public AuthResponse currentSession(JwtPrincipal principal) {
        UserState user = authState.user(principal.userId());
        if (user == null) throw new IllegalArgumentException("access session is no longer active");
        long operatingUserId = principal.userId();
        if (principal.sessionId() > 0) {
            SessionState session = authState.session(principal.sessionId());
            if (session == null || session.userId() != principal.userId()) {
                throw new IllegalArgumentException("access session is no longer active");
            }
            operatingUserId = restoreOperatingAccount(session);
        }
        return sessionAuthResponse(user, operatingUserId);
    }

    /**
     * 在当前主账号登录会话内切换查看/交易账户。传主账号 ID 或空表示切回主账号。
     * 允许切到已冻结或只读的子账号以便查看；写操作仍走 {@link #requireSubAccountWrite(long)}。
     */
    public AuthResponse switchOperatingAccount(JwtPrincipal principal, Long requestedUserId) {
        if (principal == null || principal.sessionId() <= 0) {
            throw new IllegalArgumentException("access session is no longer active");
        }
        SessionState session = authState.session(principal.sessionId());
        if (session == null || session.userId() != principal.userId()) {
            throw new IllegalArgumentException("access session is no longer active");
        }
        long loginUserId = principal.userId();
        long target = requestedUserId == null || requestedUserId <= 0 ? loginUserId : requestedUserId;
        if (target != loginUserId) {
            UserState operating = ownedOperatingAccount(loginUserId, target, true);
            if (operating == null) {
                throw new IllegalStateException("operating account is not available for this user");
            }
            if ("FROZEN".equals(operating.status())) {
                throw new IllegalStateException("user is not active");
            }
        }
        authState.replaceSession(session.withOperatingUserId(target), Duration.ofSeconds(session.ttlSeconds()));
        UserState user = authState.user(loginUserId);
        if (user == null) throw new IllegalArgumentException("access session is no longer active");
        return sessionAuthResponse(user, target);
    }

    /** 登出：作废当前登录会话；Valkey 会话立即删除，Postgres 仅追加操作记录。 */
    @Transactional
    public void logout(JwtPrincipal principal) {
        if (principal == null || principal.sessionId() <= 0) return;
        repository.revokeSession(principal.sessionId(), Instant.now());

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
        var existing = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (existing != null && existing.isAuthenticated()
                && existing.getPrincipal() instanceof JwtPrincipal verifiedPrincipal
                && authorizationHeader != null && authorizationHeader.equals(existing.getCredentials())) {
            return verifiedPrincipal;
        }
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
        requireStateAccessAllowed(user, session.deviceId(), client == null ? null : client.ipAddress());
        long operatingUserId = resolveOperatingUserId(session, user.userId());
        JwtPrincipal verified = new JwtPrincipal(user.userId(), user.username(), user.status(), user.roles(),
                principal.expiresAt(), principal.sessionId(), operatingUserId);
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


    /** 踢掉当前用户除本次登录以外的所有会话。 */
    @Transactional
    public AdminSessionRevokeResponse revokeOtherSessions(String authorizationHeader) {
        JwtPrincipal principal = authenticateBearer(authorizationHeader);
        Instant now = Instant.now();
        int revoked = repository.revokeActiveForUserExcept(principal.userId(), principal.sessionId(), now);
        return new AdminSessionRevokeResponse(revoked, now);
    }

    public GatewayProperties properties() {
        return properties;
    }

    public AuthPersistenceService persistence() {
        return repository;
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
        int pi = 0;
        int ri = 0;
        int starIdx = -1;
        int matchIdx = 0;
        int pLen = pattern.length();
        int rLen = required.length();
        while (ri < rLen) {
            if (pi < pLen && pattern.charAt(pi) == required.charAt(ri) && pattern.charAt(pi) != '*') {
                pi++;
                ri++;
            } else if (pi < pLen && pattern.charAt(pi) == '*') {
                starIdx = pi++;
                matchIdx = ri;
            } else if (starIdx != -1 && isPermissionTokenChar(required.charAt(matchIdx))) {
                pi = starIdx + 1;
                ri = ++matchIdx;
            } else {
                return false;
            }
        }
        while (pi < pLen && pattern.charAt(pi) == '*') {
            pi++;
        }
        return pi == pLen;
    }

    private static boolean isPermissionTokenChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
    }





    private AuthResponse authResponse(AuthenticatedUser user, AuthClientMetadata request, Instant now) {
        return authResponse(user, request, now, false);
    }

    /** 创建登录会话：Valkey 写会话、设备资料及用户状态，签发登录令牌。 */
    private AuthResponse authResponse(AuthenticatedUser user, AuthClientMetadata request, Instant now,
                                      boolean requiresEmailVerification) {
        boolean admin = user.roles().stream().anyMatch(properties.getSecurity().getAdminRoles()::contains);
        Duration ttl = admin ? properties.getSecurity().getAdminSessionTtl() : properties.getSecurity().getSessionTtl();
        Instant sessionExpiresAt = now.plus(ttl);
        String deviceId = deviceId(request);
        requireAccessAllowed(user.userId(), deviceId, ipAddress(request));
        if(!admin || request==null || !request.administrativeClient())
            legal.recordAndRequire(user.userId(),request==null?List.of():request.acknowledgements(),now);
        authStateSync.reloadUser(user.userId());
        long sessionId = repository.createSession(user.userId(), ttl, userAgent(request), ipAddress(request), deviceId, now);
        String token = jwtTokenService.createSessionToken(user.userId(), user.username(), user.roles(), sessionId, now);
        UserState published = authState.user(user.userId());
        return new AuthResponse(user, token, sessionExpiresAt, requiresEmailVerification, false,
                operatingAccount(published != null ? published : null, user, true));
    }

    private AuthResponse sessionAuthResponse(UserState loginUser, long operatingUserId) {
        AuthenticatedUser user = new AuthenticatedUser(loginUser.userId(), loginUser.username(), loginUser.email(),
                loginUser.status(), loginUser.roles(), loginUser.createdAt(), loginUser.displayName());
        boolean parent = operatingUserId == loginUser.userId();
        UserState operating = parent ? loginUser : authState.user(operatingUserId);
        return new AuthResponse(user, null, null, false, false, operatingAccount(operating, user, parent));
    }

    private OperatingAccount operatingAccount(UserState operating, AuthenticatedUser fallback, boolean parent) {
        if (operating != null) {
            return new OperatingAccount(operating.userId(), operating.username(), operating.displayName(),
                    operating.status(), operating.subAccountStatus(), operating.subAccountPermissions(), parent);
        }
        if (fallback == null || !parent) return null;
        return new OperatingAccount(fallback.userId(), fallback.username(), fallback.displayName(),
                fallback.status(), null, null, true);
    }

    /** 请求路径只读 Valkey：操作账户必须是本主账号的子账号，否则拒绝，不回落到主账号下单。 */
    private long resolveOperatingUserId(SessionState session, long loginUserId) {
        long operatingUserId = session.effectiveOperatingUserId();
        if (operatingUserId == loginUserId) return loginUserId;
        UserState operating = ownedOperatingAccount(loginUserId, operatingUserId, false);
        if (operating == null) {
            throw new IllegalStateException("operating account is no longer available");
        }
        if ("FROZEN".equals(operating.status())) {
            throw new IllegalStateException("user is not active");
        }
        return operatingUserId;
    }

    /** 刷新会话时：无法确认的操作账户切回主账号，避免页面一直卡在失效选择。 */
    private long restoreOperatingAccount(SessionState session) {
        long operatingUserId = session.effectiveOperatingUserId();
        if (operatingUserId == session.userId()) return operatingUserId;
        UserState operating = ownedOperatingAccount(session.userId(), operatingUserId, true);
        if (operating != null && !"FROZEN".equals(operating.status())) return operatingUserId;
        authState.replaceSession(session.withOperatingUserId(session.userId()), Duration.ofSeconds(session.ttlSeconds()));
        return session.userId();
    }

    private UserState ownedOperatingAccount(long loginUserId, long operatingUserId, boolean mayReload) {
        UserState operating = authState.user(operatingUserId);
        if (!ownedSubAccount(loginUserId, operating) && mayReload && authStateSync != null) {
            authStateSync.reloadUser(operatingUserId);
            operating = authState.user(operatingUserId);
        }
        return ownedSubAccount(loginUserId, operating) ? operating : null;
    }

    private static boolean ownedSubAccount(long loginUserId, UserState operating) {
        return operating != null
                && operating.subAccountParentUserId() != null
                && operating.subAccountParentUserId() == loginUserId;
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
        requireSubAccountWrite(user, "TRADE".equals(permission) || "WITHDRAW".equals(permission));
    }

    /** 请求路径：按 Valkey 中已发布的子账号关系状态判断交易/划转，不回源数据库。 */
    public void requireSubAccountWrite(long userId) {
        var user = authState.user(userId);
        if (user == null) throw new IllegalArgumentException("user not found");
        requireSubAccountWrite(user, true);
    }

    void requireSubAccountWrite(UserState user, boolean write) {
        if (!write || user == null) return;
        if (user.subAccountFrozen()) throw new IllegalStateException("sub-account is not active");
        if (user.subAccountReadOnly()) throw new IllegalStateException("sub-account is read-only");
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
