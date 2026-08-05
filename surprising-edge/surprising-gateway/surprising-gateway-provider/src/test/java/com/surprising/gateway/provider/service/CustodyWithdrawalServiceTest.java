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
        when(walletClient.createWithdrawal(eq(42L), any(), eq("custody-wallet-withdrawal:withdraw-unknown")))
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
    void concurrentSubmissionThatAlreadyPersistedDoesNotDowngradeToBroadcastUnknown() {
        GatewayProperties properties = new GatewayProperties();
        CustodyWithdrawalRepository repository = Mockito.mock(CustodyWithdrawalRepository.class);
        CustodyWalletClient walletClient = Mockito.mock(CustodyWalletClient.class);
        SpotAccountClient spotAccountClient = Mockito.mock(SpotAccountClient.class);
        WithdrawalValuationClient valuationClient = Mockito.mock(WithdrawalValuationClient.class);
        UUID withdrawalId = UUID.randomUUID();
        CustodyWithdrawalRepository.WithdrawalRecord processing = record(withdrawalId, "PROCESSING",
                "withdraw-concurrent");
        CustodyWithdrawalRepository.WithdrawalRecord debited = record(withdrawalId, "DEBITED",
                "withdraw-concurrent");
        CustodyWithdrawalRepository.WithdrawalRecord submitted = record(withdrawalId, "SUBMITTED",
                "withdraw-concurrent");
        when(walletClient.amountUnits("USDT", "25")).thenReturn(25_000_000L);
        when(valuationClient.toUsdt("USDT", new BigDecimal("25"))).thenReturn(new BigDecimal("25"));
        when(repository.createOrGet(any())).thenReturn(new CustodyWithdrawalRepository.CreateResult(processing, true));
        when(repository.markDebited(eq(withdrawalId), any())).thenReturn(debited);
        when(walletClient.createWithdrawal(eq(42L), any(), eq("custody-wallet-withdrawal:withdraw-concurrent")))
                .thenReturn(Map.of("id", "wallet-concurrent",
                        "externalReference", "custody-wallet-withdrawal:withdraw-concurrent"));
        when(repository.markSubmitted(eq(withdrawalId), any(), eq("wallet-concurrent")))
                .thenThrow(new IllegalStateException("already submitted"));
        when(repository.find(withdrawalId)).thenReturn(submitted);

        CustodyWithdrawalService service = service(properties, repository, walletClient, spotAccountClient,
                valuationClient);

        assertThat(service.submit(42L, "withdraw-concurrent", request()).status()).isEqualTo("SUBMITTED");
        verify(repository, never()).markBroadcastUnknown(any(), any(), any());
    }

    @Test
    void timeoutAfterConcurrentSubmissionDoesNotDowngradeToBroadcastUnknown() {
        GatewayProperties properties = new GatewayProperties();
        CustodyWithdrawalRepository repository = Mockito.mock(CustodyWithdrawalRepository.class);
        CustodyWalletClient walletClient = Mockito.mock(CustodyWalletClient.class);
        SpotAccountClient spotAccountClient = Mockito.mock(SpotAccountClient.class);
        WithdrawalValuationClient valuationClient = Mockito.mock(WithdrawalValuationClient.class);
        UUID withdrawalId = UUID.randomUUID();
        CustodyWithdrawalRepository.WithdrawalRecord processing = record(withdrawalId, "PROCESSING",
                "withdraw-timeout-race");
        CustodyWithdrawalRepository.WithdrawalRecord debited = record(withdrawalId, "DEBITED",
                "withdraw-timeout-race");
        CustodyWithdrawalRepository.WithdrawalRecord submitted = record(withdrawalId, "SUBMITTED",
                "withdraw-timeout-race");
        when(walletClient.amountUnits("USDT", "25")).thenReturn(25_000_000L);
        when(valuationClient.toUsdt("USDT", new BigDecimal("25"))).thenReturn(new BigDecimal("25"));
        when(repository.createOrGet(any())).thenReturn(new CustodyWithdrawalRepository.CreateResult(processing, true));
        when(repository.markDebited(eq(withdrawalId), any())).thenReturn(debited);
        when(walletClient.createWithdrawal(eq(42L), any(), eq("custody-wallet-withdrawal:withdraw-timeout-race")))
                .thenThrow(new IllegalStateException("custody request timed out"));
        when(repository.markBroadcastUnknown(eq(withdrawalId), any(), eq("custody request timed out")))
                .thenThrow(new IllegalStateException("already submitted"));
        when(repository.find(withdrawalId)).thenReturn(submitted);

        CustodyWithdrawalService service = service(properties, repository, walletClient, spotAccountClient,
                valuationClient);

        assertThat(service.submit(42L, "withdraw-timeout-race", request()).status()).isEqualTo("SUBMITTED");
        verify(repository).markBroadcastUnknown(eq(withdrawalId), any(), eq("custody request timed out"));
    }

    @Test
    void localSubmissionStateConflictDoesNotBecomeBroadcastUnknown() {
        GatewayProperties properties = new GatewayProperties();
        CustodyWithdrawalRepository repository = Mockito.mock(CustodyWithdrawalRepository.class);
        CustodyWalletClient walletClient = Mockito.mock(CustodyWalletClient.class);
        SpotAccountClient spotAccountClient = Mockito.mock(SpotAccountClient.class);
        WithdrawalValuationClient valuationClient = Mockito.mock(WithdrawalValuationClient.class);
        UUID withdrawalId = UUID.randomUUID();
        CustodyWithdrawalRepository.WithdrawalRecord processing = record(withdrawalId, "PROCESSING",
                "withdraw-local-conflict");
        CustodyWithdrawalRepository.WithdrawalRecord debited = record(withdrawalId, "DEBITED",
                "withdraw-local-conflict");
        when(walletClient.amountUnits("USDT", "25")).thenReturn(25_000_000L);
        when(valuationClient.toUsdt("USDT", new BigDecimal("25"))).thenReturn(new BigDecimal("25"));
        when(repository.createOrGet(any())).thenReturn(new CustodyWithdrawalRepository.CreateResult(processing, true));
        when(repository.markDebited(eq(withdrawalId), any())).thenReturn(debited);
        when(walletClient.createWithdrawal(eq(42L), any(), eq("custody-wallet-withdrawal:withdraw-local-conflict")))
                .thenReturn(Map.of("id", "wallet-local-conflict",
                        "externalReference", "custody-wallet-withdrawal:withdraw-local-conflict"));
        when(repository.markSubmitted(eq(withdrawalId), any(), eq("wallet-local-conflict")))
                .thenThrow(new IllegalStateException("invalid local status"));
        when(repository.find(withdrawalId)).thenReturn(debited);

        CustodyWithdrawalService service = service(properties, repository, walletClient, spotAccountClient,
                valuationClient);

        assertThatThrownBy(() -> service.submit(42L, "withdraw-local-conflict", request()))
                .isInstanceOf(IllegalStateException.class);
        verify(repository, never()).markBroadcastUnknown(any(), any(), any());
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
        when(walletClient.createWithdrawal(eq(42L), any(), eq("custody-wallet-withdrawal:withdraw-rejected")))
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
        when(walletClient.createWithdrawal(eq(42L), any(), eq("custody-wallet-withdrawal:withdraw-retry")))
                .thenReturn(Map.of("id", "wallet-withdrawal-1",
                        "externalReference", "custody-wallet-withdrawal:withdraw-retry"));
        when(repository.markSubmitted(eq(withdrawalId), any(), eq("wallet-withdrawal-1")))
                .thenReturn(record(withdrawalId, "SUBMITTED", "withdraw-retry"));

        CustodyWithdrawalService service = service(properties, repository, walletClient, spotAccountClient,
                valuationClient);

        when(repository.recordAdminRetry(eq(withdrawalId), eq(7L), eq("admin"), eq("manual retry")))
                .thenReturn(unknown);
        CustodyWithdrawalService.WithdrawalResponse response =
                service.retry(withdrawalId, 7L, "admin", "manual retry");

        assertThat(response.status()).isEqualTo("SUBMITTED");
        verify(spotAccountClient).adjustBalance(42L, "USDT", -25_000_000L,
                "custody-wallet-withdrawal:withdraw-retry", "custody wallet withdrawal");
        verify(walletClient).createWithdrawal(eq(42L), any(), eq("custody-wallet-withdrawal:withdraw-retry"));
    }

    @Test
    void failedCustodyWebhookWaitsForCustodyTerminalStateBeforeRefunding() {
        GatewayProperties properties = new GatewayProperties();
        CustodyWithdrawalRepository repository = Mockito.mock(CustodyWithdrawalRepository.class);
        CustodyWalletClient walletClient = Mockito.mock(CustodyWalletClient.class);
        SpotAccountClient spotAccountClient = Mockito.mock(SpotAccountClient.class);
        WithdrawalValuationClient valuationClient = Mockito.mock(WithdrawalValuationClient.class);
        UUID withdrawalId = UUID.randomUUID();
        CustodyWithdrawalRepository.WithdrawalRecord record = record(withdrawalId, "SUBMITTED", "withdraw-failed");
        when(repository.findByWalletReference("wallet-withdrawal-1", "custody-wallet-withdrawal:withdraw-failed"))
                .thenReturn(record);
        CustodyWithdrawalRepository.WithdrawalRecord pending =
                record(withdrawalId, "FAILED_PENDING", "withdraw-failed");
        when(repository.markFailurePending(eq(withdrawalId), any(), any(), eq("wallet-withdrawal-1")))
                .thenReturn(pending);
        when(repository.listPendingFailures(any(), eq(50))).thenReturn(java.util.List.of(pending));
        when(walletClient.withdrawalsByExternalReference(
                eq("custody-wallet-withdrawal:withdraw-failed"), eq("ETH"), eq("USDT"), eq(20)))
                .thenReturn(java.util.List.of(Map.of(
                        "id", "wallet-withdrawal-1",
                        "externalReference", "custody-wallet-withdrawal:withdraw-failed",
                        "status", "FAILED", "asset", "USDT", "chain", "ETH", "amount", "25")));
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

        verify(spotAccountClient, never()).adjustBalance(eq(42L), eq("USDT"), eq(25_000_000L),
                eq("custody-wallet-withdrawal:withdraw-failed:refund"), any());
        service.reconcileFailedWithdrawals();

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

        assertThatThrownBy(() -> service.handleWebhook("WITHDRAWAL.FAILED", Map.of(
                "data", Map.of("withdrawalId", "wallet-withdrawal-1",
                        "externalReference", "custody-wallet-withdrawal:withdraw-late",
                        "asset", "USDT", "chain", "ETH", "amount", "25"))))
                .isInstanceOf(IllegalStateException.class);

        verify(spotAccountClient, never()).adjustBalance(any(Long.class), any(), any(Long.class), any(), any());
        verify(repository, never()).markRefundPending(any(), any());
    }

    @Test
    void broadcastUnknownWebhookDoesNotSilentlyAcceptFailurePendingState() {
        GatewayProperties properties = new GatewayProperties();
        CustodyWithdrawalRepository repository = Mockito.mock(CustodyWithdrawalRepository.class);
        CustodyWalletClient walletClient = Mockito.mock(CustodyWalletClient.class);
        SpotAccountClient spotAccountClient = Mockito.mock(SpotAccountClient.class);
        WithdrawalValuationClient valuationClient = Mockito.mock(WithdrawalValuationClient.class);
        UUID withdrawalId = UUID.randomUUID();
        CustodyWithdrawalRepository.WithdrawalRecord pending =
                record(withdrawalId, "FAILED_PENDING", "withdraw-webhook-conflict");
        when(repository.findByWalletReference("wallet-webhook-conflict",
                "custody-wallet-withdrawal:withdraw-webhook-conflict")).thenReturn(pending);
        when(repository.markBroadcastUnknown(eq(withdrawalId), any(), any(), eq("wallet-webhook-conflict")))
                .thenThrow(new IllegalStateException("invalid webhook source status"));

        CustodyWithdrawalService service = service(properties, repository, walletClient, spotAccountClient,
                valuationClient);

        assertThatThrownBy(() -> service.handleWebhook("WITHDRAWAL.BROADCAST_UNKNOWN", Map.of(
                "data", Map.of("withdrawalId", "wallet-webhook-conflict",
                        "externalReference", "custody-wallet-withdrawal:withdraw-webhook-conflict",
                        "asset", "USDT", "chain", "ETH", "amount", "25"))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void lateBroadcastUnknownWebhookKeepsProviderEventIdOnWithdrawalAudit() {
        GatewayProperties properties = new GatewayProperties();
        CustodyWithdrawalRepository repository = Mockito.mock(CustodyWithdrawalRepository.class);
        CustodyWalletClient walletClient = Mockito.mock(CustodyWalletClient.class);
        SpotAccountClient spotAccountClient = Mockito.mock(SpotAccountClient.class);
        WithdrawalValuationClient valuationClient = Mockito.mock(WithdrawalValuationClient.class);
        UUID withdrawalId = UUID.randomUUID();
        CustodyWithdrawalRepository.WithdrawalRecord submitted =
                record(withdrawalId, "SUBMITTED", "withdraw-webhook-late");
        when(repository.findByWalletReference("wallet-webhook-late",
                "custody-wallet-withdrawal:withdraw-webhook-late", "provider-event-late"))
                .thenReturn(submitted);
        when(repository.markBroadcastUnknown(eq(withdrawalId), any(), any(), eq("wallet-webhook-late"),
                eq("provider-event-late")))
                .thenThrow(new IllegalStateException("invalid webhook source status"));
        when(repository.find(withdrawalId)).thenReturn(submitted);

        CustodyWithdrawalService service = service(properties, repository, walletClient, spotAccountClient,
                valuationClient);

        service.handleWebhook("provider-event-late", "WITHDRAWAL.BROADCAST_UNKNOWN", Map.of(
                "data", Map.of("withdrawalId", "wallet-webhook-late",
                        "externalReference", "custody-wallet-withdrawal:withdraw-webhook-late",
                        "asset", "USDT", "chain", "ETH", "amount", "25")));

        verify(repository).recordWebhookObservation(eq(withdrawalId), eq("wallet-webhook-late"), any(),
                eq("late broadcast-unknown webhook ignored after local status advanced"),
                eq("provider-event-late"));
    }

    @Test
    void confirmationWinsAfterFailureIsHeldForAuthoritativeReconciliation() {
        GatewayProperties properties = new GatewayProperties();
        CustodyWithdrawalRepository repository = Mockito.mock(CustodyWithdrawalRepository.class);
        CustodyWalletClient walletClient = Mockito.mock(CustodyWalletClient.class);
        SpotAccountClient spotAccountClient = Mockito.mock(SpotAccountClient.class);
        WithdrawalValuationClient valuationClient = Mockito.mock(WithdrawalValuationClient.class);
        UUID withdrawalId = UUID.randomUUID();
        CustodyWithdrawalRepository.WithdrawalRecord pending =
                record(withdrawalId, "FAILED_PENDING", "withdraw-ordering");
        when(repository.findByWalletReference("wallet-withdrawal-1", "custody-wallet-withdrawal:withdraw-ordering"))
                .thenReturn(pending);
        when(repository.markCompleted(eq(withdrawalId), any(), eq("wallet-withdrawal-1")))
                .thenReturn(record(withdrawalId, "COMPLETED", "withdraw-ordering"));

        CustodyWithdrawalService service = service(properties, repository, walletClient, spotAccountClient,
                valuationClient);

        service.handleWebhook("WITHDRAWAL.CONFIRMED", Map.of(
                "data", Map.of("withdrawalId", "wallet-withdrawal-1",
                        "externalReference", "custody-wallet-withdrawal:withdraw-ordering",
                        "asset", "USDT", "chain", "ETH", "amount", "25")));

        verify(repository).markCompleted(eq(withdrawalId), any(), eq("wallet-withdrawal-1"));
        verify(spotAccountClient, never()).adjustBalance(any(Long.class), any(), any(Long.class), any(), any());
        verify(repository, never()).markRefundPending(any(), any());
    }

    @Test
    void localHistoryRemainsAvailableWhenCustodyHistoryIsUnavailable() {
        GatewayProperties properties = new GatewayProperties();
        CustodyWithdrawalRepository repository = Mockito.mock(CustodyWithdrawalRepository.class);
        CustodyWalletClient walletClient = Mockito.mock(CustodyWalletClient.class);
        SpotAccountClient spotAccountClient = Mockito.mock(SpotAccountClient.class);
        WithdrawalValuationClient valuationClient = Mockito.mock(WithdrawalValuationClient.class);
        when(walletClient.withdrawals(42L, "ETH", "USDT", 50))
                .thenThrow(new IllegalStateException("custody history unavailable"));
        when(repository.listForUser(42L, "ETH", "USDT", 50))
                .thenReturn(java.util.List.of(record(UUID.randomUUID(), "BROADCAST_UNKNOWN", "withdraw-history")));

        CustodyWithdrawalService service = service(properties, repository, walletClient, spotAccountClient,
                valuationClient);

        assertThat(service.history(42L, "ETH", "USDT", 50))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row).containsEntry("status", "BROADCAST_UNKNOWN")
                            .containsEntry("custodyWalletUnavailable", true);
                });
    }

    private CustodyWithdrawalService service(GatewayProperties properties,
                                             CustodyWithdrawalRepository repository,
                                             CustodyWalletClient walletClient,
                                             SpotAccountClient spotAccountClient,
                                             WithdrawalValuationClient valuationClient) {
        CustodyWithdrawalRefundService refundService =
                new CustodyWithdrawalRefundService(repository, spotAccountClient);
        CustodyWithdrawalReconciliationService reconciliationService =
                new CustodyWithdrawalReconciliationService(repository, walletClient, refundService, new ObjectMapper());
        return new CustodyWithdrawalService(properties, repository, walletClient, spotAccountClient,
                valuationClient, refundService, reconciliationService, new ObjectMapper());
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
                "0xrecipient", "25", 25_000_000L, new BigDecimal("25"),
                "custody-wallet-withdrawal:" + key,
                "custody-wallet-withdrawal:" + key, "{}", status, null, null, null, null,
                Instant.now(), Instant.now(), null, null, null, null, null);
    }
}
