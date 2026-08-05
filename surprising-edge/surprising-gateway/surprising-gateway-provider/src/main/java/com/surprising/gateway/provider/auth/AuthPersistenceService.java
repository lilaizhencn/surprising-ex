package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.AuthModels.AdminPermissionResponse;
import com.surprising.gateway.provider.auth.AuthModels.AdminRefreshSessionResponse;
import com.surprising.gateway.provider.auth.AuthModels.AdminRoleResponse;
import com.surprising.gateway.provider.auth.AuthModels.AuthenticatedUser;
import com.surprising.gateway.provider.auth.AuthModels.LoginLogResponse;
import com.surprising.gateway.provider.auth.GatewayPermissionRepository.PermissionRecord;
import com.surprising.gateway.provider.auth.GatewayRoleRepository.RoleRecord;
import com.surprising.gateway.provider.auth.GatewayUserRepository.UserCredential;
import com.surprising.gateway.provider.auth.GatewayUserRepository.UserRecord;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 聚合认证域的单表仓储，不直接执行 SQL。
 */
@Service
public class AuthPersistenceService {

    private final GatewayUserRepository userRepository;
    private final GatewayRoleRepository roleRepository;
    private final GatewayPermissionRepository permissionRepository;
    private final GatewayUserRoleRepository userRoleRepository;
    private final GatewayRolePermissionRepository rolePermissionRepository;
    private final GatewayLoginLogRepository loginLogRepository;
    private final GatewayUserMfaRepository mfaRepository;
    private final GatewayRefreshSessionRepository refreshSessionRepository;

    public AuthPersistenceService(GatewayUserRepository userRepository,
                                  GatewayRoleRepository roleRepository,
                                  GatewayPermissionRepository permissionRepository,
                                  GatewayUserRoleRepository userRoleRepository,
                                  GatewayRolePermissionRepository rolePermissionRepository,
                                  GatewayLoginLogRepository loginLogRepository,
                                  GatewayUserMfaRepository mfaRepository,
                                  GatewayRefreshSessionRepository refreshSessionRepository) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
        this.userRoleRepository = userRoleRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.loginLogRepository = loginLogRepository;
        this.mfaRepository = mfaRepository;
        this.refreshSessionRepository = refreshSessionRepository;
    }

    public AuthenticatedUser createUser(String username, String email, String passwordHash, Instant now) {
        return toAuthenticatedUser(userRepository.create(username, email, passwordHash, now), List.of("USER"));
    }

    public AuthenticatedUser createUser(String username, String email, String phone,
                                        String passwordHash, Instant now) {
        return toAuthenticatedUser(userRepository.create(username, email, phone, passwordHash, now), List.of("USER"));
    }

    public Optional<UserCredential> credentialByUsername(String username) {
        return userRepository.findCredentialByUsername(username);
    }

    public Optional<UserCredential> credentialByEmail(String email) {
        return userRepository.findCredentialByEmail(email);
    }

    public Optional<UserCredential> credentialByPhone(String phone) {
        return userRepository.findCredentialByPhone(phone);
    }

    public Optional<UserCredential> credential(long userId) {
        return userRepository.findCredentialByUserId(userId);
    }

    public Optional<AuthenticatedUser> user(long userId) {
        return userRepository.find(userId).map(user -> toAuthenticatedUser(user, roles(userId)));
    }

    public List<String> roles(long userId) {
        List<Long> roleIds = userRoleRepository.findRoleIds(userId);
        return roleCodes(roleIds);
    }

    public void ensureDefaultRole(long userId, Instant now) {
        roleRepository.ensure("USER", "Standard user", now);
        userRoleRepository.add(userId, roleRepository.requireByCode("USER").roleId(), now);
    }

    public List<AuthenticatedUser> users(String query, String status, int limit) {
        return attachRoles(userRepository.find(query, status, limit));
    }

    public AdminCursorPage.CursorPage<AuthenticatedUser> usersPage(String query,
                                                                   String status,
                                                                   int limit,
                                                                   String cursor,
                                                                   String sort) {
        AdminCursorPage.CursorPage<UserRecord> page = userRepository.findPage(query, status, limit, cursor, sort);
        return new AdminCursorPage.CursorPage<>(
                attachRoles(page.items()),
                page.nextCursor(),
                page.hasMore(),
                page.sort(),
                page.limit());
    }

    public Optional<AuthenticatedUser> updateStatus(long userId, String status, Instant now) {
        return userRepository.updateStatus(userId, status, now)
                .map(user -> toAuthenticatedUser(user, roles(userId)));
    }

    public void replaceRoles(long userId, List<String> roleCodes, Instant now) {
        List<String> normalizedRoles = normalizeRoleCodes(roleCodes);
        List<Long> roleIds = new ArrayList<>();
        for (String roleCode : normalizedRoles) {
            roleRepository.ensure(roleCode, roleName(roleCode), now);
            roleIds.add(roleRepository.requireByCode(roleCode).roleId());
        }
        userRoleRepository.replace(userId, roleIds, now);
    }

    public AdminCursorPage.CursorPage<LoginLogResponse> loginLogPage(Long userId,
                                                                     String result,
                                                                     int limit,
                                                                     String cursor,
                                                                     String sort) {
        return loginLogRepository.findPage(userId, result, limit, cursor, sort);
    }

    public List<AdminRoleResponse> roleSummaries() {
        List<RoleRecord> roles = roleRepository.findAll();
        Map<Long, Integer> counts = rolePermissionRepository.countByRoleIds(
                roles.stream().map(RoleRecord::roleId).toList());
        return roles.stream()
                .map(role -> new AdminRoleResponse(
                        role.roleCode(),
                        role.roleName(),
                        counts.getOrDefault(role.roleId(), 0),
                        role.createdAt()))
                .toList();
    }

    public List<AdminPermissionResponse> permissions() {
        return permissionRepository.findAllResponses();
    }

    public List<String> rolePermissions(String roleCode) {
        RoleRecord role = roleRepository.requireByCode(roleCode);
        return permissionCodes(rolePermissionRepository.findPermissionIds(role.roleId()));
    }

    public void replaceRolePermissions(String roleCode, List<String> permissionCodes, Instant now) {
        RoleRecord role = roleRepository.requireByCode(roleCode);
        List<String> normalizedPermissions = normalizePermissionCodes(permissionCodes);
        Map<String, PermissionRecord> permissions = permissionRepository.findByCodes(normalizedPermissions);
        for (String permissionCode : normalizedPermissions) {
            if (!permissions.containsKey(permissionCode)) {
                throw new IllegalArgumentException("unknown permission: " + permissionCode);
            }
        }
        rolePermissionRepository.replace(
                role.roleId(),
                normalizedPermissions.stream().map(code -> permissions.get(code).permissionId()).toList(),
                now);
    }

    public List<String> permissionsForUser(long userId) {
        List<Long> roleIds = userRoleRepository.findRoleIds(userId);
        return permissionCodes(rolePermissionRepository.findPermissionIdsByRoleIds(roleIds));
    }

    public Optional<GatewayUserMfaRepository.MfaCredential> mfaCredential(long userId) {
        return mfaRepository.find(userId);
    }

    public void upsertMfaSecret(long userId, String secretCiphertext, Instant now) {
        mfaRepository.upsertSecret(userId, secretCiphertext, now);
    }

    public void enableMfa(long userId, Instant now) {
        mfaRepository.enable(userId, now);
    }

    public void disableMfa(long userId, Instant now) {
        mfaRepository.disable(userId, now);
    }

    public void saveRefreshSession(long userId,
                                   String tokenHash,
                                   Instant expiresAt,
                                   String userAgent,
                                   String ipAddress,
                                   Instant now) {
        refreshSessionRepository.save(userId, tokenHash, expiresAt, userAgent, ipAddress, now);
    }

    public Optional<GatewayRefreshSessionRepository.RefreshSession> refreshSession(String tokenHash) {
        return refreshSessionRepository.find(tokenHash);
    }

    public void revokeRefreshSession(long sessionId, Instant now) {
        refreshSessionRepository.revoke(sessionId, now);
    }

    public int consumeRefreshSession(long sessionId, Instant now) {
        return refreshSessionRepository.consume(sessionId, now);
    }

    public List<AdminRefreshSessionResponse> refreshSessions(Long userId, Boolean active, int limit) {
        return refreshSessionRepository.find(userId, active, limit);
    }

    public AdminCursorPage.CursorPage<AdminRefreshSessionResponse> refreshSessionsPage(Long userId,
                                                                                      Boolean active,
                                                                                      int limit,
                                                                                      String cursor,
                                                                                      String sort) {
        return refreshSessionRepository.findPage(userId, active, limit, cursor, sort);
    }

    public int revokeRefreshSessionForAdmin(long sessionId, Instant now) {
        return refreshSessionRepository.revokeActive(sessionId, now);
    }

    public int revokeUserRefreshSessions(long userId, Instant now) {
        return refreshSessionRepository.revokeActiveForUser(userId, now);
    }

    public int updatePasswordHash(long userId, String passwordHash, Instant now) {
        return userRepository.updatePasswordHash(userId, passwordHash, now);
    }

    public void loginLog(long userId, String result, String reason, String userAgent, String ipAddress, Instant now) {
        loginLogRepository.append(userId, result, reason, userAgent, ipAddress, now);
    }

    private List<AuthenticatedUser> attachRoles(List<UserRecord> users) {
        if (users.isEmpty()) {
            return List.of();
        }
        List<Long> userIds = users.stream().map(UserRecord::userId).toList();
        Map<Long, List<Long>> roleIdsByUser = userRoleRepository.findRoleIdsByUserIds(userIds);
        LinkedHashSet<Long> allRoleIds = new LinkedHashSet<>();
        roleIdsByUser.values().forEach(allRoleIds::addAll);
        Map<Long, RoleRecord> rolesById = roleRepository.findByIds(allRoleIds);
        return users.stream()
                .map(user -> toAuthenticatedUser(
                        user,
                        roleCodes(roleIdsByUser.getOrDefault(user.userId(), List.of()), rolesById)))
                .toList();
    }

    private List<String> roleCodes(Collection<Long> roleIds) {
        return roleCodes(roleIds, roleRepository.findByIds(roleIds));
    }

    private List<String> roleCodes(Collection<Long> roleIds, Map<Long, RoleRecord> rolesById) {
        List<String> roles = roleIds.stream()
                .map(rolesById::get)
                .filter(java.util.Objects::nonNull)
                .map(RoleRecord::roleCode)
                .sorted()
                .toList();
        return roles.isEmpty() ? List.of("USER") : roles;
    }

    private List<String> permissionCodes(Collection<Long> permissionIds) {
        Map<Long, PermissionRecord> permissionsById = permissionRepository.findByIds(permissionIds);
        return permissionIds.stream()
                .map(permissionsById::get)
                .filter(java.util.Objects::nonNull)
                .map(PermissionRecord::permissionCode)
                .distinct()
                .sorted()
                .toList();
    }

    private AuthenticatedUser toAuthenticatedUser(UserRecord user, List<String> roles) {
        return new AuthenticatedUser(
                user.userId(),
                user.username(),
                user.email(),
                user.status(),
                roles,
                user.createdAt());
    }

    private List<String> normalizeRoleCodes(List<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            throw new IllegalArgumentException("roles are required");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        roleCodes.forEach(roleCode -> normalized.add(GatewayRoleRepository.normalizeRoleCode(roleCode)));
        return List.copyOf(normalized);
    }

    private List<String> normalizePermissionCodes(List<String> permissionCodes) {
        if (permissionCodes == null) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        permissionCodes.forEach(permissionCode ->
                normalized.add(GatewayPermissionRepository.normalizePermissionCode(permissionCode)));
        return List.copyOf(normalized);
    }

    private String roleName(String roleCode) {
        return roleCode.replace('_', ' ');
    }
}
