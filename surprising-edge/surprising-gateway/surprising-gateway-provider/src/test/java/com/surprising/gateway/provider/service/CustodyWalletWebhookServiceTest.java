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
import tools.jackson.databind.ObjectMapper;

class CustodyWalletWebhookServiceTest {

    private final GatewayProperties properties = new GatewayProperties();
    private final CustodyWalletWebhookRepository repository = mock(CustodyWalletWebhookRepository.class);
    private final CustodyWalletClient walletClient = mock(CustodyWalletClient.class);
    private final CustodyWithdrawalService withdrawalService = mock(CustodyWithdrawalService.class);
    private final SpotAccountClient spotAccountClient = mock(SpotAccountClient.class);
    private final CustodyWalletWebhookService service = new CustodyWalletWebhookService(
            properties, repository, walletClient, withdrawalService, spotAccountClient, new ObjectMapper());

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
        service.handle("event-1", "DEPOSIT.CONFIRMED", Long.toString(timestamp),
                service.signature("webhook-secret", "event-1", "DEPOSIT.CONFIRMED", timestamp, body), body);

        verify(spotAccountClient).adjustBalance(42L, "USDT", 1_250_000L,
                "custody-wallet:event-1:deposit.confirmed", "custody wallet DEPOSIT.CONFIRMED");
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

    @Test
    void rejectsHeaderIdentityThatDoesNotMatchSignedPayload() {
        byte[] body = "{\"id\":\"event-1\",\"type\":\"DEPOSIT.CONFIRMED\",\"data\":{}}"
                .getBytes(StandardCharsets.UTF_8);
        long timestamp = Instant.now().getEpochSecond();

        assertThatThrownBy(() -> service.handle("event-2", "DEPOSIT.CONFIRMED", Long.toString(timestamp),
                service.signature("webhook-secret", "event-2", "DEPOSIT.CONFIRMED", timestamp, body), body))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("event id");
        verify(repository, org.mockito.Mockito.never()).claim(any(), any(), any(), any());
    }

    @Test
    void rejectsNonNormalizedIdentityHeadersBeforeClaimingEvent() {
        byte[] body = "{\"id\":\"event-1\",\"type\":\"DEPOSIT.CONFIRMED\",\"data\":{}}"
                .getBytes(StandardCharsets.UTF_8);
        long timestamp = Instant.now().getEpochSecond();

        assertThatThrownBy(() -> service.handle("event-1 ", "DEPOSIT.CONFIRMED", Long.toString(timestamp),
                service.signature("webhook-secret", "event-1", "DEPOSIT.CONFIRMED", timestamp, body), body))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("normalized");
        verify(repository, org.mockito.Mockito.never()).claim(any(), any(), any(), any());
    }

    @Test
    void dispatchesWithdrawalWebhookToTheWithdrawalStateMachine() {
        byte[] body = ("{\"id\":\"withdrawal-event-1\",\"type\":\"WITHDRAWAL.CONFIRMED\","
                + "\"data\":{\"withdrawalId\":\"wallet-withdrawal-1\","
                + "\"externalReference\":\"custody-wallet-withdrawal:withdraw-1\"}}")
                .getBytes(StandardCharsets.UTF_8);
        long timestamp = Instant.now().getEpochSecond();
        when(repository.claim(eq("withdrawal-event-1"), eq("WITHDRAWAL.CONFIRMED"), any(), any()))
                .thenReturn(CustodyWalletWebhookRepository.ClaimResult.CLAIMED);

        service.handle("withdrawal-event-1", "WITHDRAWAL.CONFIRMED", Long.toString(timestamp),
                service.signature("webhook-secret", "withdrawal-event-1", "WITHDRAWAL.CONFIRMED", timestamp, body), body);

        verify(withdrawalService).handleWebhook(eq("WITHDRAWAL.CONFIRMED"), any());
        verify(repository).markProcessed(eq("withdrawal-event-1"), any());
    }
}
