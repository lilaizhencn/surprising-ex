package com.surprising.gateway.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.gateway.provider.config.GatewayProperties;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

class SpotAccountClientTest {

    @Test
    void sendsPayloadAndSignatureForInternalBalanceAdjustment() {
        GatewayProperties properties = new GatewayProperties();
        GatewayProperties.CustodyWallet wallet = properties.getCustodyWallet();
        wallet.setSpotAccountBaseUrl("http://account:9086/");
        wallet.setSpotAccountInternalSecret("account-internal-secret-for-tests-32");
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));
        SpotAccountClient client = new SpotAccountClient(properties, restTemplate);

        client.adjustBalance(42L, "usdt", 1_250_000L, "deposit:event-1", "custody deposit");

        ArgumentCaptor<HttpEntity> request = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(eq(URI.create("http://account:9086/api/v1/accounts/admin/balance-adjustments")),
                eq(HttpMethod.POST), request.capture(), eq(String.class));
        HttpEntity<?> entity = request.getValue();
        String timestamp = entity.getHeaders().getFirst("X-Internal-Timestamp");
        assertThat(entity.getHeaders().getFirst("X-Internal-Service")).isEqualTo("surprising-gateway");
        assertThat(entity.getHeaders().getFirst("X-Internal-Signature"))
                .isEqualTo(client.signature("account-internal-secret-for-tests-32", Long.parseLong(timestamp),
                        42L, "USDT", 1_250_000L, "deposit:event-1", "custody deposit"));
        assertThat(entity.getBody()).isEqualTo(Map.of("userId", 42L, "asset", "USDT", "amountUnits", 1_250_000L,
                "referenceId", "deposit:event-1", "reason", "custody deposit"));
        assertThat(Math.abs(Instant.now().getEpochSecond() - Long.parseLong(timestamp))).isLessThanOrEqualTo(1L);
    }
}
