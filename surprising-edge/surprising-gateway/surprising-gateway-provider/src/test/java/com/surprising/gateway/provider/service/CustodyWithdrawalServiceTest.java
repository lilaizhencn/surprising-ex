package com.surprising.gateway.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.repository.CustodyWithdrawalRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

class CustodyWithdrawalServiceTest {

    @Test
    void unknownCustodyResponseKeepsFundsInBroadcastUnknownWithoutRefund() {
        GatewayProperties properties = new GatewayProperties();
        CustodyWithdrawalRepository repository = Mockito.mock(CustodyWithdrawalRepository.class);
        CustodyWalletClient walletClient = Mockito.mock(CustodyWalletClient.class);
        SpotAccountClient spotAccountClient = Mockito.mock(SpotAccountClient.class);
        WithdrawalValuationClient valuationClient = Mockito.mock(WithdrawalValuationClient.class);
        UUID withdrawalId = UUID.randomUUID();
        CustodyWithdrawalRepository.WithdrawalRecord record = record(withdrawalId, "PROCESSING");
        when(walletClient.amountUnits("USDT", "25")).thenReturn(25_000_000L);
        when(valuationClient.toUsdt("USDT", new BigDecimal("25"))).thenReturn(new BigDecimal("25"));
        when(repository.createOrGet(any())).thenReturn(new CustodyWithdrawalRepository.CreateResult(record, true));
        when(repository.markDebited(eq(withdrawalId), any())).thenReturn(record(withdrawalId, "DEBITED"));
        when(walletClient.createWithdrawal(eq(42L), any(), eq("withdraw-unknown")))
                .thenThrow(new IllegalStateException("custody request timed out"));

        CustodyWithdrawalService service = service(properties, repository, walletClient, spotAccountClient,
                valuationClient);

        assertThatThrownBy(() -> service.submit(42L, "withdraw-unknown", request()))
                .isInstanceOf(CustodyWithdrawalService.WithdrawalUnknownException.class);
        verify(repository).markBroadcastUnknown(eq(withdrawalId), any(), eq("custody request timed out"));
        verify(spotAccountClient, never()).adjustBalance(eq(42L), eq("USDT"), eq(25_000_000L),
                eq("custody-wallet-withdrawal:withdraw-unknown:refund"), any());
    }

    @Test
    void rejectedCustodyResponseRefundsExactlyOnceAndReturnsRejected() {
        GatewayProperties properties = new GatewayProperties();
        CustodyWithdrawalRepository repository = Mockito.mock(CustodyWithdrawalRepository.class);
        CustodyWalletClient walletClient = Mockito.mock(CustodyWalletClient.class);
        SpotAccountClient spotAccountClient = Mockito.mock(SpotAccountClient.class);
        WithdrawalValuationClient valuationClient = Mockito.mock(WithdrawalValuationClient.class);
        UUID withdrawalId = UUID.randomUUID();
        CustodyWithdrawalRepository.WithdrawalRecord record = record(withdrawalId, "PROCESSING", "withdraw-rejected");
        when(walletClient.amountUnits("USDT", "25")).thenReturn(25_000_000L);
        when(valuationClient.toUsdt("USDT", new BigDecimal("25"))).thenReturn(new BigDecimal("25"));
        when(repository.createOrGet(any())).thenReturn(new CustodyWithdrawalRepository.CreateResult(record, true));
        when(repository.markDebited(eq(withdrawalId), any())).thenReturn(
                record(withdrawalId, "DEBITED", "withdraw-rejected"));
        when(repository.markRefundPending(eq(withdrawalId), any()))
                .thenReturn(record(withdrawalId, "REFUND_PENDING", "withdraw-rejected"));
        when(walletClient.createWithdrawal(eq(42L), any(), eq("withdraw-rejected")))
                .thenThrow(new CustodyWalletClient.CustodyWalletRejectedException("rejected", 400, null));

        CustodyWithdrawalService service = service(properties, repository, walletClient, spotAccountClient,
                valuationClient);

        assertThatThrownBy(() -> service.submit(42L, "withdraw-rejected", request()))
                .isInstanceOf(CustodyWithdrawalService.WithdrawalRejectedException.class);
        verify(spotAccountClient).adjustBalance(42L, "USDT", 25_000_000L,
                "custody-wallet-withdrawal:withdraw-rejected:refund", "custody wallet withdrawal rejected refund");
        verify(repository).markRefunded(eq(withdrawalId), any(), eq("custody wallet rejected withdrawal"));
    }

    @Test
    void retryingSameIdempotencyKeyDoesNotCreateAnotherWithdrawalIntent() {
        GatewayProperties properties = new GatewayProperties();
        CustodyWithdrawalRepository repository = Mockito.mock(CustodyWithdrawalRepository.class);
        CustodyWalletClient walletClient = Mockito.mock(CustodyWalletClient.class);
        SpotAccountClient spotAccountClient = Mockito.mock(SpotAccountClient.class);
        WithdrawalValuationClient valuationClient = Mockito.mock(WithdrawalValuationClient.class);
        UUID withdrawalId = UUID.randomUUID();
        CustodyWithdrawalRepository.WithdrawalRecord record = record(withdrawalId, "SUBMITTED");
        when(walletClient.amountUnits("USDT", "25")).thenReturn(25_000_000L);
        when(valuationClient.toUsdt("USDT", new BigDecimal("25"))).thenReturn(new BigDecimal("25"));
        when(repository.createOrGet(any())).thenReturn(new CustodyWithdrawalRepository.CreateResult(record, false));

        CustodyWithdrawalService service = service(properties, repository, walletClient, spotAccountClient,
                valuationClient);

        CustodyWithdrawalService.WithdrawalResponse response =
                service.submit(42L, "withdraw-retry", request());

        assertThat(response.status()).isEqualTo("SUBMITTED");
        verify(spotAccountClient, never()).adjustBalance(any(Long.class), any(), any(Long.class), any(), any());
        verify(walletClient, never()).createWithdrawal(any(Long.class), any(), any());
    }

    @Test
    void retryingDebitUnknownReusesTheSameLedgerReference() {
        GatewayProperties properties = new GatewayProperties();
        CustodyWithdrawalRepository repository = Mockito.mock(CustodyWithdrawalRepository.class);
        CustodyWalletClient walletClient = Mockito.mock(CustodyWalletClient.class);
        SpotAccountClient spotAccountClient = Mockito.mock(SpotAccountClient.class);
        WithdrawalValuationClient valuationClient = Mockito.mock(WithdrawalValuationClient.class);
        UUID withdrawalId = UUID.randomUUID();
        CustodyWithdrawalRepository.WithdrawalRecord unknown = record(withdrawalId, "DEBIT_UNKNOWN", "withdraw-retry");
        when(repository.find(withdrawalId)).thenReturn(unknown);
        when(repository.markDebited(eq(withdrawalId), any()))
                .thenReturn(record(withdrawalId, "DEBITED", "withdraw-retry"));
        when(walletClient.createWithdrawal(eq(42L), any(), eq("withdraw-retry")))
                .thenReturn(Map.of("id", "wallet-withdrawal-1"));
        when(repository.markSubmitted(eq(withdrawalId), any(), eq("wallet-withdrawal-1")))
                .thenReturn(record(withdrawalId, "SUBMITTED", "withdraw-retry"));

        CustodyWithdrawalService service = service(properties, repository, walletClient, spotAccountClient,
                valuationClient);

        CustodyWithdrawalService.WithdrawalResponse response = service.retry(withdrawalId);

        assertThat(response.status()).isEqualTo("SUBMITTED");
        verify(spotAccountClient).adjustBalance(42L, "USDT", -25_000_000L,
                "custody-wallet-withdrawal:withdraw-retry", "custody wallet withdrawal");
        verify(walletClient).createWithdrawal(eq(42L), any(), eq("withdraw-retry"));
    }

    @Test
    void failedCustodyWebhookRefundsTheDebitedSpotBalance() {
        GatewayProperties properties = new GatewayProperties();
        CustodyWithdrawalRepository repository = Mockito.mock(CustodyWithdrawalRepository.class);
        CustodyWalletClient walletClient = Mockito.mock(CustodyWalletClient.class);
        SpotAccountClient spotAccountClient = Mockito.mock(SpotAccountClient.class);
        WithdrawalValuationClient valuationClient = Mockito.mock(WithdrawalValuationClient.class);
        UUID withdrawalId = UUID.randomUUID();
        CustodyWithdrawalRepository.WithdrawalRecord record = record(withdrawalId, "SUBMITTED", "withdraw-failed");
        when(repository.findByWalletReference("wallet-withdrawal-1", "custody-wallet-withdrawal:withdraw-failed"))
                .thenReturn(record);
        when(repository.markRefundPending(eq(withdrawalId), any()))
                .thenReturn(record(withdrawalId, "REFUND_PENDING", "withdraw-failed"));
        when(repository.markRefunded(eq(withdrawalId), any(), eq("custody wallet withdrawal failed")))
                .thenReturn(record(withdrawalId, "REFUNDED", "withdraw-failed"));

        CustodyWithdrawalService service = service(properties, repository, walletClient, spotAccountClient,
                valuationClient);

        service.handleWebhook("WITHDRAWAL.FAILED", Map.of(
                "data", Map.of("withdrawalId", "wallet-withdrawal-1",
                        "externalReference", "custody-wallet-withdrawal:withdraw-failed",
                        "asset", "USDT", "chain", "ETH", "amount", "25")));

        verify(spotAccountClient).adjustBalance(42L, "USDT", 25_000_000L,
                "custody-wallet-withdrawal:withdraw-failed:refund", "custody wallet withdrawal failed");
        verify(repository).markRefunded(eq(withdrawalId), any(), eq("custody wallet withdrawal failed"));
    }

    @Test
    void lateFailureAfterConfirmationDoesNotCreditSpotBalanceAgain() {
        GatewayProperties properties = new GatewayProperties();
        CustodyWithdrawalRepository repository = Mockito.mock(CustodyWithdrawalRepository.class);
        CustodyWalletClient walletClient = Mockito.mock(CustodyWalletClient.class);
        SpotAccountClient spotAccountClient = Mockito.mock(SpotAccountClient.class);
        WithdrawalValuationClient valuationClient = Mockito.mock(WithdrawalValuationClient.class);
        UUID withdrawalId = UUID.randomUUID();
        CustodyWithdrawalRepository.WithdrawalRecord completed = record(withdrawalId, "COMPLETED", "withdraw-late");
        when(repository.findByWalletReference("wallet-withdrawal-1", "custody-wallet-withdrawal:withdraw-late"))
                .thenReturn(completed);

        CustodyWithdrawalService service = service(properties, repository, walletClient, spotAccountClient,
                valuationClient);

        service.handleWebhook("WITHDRAWAL.FAILED", Map.of(
                "data", Map.of("withdrawalId", "wallet-withdrawal-1",
                        "externalReference", "custody-wallet-withdrawal:withdraw-late",
                        "asset", "USDT", "chain", "ETH", "amount", "25")));

        verify(spotAccountClient, never()).adjustBalance(any(Long.class), any(), any(Long.class), any(), any());
        verify(repository, never()).markRefundPending(any(), any());
    }

    private CustodyWithdrawalService service(GatewayProperties properties,
                                             CustodyWithdrawalRepository repository,
                                             CustodyWalletClient walletClient,
                                             SpotAccountClient spotAccountClient,
                                             WithdrawalValuationClient valuationClient) {
        return new CustodyWithdrawalService(properties, repository, walletClient, spotAccountClient,
                valuationClient, new ObjectMapper());
    }

    private CustodyWithdrawalService.WithdrawalRequest request() {
        return new CustodyWithdrawalService.WithdrawalRequest(
                UUID.randomUUID(), "ETH", "USDT", "0xrecipient", "25", null);
    }

    private CustodyWithdrawalRepository.WithdrawalRecord record(UUID id, String status) {
        return record(id, status, "withdraw-unknown");
    }

    private CustodyWithdrawalRepository.WithdrawalRecord record(UUID id, String status, String key) {
        return new CustodyWithdrawalRepository.WithdrawalRecord(
                id, 42L, key, "hash", "ETH", "USDT", UUID.randomUUID(),
                "0xrecipient", "25", 25_000_000L, new BigDecimal("25"), null,
                "custody-wallet-withdrawal:" + key, "{}", status, null, null, null, null,
                Instant.now(), Instant.now(), null, null, null, null, null);
    }
}
