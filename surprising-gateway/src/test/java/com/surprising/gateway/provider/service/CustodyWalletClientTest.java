package com.surprising.gateway.provider.service;

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
                .isInstanceOf(CustodyWalletClient.CustodyWalletRejectedException.class)
                .extracting(ex -> ((CustodyWalletClient.CustodyWalletRejectedException) ex).responseBody())
                .asString().contains("rejected");
    }

    @Test
    void retainsResponseBodyAndClassifiesInvalidState409WhenRestTemplateReturnsErrorEntity() {
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

        assertThatThrownBy(() -> rejectedClient.createWithdrawal(42L, Map.of("amount", "1"), "withdraw-1"))
                .isInstanceOf(CustodyWalletClient.CustodyWalletRejectedException.class)
                .satisfies(ex -> {
                    var rejection = (CustodyWalletClient.CustodyWalletRejectedException) ex;
                    assertThat(rejection.status()).isEqualTo(409);
                    assertThat(rejection.responseBody()).contains("INVALID_STATE", "insufficient gas balance");
                });
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
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.status(409).body(Map.of("error", "duplicate")));
        CustodyWalletClient unknownClient = new CustodyWalletClient(properties, restTemplate, new ObjectMapper());

        assertThatThrownBy(() -> unknownClient.createWithdrawal(42L, Map.of("amount", "1"), "withdraw-1"))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(CustodyWalletClient.CustodyWalletRejectedException.class);
    }

    @Test
    void mapsWalletBusinessInvalidState409ToDeterministicRejection() {
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
                .isInstanceOf(CustodyWalletClient.CustodyWalletRejectedException.class)
                .extracting(ex -> ((CustodyWalletClient.CustodyWalletRejectedException) ex).status())
                .isEqualTo(409);
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

    @Test
    void signsTheExactEncodedAddressSearchUriWithoutDoubleEncoding() {
        GatewayProperties properties = new GatewayProperties();
        properties.getCustodyWallet().setEnabled(true);
        properties.getCustodyWallet().setBaseUrl("https://wallet.example.com");
        properties.getCustodyWallet().setApiKey("wallet-key");
        properties.getCustodyWallet().setApiSecret("wallet-secret");
        RestTemplate restTemplate = mock(RestTemplate.class);
        UUID addressId = UUID.randomUUID();
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class),
                any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(List.<Map<String, Object>>of(Map.of(
                        "id", addressId.toString(), "chain", "BTC", "subject", "user:42",
                        "status", "ACTIVE", "addressVersion", 1))));
        CustodyWalletClient queryClient = new CustodyWalletClient(properties, restTemplate, new ObjectMapper());
        ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
        ArgumentCaptor<HttpEntity> request = ArgumentCaptor.forClass(HttpEntity.class);

        assertThat(queryClient.withdrawalAddressId(42L, "BTC")).isEqualTo(addressId);
        org.mockito.Mockito.verify(restTemplate).exchange(uri.capture(), eq(HttpMethod.GET), request.capture(),
                any(ParameterizedTypeReference.class));
        assertThat(uri.getValue().getRawQuery()).contains("search=user%3A42").doesNotContain("%253A");
        String requestTarget = uri.getValue().getRawPath() + "?" + uri.getValue().getRawQuery();
        String timestamp = request.getValue().getHeaders().getFirst("X-Custody-Timestamp");
        String nonce = request.getValue().getHeaders().getFirst("X-Custody-Nonce");
        String canonical = queryClient.canonicalRequest(Long.parseLong(timestamp), nonce, "GET",
                requestTarget, new byte[0]);
        assertThat(request.getValue().getHeaders().getFirst("X-Custody-Signature"))
                .isEqualTo(queryClient.sign("wallet-secret", canonical));
    }

    @Test
    void selectsHighestVersionAddressThatActuallyCoversAssetAmount() {
        GatewayProperties properties = new GatewayProperties();
        properties.getCustodyWallet().setEnabled(true);
        properties.getCustodyWallet().setBaseUrl("https://wallet.example.com");
        properties.getCustodyWallet().setApiKey("wallet-key");
        properties.getCustodyWallet().setApiSecret("wallet-secret");
        RestTemplate restTemplate = mock(RestTemplate.class);
        UUID v1 = UUID.randomUUID();
        UUID v2 = UUID.randomUUID();
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class),
                any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(List.of(
                        Map.of("custodyAddressId", v2.toString(), "chain", "ETH", "assetSymbol", "ETH",
                                "subject", "user:34", "addressVersion", 2, "availableBalance", "0"),
                        Map.of("custodyAddressId", v1.toString(), "chain", "ETH", "assetSymbol", "ETH",
                                "subject", "user:34", "addressVersion", 1, "availableBalance", "0.005"))));
        CustodyWalletClient queryClient = new CustodyWalletClient(properties, restTemplate, new ObjectMapper());
        ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);

        assertThat(queryClient.withdrawalAddressId(34L, "ETH", "ETH", "0.0005")).isEqualTo(v1);
        org.mockito.Mockito.verify(restTemplate).exchange(uri.capture(), eq(HttpMethod.GET), any(HttpEntity.class),
                any(ParameterizedTypeReference.class));
        assertThat(uri.getValue().getRawPath()).isEqualTo("/custody/api/v1/address-balances");
        assertThat(uri.getValue().getRawQuery()).contains("subject=user%3A34", "assetSymbol=ETH",
                "requiredAmount=0.0005");
    }
}
