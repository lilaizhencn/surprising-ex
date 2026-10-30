package com.surprising.gateway.provider.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.surprising.gateway.provider.config.GatewayProperties;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.core.ParameterizedTypeReference;
import java.util.Map;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

class CustodyWalletClientTest {

    @Test
    void historyRequiresTheExactAuthenticatedUserSubject() {
        GatewayProperties properties = new GatewayProperties();
        properties.getCustodyWallet().setEnabled(true);
        properties.getCustodyWallet().setBaseUrl("https://wallet.example.test");
        properties.getCustodyWallet().setApiKey("test-key");
        properties.getCustodyWallet().setApiSecret("test-only-secret");
        RestTemplate rest = mock(RestTemplate.class);
        Map<String, Object> own = Map.of("subject", "user:42", "amount", "1");
        when(rest.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class),
                any(ParameterizedTypeReference.class))).thenReturn(ResponseEntity.ok(List.of(
                        own, Map.of("subject", "user:420", "amount", "99"), Map.of("amount", "5"))));
        CustodyWalletClient client = new CustodyWalletClient(properties, rest, new ObjectMapper());

        assertThat(client.deposits(42L, "ETH", "USDT", 50)).containsExactly(own);
        assertThat(client.withdrawals(42L, "ETH", "USDT", 50)).containsExactly(own);
    }

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
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.badRequest().body(Map.of("error", "rejected")));
        CustodyWalletClient rejectedClient = new CustodyWalletClient(properties, restTemplate, new ObjectMapper());

        assertThatThrownBy(() -> rejectedClient.createWithdrawal(42L, Map.of("amount", "1"), "withdraw-1"))
                .isInstanceOf(IllegalStateException.class).isNotInstanceOf(CustodyWalletClient.CustodyWalletRejectedException.class);
    }

    @Test
    void invalidState409WithoutAPermanentReceiptRemainsUnknown() {
        GatewayProperties properties = new GatewayProperties();
        properties.getCustodyWallet().setEnabled(true);
        properties.getCustodyWallet().setBaseUrl("https://wallet.example.com");
        properties.getCustodyWallet().setApiKey("wallet-key");
        properties.getCustodyWallet().setApiSecret("wallet-secret");
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.status(409).body(Map.of("error", Map.of(
                        "code", "INVALID_STATE", "message", "insufficient gas balance"))));
        CustodyWalletClient rejectedClient = new CustodyWalletClient(properties, restTemplate, new ObjectMapper());

        assertThatThrownBy(() -> rejectedClient.createWithdrawal(42L,Map.of("amount","1"),"withdraw-1"))
                .isInstanceOf(IllegalStateException.class).isNotInstanceOf(CustodyWalletClient.CustodyWalletRejectedException.class);
    }

    @Test
    void keepsCustodyServerErrorAsUnknownInsteadOfRejected() {
        GatewayProperties properties = new GatewayProperties();
        properties.getCustodyWallet().setEnabled(true);
        properties.getCustodyWallet().setBaseUrl("https://wallet.example.com");
        properties.getCustodyWallet().setApiKey("wallet-key");
        properties.getCustodyWallet().setApiSecret("wallet-secret");
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.internalServerError().body(Map.of("error", "unknown")));
        CustodyWalletClient unknownClient = new CustodyWalletClient(properties, restTemplate, new ObjectMapper());

        assertThatThrownBy(() -> unknownClient.createWithdrawal(42L, Map.of("amount", "1"), "withdraw-1"))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(CustodyWalletClient.CustodyWalletRejectedException.class);
    }

    @Test
    void keepsAmbiguousClientErrorsAsUnknownInsteadOfRefundableRejections() {
        GatewayProperties properties = new GatewayProperties();
        properties.getCustodyWallet().setEnabled(true);
        properties.getCustodyWallet().setBaseUrl("https://wallet.example.com");
        properties.getCustodyWallet().setApiKey("wallet-key");
        properties.getCustodyWallet().setApiSecret("wallet-secret");
        RestTemplate restTemplate = mock(RestTemplate.class);
        CustodyWalletClient unknownClient = new CustodyWalletClient(properties, restTemplate, new ObjectMapper());
        for (int status : List.of(401, 403, 409, 429)) {
            when(restTemplate.exchange(any(URI.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(ResponseEntity.status(status).body(Map.of("error", "ambiguous")));
            assertThatThrownBy(() -> unknownClient.createWithdrawal(42L, Map.of("amount", "1"), "withdraw-1"))
                    .isInstanceOf(IllegalStateException.class)
                    .isNotInstanceOf(CustodyWalletClient.CustodyWalletRejectedException.class);
        }
    }

    @Test
    void businessInvalidStateIsNotRefundEvidence() {
        GatewayProperties properties = new GatewayProperties();
        properties.getCustodyWallet().setEnabled(true);
        properties.getCustodyWallet().setBaseUrl("https://wallet.example.com");
        properties.getCustodyWallet().setApiKey("wallet-key");
        properties.getCustodyWallet().setApiSecret("wallet-secret");
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new org.springframework.web.client.HttpClientErrorException(
                        org.springframework.http.HttpStatus.CONFLICT, "Conflict", """
                        {"error":{"code":"INVALID_STATE","message":"insufficient gas balance"}}
                        """.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        java.nio.charset.StandardCharsets.UTF_8));
        CustodyWalletClient rejectedClient = new CustodyWalletClient(properties, restTemplate, new ObjectMapper());

        assertThatThrownBy(() -> rejectedClient.createWithdrawal(42L, Map.of("amount", "1"), "withdraw-1"))
                .isInstanceOf(IllegalStateException.class).isNotInstanceOf(CustodyWalletClient.CustodyWalletRejectedException.class);
    }

    @Test
    void keepsWalletIdempotencyProcessing409AsUnknown() {
        GatewayProperties properties = new GatewayProperties();
        properties.getCustodyWallet().setEnabled(true);
        properties.getCustodyWallet().setBaseUrl("https://wallet.example.com");
        properties.getCustodyWallet().setApiKey("wallet-key");
        properties.getCustodyWallet().setApiSecret("wallet-secret");
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new org.springframework.web.client.HttpClientErrorException(
                        org.springframework.http.HttpStatus.CONFLICT, "Conflict", """
                        {"error":{"code":"WITHDRAWAL_ALREADY_PROCESSING","message":"still processing"}}
                        """.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        java.nio.charset.StandardCharsets.UTF_8));
        CustodyWalletClient unknownClient = new CustodyWalletClient(properties, restTemplate, new ObjectMapper());

        assertThatThrownBy(() -> unknownClient.createWithdrawal(42L, Map.of("amount", "1"), "withdraw-1"))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(CustodyWalletClient.CustodyWalletRejectedException.class);
    }




}
