package com.surprising.gateway.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.surprising.gateway.provider.config.GatewayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import java.util.Map;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

class CustodyWalletClientTest {

    private final CustodyWalletClient client = new CustodyWalletClient(
            new GatewayProperties(), mock(RestTemplate.class), new ObjectMapper());

    @Test
    void buildsWalletCompatibleCanonicalRequestAndSignature() {
        String canonical = client.canonicalRequest(1_754_320_000L, "nonce-1234567890AB", "post",
                "/custody/api/v1/withdrawals", "{}".getBytes());

        assertThat(canonical).contains("1754320000\nnonce-1234567890AB\nPOST\n/custody/api/v1/withdrawals\n");
        assertThat(client.sign("secret", canonical)).isNotBlank();
    }

    @Test
    void rejectsInvalidWalletSubjectAndIdempotencyKey() {
        assertThatThrownBy(() -> client.subject(0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.createWithdrawal(42L, java.util.Map.of(), "bad"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Idempotency-Key");
    }

    @Test
    void mapsDeterministicWalletHttpRejectionToRejectedException() {
        GatewayProperties properties = new GatewayProperties();
        properties.getCustodyWallet().setEnabled(true);
        properties.getCustodyWallet().setBaseUrl("https://wallet.example.com");
        properties.getCustodyWallet().setApiKey("wallet-key");
        properties.getCustodyWallet().setApiSecret("wallet-secret");
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.badRequest().body(Map.of("error", "rejected")));
        CustodyWalletClient rejectedClient = new CustodyWalletClient(properties, restTemplate, new ObjectMapper());

        assertThatThrownBy(() -> rejectedClient.createWithdrawal(42L, Map.of("amount", "1"), "withdraw-1"))
                .isInstanceOf(CustodyWalletClient.CustodyWalletRejectedException.class)
                .extracting(ex -> ((CustodyWalletClient.CustodyWalletRejectedException) ex).status())
                .isEqualTo(400);
    }

    @Test
    void keepsCustodyServerErrorAsUnknownInsteadOfRejected() {
        GatewayProperties properties = new GatewayProperties();
        properties.getCustodyWallet().setEnabled(true);
        properties.getCustodyWallet().setBaseUrl("https://wallet.example.com");
        properties.getCustodyWallet().setApiKey("wallet-key");
        properties.getCustodyWallet().setApiSecret("wallet-secret");
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.internalServerError().body(Map.of("error", "unknown")));
        CustodyWalletClient unknownClient = new CustodyWalletClient(properties, restTemplate, new ObjectMapper());

        assertThatThrownBy(() -> unknownClient.createWithdrawal(42L, Map.of("amount", "1"), "withdraw-1"))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(CustodyWalletClient.CustodyWalletRejectedException.class);
    }
}
