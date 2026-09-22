package com.surprising.gateway.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.gateway.provider.config.GatewayProperties;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

class SpotAccountClientTest {

    @Test
    void sendsInternalBalanceAdjustmentWithoutCredentials() {
        GatewayProperties properties = new GatewayProperties();
        GatewayProperties.CustodyWallet wallet = properties.getCustodyWallet();
        wallet.setSpotAccountBaseUrl("http://account:9086/");
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));
        SpotAccountClient client = new SpotAccountClient(properties, restTemplate, org.mockito.Mockito.mock(com.surprising.account.provider.service.AccountCommandGateway.class), new com.surprising.account.provider.config.AccountProperties());

        client.adjustBalance(42L, "usdt", 1_250_000L, "deposit:event-1", "custody deposit");

        ArgumentCaptor<HttpEntity> request = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(eq(URI.create("http://account:9086/api/v1/accounts/admin/balance-adjustments")),
                eq(HttpMethod.POST), request.capture(), eq(String.class));
        HttpEntity<?> entity = request.getValue();
        assertThat(entity.getHeaders().toSingleValueMap()).doesNotContainKeys("X-Business-Internal-Token", "X-Internal-Service", "X-Internal-Timestamp", "X-Internal-Signature");
        assertThat(entity.getBody()).isEqualTo(Map.of("userId", 42L, "asset", "USDT", "amountUnits", 1_250_000L,
                "referenceId", "deposit:event-1", "reason", "custody deposit"));
    }
}
