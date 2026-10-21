package com.surprising.gateway.provider.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.AuthService;
import com.surprising.gateway.provider.product.GatewayProductServices;
import com.surprising.gateway.provider.service.GatewayProductSettings;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

class AdminProductLinesControllerTest {
    private final GatewayProductSettings settings=mock(GatewayProductSettings.class);
    private final AuthService auth=mock(AuthService.class);
    private final JwtPrincipal admin=new JwtPrincipal(42,"qa", "ACTIVE",List.of("ADMIN"),Instant.now().plusSeconds(60));
    private final AdminProductLinesController controller=new AdminProductLinesController(auth,settings,mock(GatewayProductServices.class));
    private final JsonMapper json=JsonMapper.builder().build();

    @Test void rejectsMalformedParametersBeforeBusinessChanges() {
        when(auth.requireAdminPermission("token","admin.gateway.instrument-admin.write")).thenReturn(admin);
        for (String value : List.of("null", "{}", "{\"expectedVersion\":\"1\",\"reason\":\"x\"}",
                "{\"expectedVersion\":1.5,\"reason\":\"x\"}", "{\"expectedVersion\":1,\"reason\":2}",
                "{\"expectedVersion\":1,\"reason\":\"x\",\"enabled\":false}",
                "{\"expectedVersion\":9223372036854775808,\"reason\":\"x\"}",
                "{\"expectedVersion\":0,\"reason\":\"x\"}", "{\"expectedVersion\":1,\"reason\":\" \"}",
                json.writeValueAsString(java.util.Map.of("expectedVersion",1,"reason","x".repeat(1001)))))
            assertThatThrownBy(()->controller.enable("token",ProductLine.SPOT,json.readTree(value)))
                    .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
        verifyNoInteractions(settings);
    }

    @Test void normalizesReasonAndMapsOnlyVersionConflict() {
        when(auth.requireAdminPermission("token","admin.gateway.instrument-admin.write")).thenReturn(admin);
        var request=json.readTree("{\"expectedVersion\":1,\"reason\":\"  启用  \"}");
        controller.enable("token",ProductLine.SPOT,request);
        verify(settings).enable(ProductLine.SPOT,1,"启用",admin);
        doThrow(new OptimisticLockingFailureException("changed")).when(settings).enable(ProductLine.SPOT,1,"启用",admin);
        assertThatThrownBy(()->controller.enable("token",ProductLine.SPOT,request)).hasMessageContaining("409");
        doThrow(new IllegalStateException("audit unavailable")).when(settings).enable(ProductLine.SPOT,1,"启用",admin);
        assertThatThrownBy(()->controller.enable("token",ProductLine.SPOT,request))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("audit unavailable");
    }
}
