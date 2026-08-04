package com.surprising.gateway.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.repository.CustodyWalletWebhookRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

class CustodyWalletWebhookServiceTest {

    private final GatewayProperties properties = new GatewayProperties();
    private final CustodyWalletWebhookRepository repository = mock(CustodyWalletWebhookRepository.class);
    private final CustodyWalletClient walletClient = mock(CustodyWalletClient.class);
    private final RestTemplate restTemplate = mock(RestTemplate.class);
    private final CustodyWalletWebhookService service = new CustodyWalletWebhookService(
            properties, repository, walletClient, restTemplate, new ObjectMapper());

    @BeforeEach
    void setUp() {
        GatewayProperties.CustodyWallet wallet = properties.getCustodyWallet();
        wallet.setEnabled(true);
        wallet.setWebhookSecret("webhook-secret");
        wallet.setSpotAccountBaseUrl("http://account:9086");
        wallet.setAssetScales(Map.of("USDT", 6L));
    }

    @Test
    void convertsConfiguredDecimalAmountToSmallestUnits() {
        when(walletClient.amountUnits("usdt", "1.250000")).thenReturn(1_250_000L);
        assertThat(walletClient.amountUnits("usdt", "1.250000")).isEqualTo(1_250_000L);
        when(walletClient.amountUnits("USDT", "1.0000001"))
                .thenThrow(new IllegalArgumentException("amount is not exact"));
        assertThatThrownBy(() -> walletClient.amountUnits("USDT", "1.0000001"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void verifiesSignatureAndPostsConfirmedDepositToSpotAccount() {
        byte[] body = ("{\"id\":\"event-1\",\"type\":\"DEPOSIT.CONFIRMED\",\"data\":{"+
                "\"subject\":\"user:42\",\"asset\":\"USDT\",\"availableAmount\":\"1.25\"}}")
                .getBytes(StandardCharsets.UTF_8);
        long timestamp = Instant.now().getEpochSecond();
        when(repository.claim(eq("event-1"), eq("DEPOSIT.CONFIRMED"), any(), any()))
                .thenReturn(CustodyWalletWebhookRepository.ClaimResult.CLAIMED);
        when(walletClient.amountUnits("USDT", "1.25")).thenReturn(1_250_000L);
        when(restTemplate.exchange(any(java.net.URI.class), any(), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));

        service.handle("event-1", "DEPOSIT.CONFIRMED", Long.toString(timestamp),
                service.signature("webhook-secret", timestamp, body), body);

        verify(restTemplate).exchange(any(java.net.URI.class), any(), any(), eq(String.class));
        verify(repository).markProcessed(eq("event-1"), any());
    }

    @Test
    void rejectsInvalidSignatureBeforeClaimingEvent() {
        byte[] body = "{\"type\":\"DEPOSIT.CONFIRMED\",\"data\":{}}".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> service.handle("event-1", "DEPOSIT.CONFIRMED",
                Long.toString(Instant.now().getEpochSecond()), "v1=bad", body))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signature");
    }
}
