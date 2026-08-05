package com.surprising.gateway.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.AuthService;
import com.surprising.gateway.provider.config.GatewayProperties;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

class ProductTransferGatewaySurfaceTest {

    @Test
    void postGatewayTransferReturnsCompletedTransferContract() {
        ProductTransferStore store = mock(ProductTransferStore.class);
        ProductAccountClient accountClient = mock(ProductAccountClient.class);
        java.util.concurrent.atomic.AtomicReference<ProductTransferState> pending =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(store.createOrGet(any(ProductTransferCreateRequest.class))).thenAnswer(invocation -> {
            ProductTransferCreateRequest request = invocation.getArgument(0);
            ProductTransferState state = ProductTransferState.pending(321L, request, Instant.now());
            pending.set(state);
            return state;
        });
        when(store.lock(321L)).thenAnswer(invocation -> pending.get());
        when(store.update(any(ProductTransferState.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(accountClient.adjust(anyString(), anyLong(), anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(ProductAccountAdjustment.applied("ok"));
        GatewayProxyService proxy = new GatewayProxyService(properties(), new RestTemplate(), userAuthService(),
                null, null, new ObjectMapper(), new ProductTransferCoordinator(store, accountClient));
        MockHttpServletRequest servletRequest = new MockHttpServletRequest(
                "POST", "/api/v1/gateway/account/transfers");
        servletRequest.addHeader("Authorization", "Bearer user");
        servletRequest.addHeader("Idempotency-Key", "surface-001");
        byte[] body = "{\"sourceAccountType\":\"FUNDING\",\"targetAccountType\":\"USDT_PERPETUAL\","
                .concat("\"asset\":\"USDT\",\"amountUnits\":100,\"referenceId\":\"surface-001\","
                        + "\"reason\":\"surface\"}").getBytes(StandardCharsets.UTF_8);

        ResponseEntity<byte[]> response = proxy.proxy("account", HttpMethod.POST, servletRequest, body);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8))
                .contains("\"transferId\":321")
                .contains("\"status\":\"COMPLETED\"");
    }

    private GatewayProperties properties() {
        GatewayProperties properties = new GatewayProperties();
        properties.setRoutes(java.util.Map.of("account",
                new GatewayProperties.BackendRoute("http://account:9086", "/api/v1/accounts", true)));
        return properties;
    }

    private AuthService userAuthService() {
        AuthService service = mock(AuthService.class);
        when(service.authenticateBearer("Bearer user"))
                .thenReturn(new JwtPrincipal(42L, "user", "NORMAL", List.of("USER"),
                        Instant.now().plusSeconds(60)));
        return service;
    }
}
