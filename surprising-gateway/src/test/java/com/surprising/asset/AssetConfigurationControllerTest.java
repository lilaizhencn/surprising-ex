package com.surprising.asset;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.surprising.asset.controller.AssetConfigurationController;
import com.surprising.asset.service.AssetConfigurationService;
import com.surprising.gateway.provider.auth.AuthService;
import com.surprising.gateway.provider.auth.AdminApprovalService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;

class AssetConfigurationControllerTest {
    @Test
    void authenticationAndApprovalRunBeforeCatalogReadOrMutation() {
        var auth = mock(AuthService.class);
        var approval = mock(AdminApprovalService.class);
        var service = mock(AssetConfigurationService.class);
        var controller = new AssetConfigurationController(auth,service,approval,new ObjectMapper(), mock(com.surprising.gateway.provider.service.WalletFundingService.class));
        when(auth.requireAdminPermission("invalid","admin.wallet.read")).thenThrow(new IllegalArgumentException("unauthorized"));
        when(approval.approvalHeaderName()).thenReturn("X-Admin-Approval-Id");
        when(approval.requireWrite(eq("invalid"),eq("admin.wallet.write"),eq("gateway-admin"),any(),any()))
                .thenThrow(new AdminApprovalService.AdminApprovalRequiredException("admin approval required"));
        assertThatThrownBy(() -> controller.list("invalid",false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.save("invalid",new byte[0],new MockHttpServletRequest()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("428");
        assertThatThrownBy(() -> controller.saveNetwork("invalid",1,new byte[0],new MockHttpServletRequest()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("428");
        verifyNoInteractions(service);
    }

    @Test
    void clientReceivesConflictOnStaleConfiguration() {
        var auth=mock(AuthService.class);
        var service=mock(AssetConfigurationService.class);
        var controller=new AssetConfigurationController(auth,service,mock(AdminApprovalService.class),new ObjectMapper(), mock(com.surprising.gateway.provider.service.WalletFundingService.class));
        when(service.networks(1)).thenThrow(new IllegalStateException("configuration changed"));
        assertThatThrownBy(() -> controller.networks("admin",1))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("409");
        verify(auth).requireAdminPermission("admin","admin.wallet.read");
    }
}
