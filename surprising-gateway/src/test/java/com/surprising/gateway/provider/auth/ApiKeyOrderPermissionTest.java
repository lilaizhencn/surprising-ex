package com.surprising.gateway.provider.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.surprising.gateway.provider.auth.repository.GatewayApiKeyRepository;
import com.surprising.gateway.provider.auth.service.*;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.controller.BinanceApiController;
import com.surprising.gateway.provider.controller.GatewayProxyController;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;

class ApiKeyOrderPermissionTest {
    final String key = "sx_" + "k".repeat(24);
    final GatewayProperties properties = new GatewayProperties();
    final TotpService totp = new TotpService(properties);
    final GatewayApiKeyRepository repository = mock(GatewayApiKeyRepository.class);
    final AuthService auth = mock(AuthService.class);
    final GatewayApiKeyService keys = new GatewayApiKeyService(repository, auth, totp, null, properties, org.mockito.Mockito.mock(com.surprising.gateway.provider.auth.port.CurrentAuthClient.class));
    final GatewayProxyController proxy = mock(GatewayProxyController.class);

    @Test void readOnlyKeyCannotEnterTheCancellationBusiness() {
        record("READ");
        var response = controller().handle(request(), null);
        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verifyNoInteractions(proxy);
        verify(repository, never()).markUsed(any(), any());
    }

    @Test void tradeKeyCancellationUsesTheInternalPostContract() {
        record("READ,TRADE");
        when(proxy.proxyCompat(eq("trading"), eq("/cancel"), isNull(), eq(HttpMethod.POST), any(), any(), eq(42L)))
                .thenReturn(ResponseEntity.status(409).body("conflict".getBytes(StandardCharsets.UTF_8)));
        assertThat(controller().handle(request(), null).getStatusCode().value()).isEqualTo(409);
        verify(proxy).proxyCompat(eq("trading"), eq("/cancel"), isNull(), eq(HttpMethod.POST), any(), any(), eq(42L));
    }

    private void record(String permissions) {
        var scale = new GatewayProperties.SymbolScale();
        properties.getBinanceApi().setSymbolScales(Map.of("BTCUSDT", scale));
        properties.getBinanceApi().setSymbolAliases(Map.of("SPOT:BTCUSDT", "1"));
        when(repository.active(key)).thenReturn(Optional.of(new GatewayApiKeyRepository.ApiKeyRecord(
                UUID.randomUUID(), 42L, key, totp.encryptSecret("secret"), "bot", permissions,
                "203.0.113.7", "ACTIVE", Instant.now(), null, null)));
    }

    private MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("DELETE", "/api/v3/order");
        request.setRemoteAddr("203.0.113.7"); request.addHeader("X-MBX-APIKEY", key);
        String query = "symbol=BTCUSDT&orderId=1&timestamp=" + System.currentTimeMillis();
        request.setQueryString(query + "&signature=" + keys.sign("secret", query));
        return request;
    }

    private BinanceApiController controller() {
        return new BinanceApiController(properties, proxy, keys, null, auth, null, null, null, new ObjectMapper());
    }
}
