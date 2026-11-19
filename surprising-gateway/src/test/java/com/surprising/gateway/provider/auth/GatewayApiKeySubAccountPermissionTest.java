package com.surprising.gateway.provider.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.repository.GatewayApiKeyRepository;
import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.auth.service.GatewayApiKeyService;
import com.surprising.gateway.provider.auth.service.SensitiveActionVerificationService;
import com.surprising.gateway.provider.auth.service.TotpService;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.subaccount.model.SubAccountModels.SubAccountRecord;
import com.surprising.gateway.provider.subaccount.repository.SubAccountRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GatewayApiKeySubAccountPermissionTest {

    final com.surprising.gateway.provider.auth.state.AuthStateFixture fixture =
            new com.surprising.gateway.provider.auth.state.AuthStateFixture();
    final GatewayApiKeyRepository repository = org.mockito.Mockito.mock(GatewayApiKeyRepository.class);
    final AuthService authService = org.mockito.Mockito.mock(AuthService.class);
    final SensitiveActionVerificationService verification =
            org.mockito.Mockito.mock(SensitiveActionVerificationService.class);
    final SubAccountRepository subAccounts = org.mockito.Mockito.mock(SubAccountRepository.class);
    GatewayApiKeyService service;

    @BeforeEach
    void setUp() {
        service = new GatewayApiKeyService(repository, authService, new TotpService(new GatewayProperties()),
                verification, new GatewayProperties(),
                org.mockito.Mockito.mock(com.surprising.gateway.provider.auth.port.CurrentAuthClient.class),
                fixture.state);
        service.setSubAccountRepository(subAccounts);
        when(authService.authenticateBearer("Bearer parent")).thenReturn(
                new JwtPrincipal(1001L, "parent", "NORMAL", List.of("USER"), Instant.now().plusSeconds(3600), 1L));
        when(verification.verify(eq(1001L), eq("SECURITY_SETTINGS"), any(), any(), any())).thenReturn(true);
    }

    @Test
    void createRejectsTradeKeyForReadOnlySubAccount() {
        when(subAccounts.findRelation(1001L, 2001L)).thenReturn(Optional.of(relation("READONLY", "ACTIVE")));
        assertThatThrownBy(() -> service.create("Bearer parent", "bot", "TRADE", "203.0.113.10",
                2001L, "email", "totp", "127.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("read-only");
        verify(repository, never()).create(any(), any(Long.class), anyString(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void updateCannotEscalateReadOnlySubAccountKeyToTrade() {
        when(subAccounts.listByParent(1001L)).thenReturn(List.of(relation("READONLY", "ACTIVE")));
        when(subAccounts.findRelation(1001L, 2001L)).thenReturn(Optional.of(relation("READONLY", "ACTIVE")));
        when(repository.findActiveForUsers(List.of(1001L, 2001L), "sx_existing"))
                .thenReturn(Optional.of(key(2001L, "sx_existing", "READ")));
        assertThatThrownBy(() -> service.update("Bearer parent", "sx_existing", "bot", "TRADE",
                "203.0.113.10", "email", "totp", "127.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("read-only");
        verify(repository, never()).update(any(Long.class), anyString(), anyString(), anyString(), anyString());
        verify(repository, never()).updateForUsers(anyList(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void updateRejectsFrozenSubAccountKey() {
        when(subAccounts.listByParent(1001L)).thenReturn(List.of(relation("TRADE_SPOT", "FROZEN")));
        when(repository.findActiveForUsers(List.of(1001L, 2001L), "sx_existing"))
                .thenReturn(Optional.of(key(2001L, "sx_existing", "READ")));
        when(subAccounts.findRelation(1001L, 2001L)).thenReturn(Optional.of(relation("TRADE_SPOT", "FROZEN")));
        assertThatThrownBy(() -> service.update("Bearer parent", "sx_existing", "bot", "READ",
                "", "email", "totp", "127.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not active");
        verify(repository, never()).updateForUsers(anyList(), anyString(), anyString(), anyString(), anyString());
    }

    private static SubAccountRecord relation(String permissions, String status) {
        return new SubAccountRecord(1L, 1001L, 2001L, "trader1", permissions, status, Instant.now());
    }

    private static GatewayApiKeyRepository.ApiKeyRecord key(long userId, String apiKey, String permissions) {
        return new GatewayApiKeyRepository.ApiKeyRecord(
                UUID.randomUUID(), userId, apiKey, "cipher", "bot", permissions, "203.0.113.10",
                "ACTIVE", Instant.now(), null);
    }
}
