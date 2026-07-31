package com.surprising.gateway.provider.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.gateway.provider.auth.GatewayPermissionRepository.PermissionRecord;
import com.surprising.gateway.provider.auth.GatewayRoleRepository.RoleRecord;
import com.surprising.gateway.provider.auth.GatewayUserRepository.UserRecord;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuthPersistenceServiceTest {

    private final GatewayUserRepository userRepository = mock(GatewayUserRepository.class);
    private final GatewayRoleRepository roleRepository = mock(GatewayRoleRepository.class);
    private final GatewayPermissionRepository permissionRepository = mock(GatewayPermissionRepository.class);
    private final GatewayUserRoleRepository userRoleRepository = mock(GatewayUserRoleRepository.class);
    private final GatewayRolePermissionRepository rolePermissionRepository =
            mock(GatewayRolePermissionRepository.class);
    private final GatewayLoginLogRepository loginLogRepository = mock(GatewayLoginLogRepository.class);
    private final GatewayUserMfaRepository mfaRepository = mock(GatewayUserMfaRepository.class);
    private final GatewayRefreshSessionRepository refreshSessionRepository =
            mock(GatewayRefreshSessionRepository.class);

    private AuthPersistenceService service;

    @BeforeEach
    void setUp() {
        service = new AuthPersistenceService(
                userRepository,
                roleRepository,
                permissionRepository,
                userRoleRepository,
                rolePermissionRepository,
                loginLogRepository,
                mfaRepository,
                refreshSessionRepository);
    }

    @Test
    void usersAttachRolesWithBatchQueries() {
        Instant now = Instant.parse("2026-07-31T00:00:00Z");
        List<UserRecord> users = List.of(
                new UserRecord(1L, "admin", "admin@example.com", "NORMAL", now),
                new UserRecord(2L, "alice", "alice@example.com", "NORMAL", now));
        when(userRepository.find(null, null, 100)).thenReturn(users);
        when(userRoleRepository.findRoleIdsByUserIds(List.of(1L, 2L))).thenReturn(Map.of(1L, List.of(10L)));
        when(roleRepository.findByIds(java.util.Set.of(10L))).thenReturn(Map.of(
                10L, new RoleRecord(10L, "ADMIN", "Admin", now)));

        var result = service.users(null, null, 100);

        assertThat(result.get(0).roles()).containsExactly("ADMIN");
        assertThat(result.get(1).roles()).containsExactly("USER");
        verify(userRoleRepository).findRoleIdsByUserIds(List.of(1L, 2L));
    }

    @Test
    void permissionsForUserAreAggregatedAndSorted() {
        when(userRoleRepository.findRoleIds(7L)).thenReturn(List.of(10L, 11L));
        when(rolePermissionRepository.findPermissionIdsByRoleIds(List.of(10L, 11L)))
                .thenReturn(List.of(30L, 20L, 30L));
        when(permissionRepository.findByIds(List.of(30L, 20L, 30L))).thenReturn(Map.of(
                20L, new PermissionRecord(20L, "admin.audit.read"),
                30L, new PermissionRecord(30L, "admin.users.read")));

        assertThat(service.permissionsForUser(7L))
                .containsExactly("admin.audit.read", "admin.users.read");
    }

    @Test
    void replaceRolePermissionsRejectsUnknownPermissionBeforeWritingLinkTable() {
        Instant now = Instant.parse("2026-07-31T00:00:00Z");
        RoleRecord role = new RoleRecord(10L, "ADMIN", "Admin", now);
        when(roleRepository.requireByCode("ADMIN")).thenReturn(role);
        when(permissionRepository.findByCodes(List.of("admin.users.read", "admin.unknown")))
                .thenReturn(Map.of(
                        "admin.users.read",
                        new PermissionRecord(20L, "admin.users.read")));

        assertThatThrownBy(() -> service.replaceRolePermissions(
                "ADMIN", List.of("admin.users.read", "admin.unknown"), now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unknown permission: admin.unknown");

        verify(rolePermissionRepository, never()).replace(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.<Collection<Long>>any(),
                org.mockito.ArgumentMatchers.any());
    }
}
