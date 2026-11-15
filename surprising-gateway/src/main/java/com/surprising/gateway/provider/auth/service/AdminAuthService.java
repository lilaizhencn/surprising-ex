package com.surprising.gateway.provider.auth.service;

import com.surprising.gateway.provider.auth.model.AuthModels.AdminMfaStatusResponse;
import com.surprising.gateway.provider.auth.model.AuthModels.AdminPermissionQueryResponse;
import com.surprising.gateway.provider.auth.model.AuthModels.AdminRefreshSessionQueryResponse;
import com.surprising.gateway.provider.auth.model.AuthModels.AdminRolePermissionsRequest;
import com.surprising.gateway.provider.auth.model.AuthModels.AdminRolePermissionsResponse;
import com.surprising.gateway.provider.auth.model.AuthModels.AdminRoleQueryResponse;
import com.surprising.gateway.provider.auth.model.AuthModels.AdminSessionRevokeResponse;
import com.surprising.gateway.provider.auth.model.AuthModels.AdminUserQueryResponse;
import com.surprising.gateway.provider.auth.model.AuthPageQuery;
import com.surprising.gateway.provider.auth.model.AuthModels.AuthenticatedUser;
import com.surprising.gateway.provider.auth.model.CreatedAtPageQuery;
import com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.model.AuthModels.LoginLogQueryResponse;
import com.surprising.gateway.provider.auth.model.UserQueryFilter;
import com.surprising.gateway.provider.config.GatewayProperties;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Administrative user, role, permission, session, and login-log management operations.
 */
@Service
public class AdminAuthService {

    private final GatewayProperties properties;
    private final AuthPersistenceService repository;
    private final AuthService authService;

    public AdminAuthService(GatewayProperties properties,
                            AuthPersistenceService repository,
                            AuthService authService) {
        this.properties = properties;
        this.repository = repository;
        this.authService = authService;
    }

    public AdminUserQueryResponse adminUsers(String authorizationHeader, UserQueryFilter filter,
                                             AuthPageQuery pageQuery) {
        authService.authenticateAdminBearer(authorizationHeader);
        var page = repository.usersPage(filter, pageQuery);
        return new AdminUserQueryResponse(page.items().size(), page.items(), page.nextCursor(),
                page.hasMore(), page.sort(), page.limit());
    }

    public AuthenticatedUser adminUser(String authorizationHeader, long userId) {
        authService.requireAdminPermission(authorizationHeader, "admin.users.read");
        return repository.user(userId).orElseThrow(() -> new IllegalArgumentException("user not found"));
    }

    public AdminRefreshSessionQueryResponse adminRefreshSessions(String authorizationHeader,
                                                                 Long userId,
                                                                 Boolean active,
                                                                 AuthPageQuery pageQuery) {
        authService.authenticateAdminBearer(authorizationHeader);
        var page = repository.refreshSessionsPage(userId, active, pageQuery);
        return new AdminRefreshSessionQueryResponse(page.items().size(), page.items(), page.nextCursor(),
                page.hasMore(), page.sort(), page.limit());
    }

    @Transactional
    public AdminSessionRevokeResponse revokeRefreshSession(String authorizationHeader, long sessionId) {
        authService.authenticateAdminBearer(authorizationHeader);
        Instant now = Instant.now();
        int revoked = repository.revokeRefreshSessionForAdmin(sessionId, now);
        return new AdminSessionRevokeResponse(revoked, now);
    }

    @Transactional
    public AdminSessionRevokeResponse revokeUserRefreshSessions(String authorizationHeader, long userId) {
        authService.authenticateAdminBearer(authorizationHeader);
        repository.user(userId).orElseThrow(() -> new IllegalArgumentException("user not found"));
        Instant now = Instant.now();
        int revoked = repository.revokeUserRefreshSessions(userId, now);
        return new AdminSessionRevokeResponse(revoked, now);
    }

    @Transactional
    public AuthenticatedUser updateUserStatus(String authorizationHeader, long userId, String status) {
        authService.authenticateAdminBearer(authorizationHeader);
        return repository.updateStatus(userId, status, Instant.now())
                .orElseThrow(() -> new IllegalArgumentException("user not found"));
    }

    @Transactional
    public AuthenticatedUser replaceUserRoles(String authorizationHeader, long userId, List<String> roles) {
        JwtPrincipal admin = authService.authenticateAdminBearer(authorizationHeader);
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
        authService.authenticateAdminBearer(authorizationHeader);
        var page = repository.loginLogPage(userId, result, query);
        return new LoginLogQueryResponse(page.items().size(), page.items(),
                page.nextCursor(), page.hasMore(), page.sort(), page.limit());
    }

    public AdminRoleQueryResponse adminRoles(String authorizationHeader) {
        authService.requireAdminPermission(authorizationHeader, "admin.permissions.read");
        var roles = repository.roleSummaries();
        return new AdminRoleQueryResponse(roles.size(), roles);
    }

    public AdminPermissionQueryResponse adminPermissions(String authorizationHeader) {
        authService.requireAdminPermission(authorizationHeader, "admin.permissions.read");
        var permissions = repository.permissions();
        return new AdminPermissionQueryResponse(permissions.size(), permissions);
    }

    public AdminRolePermissionsResponse adminRolePermissions(String authorizationHeader, String roleCode) {
        authService.requireAdminPermission(authorizationHeader, "admin.permissions.read");
        return new AdminRolePermissionsResponse(roleCode, repository.rolePermissions(roleCode));
    }

    @Transactional
    public AdminRolePermissionsResponse replaceRolePermissions(String authorizationHeader,
                                                               String roleCode,
                                                               AdminRolePermissionsRequest request) {
        authService.requireAdminPermission(authorizationHeader, "admin.permissions.write");
        if ("SUPER_ADMIN".equals(roleCode)) {
            throw new IllegalArgumentException("SUPER_ADMIN permissions are managed by system policy");
        }
        Instant now = Instant.now();
        repository.replaceRolePermissions(roleCode, request == null ? List.of() : request.permissions(), now);
        return new AdminRolePermissionsResponse(roleCode, repository.rolePermissions(roleCode));
    }

    public AdminMfaStatusResponse adminMfaStatus(String authorizationHeader) {
        JwtPrincipal principal = authService.authenticateAdminBearer(authorizationHeader);
        return repository.mfaCredential(principal.userId())
                .map(credential -> new AdminMfaStatusResponse(credential.enabled(), credential.verifiedAt()))
                .orElseGet(() -> new AdminMfaStatusResponse(false, null));
    }

    public JwtPrincipal requireAdminPermission(String authorizationHeader, String permission) {
        return authService.requireAdminPermission(authorizationHeader, permission);
    }
}
