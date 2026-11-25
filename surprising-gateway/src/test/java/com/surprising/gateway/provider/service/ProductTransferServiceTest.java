package com.surprising.gateway.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.ProductBalanceResponse;
import com.surprising.account.api.model.ProductTransferOperationRequest;
import com.surprising.account.provider.exception.AccountCommandRejectedException;
import com.surprising.account.provider.service.AccountCommandGateway;
import com.surprising.gateway.provider.model.ProductTransferCommand;
import com.surprising.gateway.provider.model.ProductTransferResult;
import com.surprising.gateway.provider.model.ProductTransferStatus;
import com.surprising.gateway.provider.product.GatewayProductServices;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductTransferServiceTest {

    @Test
    void transferUsesStableRuntimeIdentityAndThreeForwardPhases() {
        RecordingProducts recording = new RecordingProducts();
        ProductTransferService coordinator = new ProductTransferService(recording.products());
        ProductTransferCommand command = command("transfer-001", "FUNDING", "USDT_PERPETUAL", 1_250_000L);

        ProductTransferResult first = coordinator.transfer(command);
        ProductTransferResult duplicate = coordinator.transfer(command);

        assertThat(first.transferId()).isEqualTo(duplicate.transferId());
        assertThat(first.status()).isEqualTo(ProductTransferStatus.COMPLETED);
        assertThat(duplicate.status()).isEqualTo(ProductTransferStatus.COMPLETED);
        assertThat(recording.calls()).extracting(TransferCall::phase)
                .containsExactly("OUT", "IN", "COMPLETE", "OUT", "IN", "COMPLETE");
        assertThat(recording.calls()).extracting(call -> call.operation().transferId())
                .containsOnly(first.transferId());
    }

    @Test
    void rejectedTargetRemainsSourceDebitedForForwardRecovery() {
        RecordingProducts recording = new RecordingProducts();
        recording.rejectNextTransferIn();
        ProductTransferService coordinator = new ProductTransferService(recording.products());

        ProductTransferResult result = coordinator.transfer(
                command("transfer-002", "FUNDING", "USDT_PERPETUAL", 10L));

        assertThat(result.status()).isEqualTo(ProductTransferStatus.SOURCE_DEBITED);
        assertThat(recording.calls()).extracting(TransferCall::phase)
                .containsExactly("OUT", "IN");
    }

    @Test
    void reconciliationReadsPendingRuntimeAndOnlyRunsRemainingPhases() {
        RecordingProducts recording = new RecordingProducts();
        ProductTransferService coordinator = new ProductTransferService(recording.products());
        recording.rejectNextTransferIn();
        ProductTransferResult started = coordinator.transfer(
                command("transfer-003", "FUNDING", "USDT_PERPETUAL", 10L));
        ProductTransferOperationRequest pending = recording.calls().get(0).operation();
        recording.addPending(pending);
        recording.clearCalls();

        assertThat(coordinator.reconcile(10)).isEqualTo(1);
        assertThat(recording.calls()).extracting(TransferCall::phase)
                .containsExactly("IN", "COMPLETE");
        assertThat(recording.calls()).extracting(call -> call.operation().transferId())
                .containsOnly(started.transferId());
    }

    @Test
    void sameUnderlyingFundingAndSpotAreRejectedBeforeProviderCall() {
        RecordingProducts recording = new RecordingProducts();
        ProductTransferService coordinator = new ProductTransferService(recording.products());

        assertThatThrownBy(() -> coordinator.transfer(
                command("transfer-005", "FUNDING", "SPOT", 10L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same account");
        assertThat(recording.calls()).isEmpty();
    }

    @Test
    void sameDerivativeAccountIsRejectedBeforeProviderCall() {
        RecordingProducts recording = new RecordingProducts();
        ProductTransferService coordinator = new ProductTransferService(recording.products());

        assertThatThrownBy(() -> coordinator.transfer(
                command("transfer-006", "USDT_PERPETUAL", "USDT_PERPETUAL", 10L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same account");
        assertThat(recording.calls()).isEmpty();
    }

    @Test
    void distinctIdempotencyKeysProduceDistinctRuntimeTransferIds() {
        RecordingProducts recording = new RecordingProducts();
        ProductTransferService coordinator = new ProductTransferService(recording.products());

        ProductTransferResult first = coordinator.transfer(new ProductTransferCommand(
                42L, "transfer-007", AccountType.FUNDING, AccountType.USDT_PERPETUAL,
                "USDT", 10L, "same-reference", "test"));
        ProductTransferResult second = coordinator.transfer(new ProductTransferCommand(
                42L, "transfer-008", AccountType.FUNDING, AccountType.USDT_PERPETUAL,
                "USDT", 10L, "same-reference", "test"));

        assertThat(first.transferId()).isNotEqualTo(second.transferId());
    }

    @Test
    void unavailableSourceDoesNotBlockOtherProducts() {
        var recording = new RecordingProducts();
        recording.unavailableSource = ProductLine.SPOT;
        var operation = new ProductTransferOperationRequest(10, 42, ProductLine.LINEAR_PERPETUAL,
                ProductLine.SPOT, AccountType.USDT_PERPETUAL, AccountType.SPOT, "USDT", 10, "r", "");
        recording.addPending(operation);
        assertThat(new ProductTransferService(recording.products()).reconcile(12)).isEqualTo(1);
        assertThat(recording.calls()).extracting(TransferCall::phase).containsExactly("IN", "COMPLETE");
    }

    @Test
    void smallBatchRotatesSourcesDespitePermanentlyPendingTransfers() {
        var recording = new RecordingProducts();
        var coordinator = new ProductTransferService(recording.products());
        for (ProductLine line : ProductLine.values()) {
            ProductLine target = line == ProductLine.SPOT ? ProductLine.LINEAR_PERPETUAL : ProductLine.SPOT;
            recording.addPending(new ProductTransferOperationRequest(line.ordinal() + 1, 42, line, target,
                    AccountType.valueOf(line.accountTypeCode()),
                    AccountType.valueOf(target.accountTypeCode()),
                    "USDT", 10, "r", ""));
        }
        recording.throwOnCredit = true;
        for (int i = 0; i < ProductLine.values().length; i++) {
            assertThat(coordinator.reconcile(1)).isEqualTo(1);
        }
        assertThat(recording.calls()).extracting(call -> call.operation().sourceProductLine())
                .containsExactly(ProductLine.values());
        assertThat(recording.calls()).extracting(TransferCall::phase).containsOnly("IN");
    }

    @Test
    void failedCreditDoesNotAbortOtherTransfersInTheSameBatch() {
        var recording = new RecordingProducts();
        var coordinator = new ProductTransferService(recording.products());
        for (int i = 1; i <= 2; i++) recording.addPending(new ProductTransferOperationRequest(i, 42,
                ProductLine.SPOT, ProductLine.LINEAR_PERPETUAL,
                AccountType.SPOT, AccountType.USDT_PERPETUAL, "USDT", 10, "r" + i, ""));
        recording.throwOnCredit = true;
        assertThat(coordinator.reconcile(12)).isEqualTo(2);
        assertThat(recording.calls()).extracting(call -> call.operation().transferId()).containsExactly(1L, 2L);
    }

    @Test
    void sameProductTransferUsesAuthenticatedSenderAndExplicitRecipient() {
        var recording = new RecordingProducts();
        var users = mock(com.surprising.gateway.provider.auth.repository.GatewayUserRepository.class);
        when(users.find(42)).thenReturn(java.util.Optional.of(
                new com.surprising.gateway.provider.auth.repository.GatewayUserRepository.UserRecord(
                        42, "sender", "sender@example.test", "NORMAL", Instant.now())));
        when(users.find(43)).thenReturn(java.util.Optional.of(
                new com.surprising.gateway.provider.auth.repository.GatewayUserRepository.UserRecord(
                        43, "recipient", "recipient@example.test", "NORMAL", Instant.now())));
        var coordinator = new ProductTransferService(recording.products(), users);
        var command = new ProductTransferCommand(42, "to-user", AccountType.SPOT, AccountType.SPOT, "USDT", 10,
                "to-user", "", 43);
        assertThat(coordinator.transfer(command).status()).isEqualTo(ProductTransferStatus.COMPLETED);
        assertThat(recording.calls()).allSatisfy(call -> {
            assertThat(call.operation().userId()).isEqualTo(42);
            assertThat(call.operation().recipientUserId()).isEqualTo(43);
        });
    }

    @Test
    void nonexistentRecipientIsRejectedBeforeSourceDebit() {
        var recording = new RecordingProducts();
        var users = mock(com.surprising.gateway.provider.auth.repository.GatewayUserRepository.class);
        when(users.find(42)).thenReturn(java.util.Optional.of(
                new com.surprising.gateway.provider.auth.repository.GatewayUserRepository.UserRecord(
                        42, "sender", "sender@example.test", "NORMAL", Instant.now())));
        var coordinator = new ProductTransferService(recording.products(), users);
        assertThatThrownBy(() -> coordinator.transfer(new ProductTransferCommand(42, "invalid-user",
                AccountType.SPOT, AccountType.SPOT, "USDT", 10, "r", "", 43)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recipient");
        assertThat(recording.calls()).isEmpty();
    }

    @Test
    void withdrawalDisabledSenderCannotMoveFundsToAnotherUser() {
        var recording = new RecordingProducts();
        var users = mock(com.surprising.gateway.provider.auth.repository.GatewayUserRepository.class);
        when(users.find(42)).thenReturn(java.util.Optional.of(
                new com.surprising.gateway.provider.auth.repository.GatewayUserRepository.UserRecord(
                        42, "sender", "sender@example.test", "WITHDRAW_DISABLED", Instant.now())));
        var coordinator = new ProductTransferService(recording.products(), users);
        assertThatThrownBy(() -> coordinator.transfer(new ProductTransferCommand(42, "blocked-user",
                AccountType.SPOT, AccountType.SPOT, "USDT", 10, "r", "", 43)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transfers are disabled");
        assertThat(recording.calls()).isEmpty();
    }

    private ProductTransferCommand command(String key, String source, String target, long amount) {
        return new ProductTransferCommand(42L, key, AccountType.valueOf(source),
                AccountType.valueOf(target), "USDT", amount, key, "test transfer");
    }

    private static final class RecordingProducts {
        private final GatewayProductServices products = mock(GatewayProductServices.class);
        private final List<TransferCall> calls = new ArrayList<>();
        private final EnumMap<ProductLine, List<ProductTransferOperationRequest>> pending =
                new EnumMap<>(ProductLine.class);
        private boolean rejectNextTransferIn;
        private boolean throwOnCredit;
        private ProductLine unavailableSource;

        RecordingProducts() {
            when(products.enabled()).thenReturn(List.of(ProductLine.values()));
            for (ProductLine line : ProductLine.values()) {
                AccountCommandGateway gateway = mock(AccountCommandGateway.class);
                when(gateway.transferOut(any())).thenAnswer(inv -> {
                    ProductTransferOperationRequest request = inv.getArgument(0);
                    calls.add(new TransferCall("OUT", request));
                    return new ProductBalanceResponse(request.userId(), request.sourceAccountType(),
                            request.asset(), 0L, 0L, 0L, Instant.now());
                });
                when(gateway.transferIn(any())).thenAnswer(inv -> {
                    ProductTransferOperationRequest request = inv.getArgument(0);
                    calls.add(new TransferCall("IN", request));
                    if (throwOnCredit) throw new IllegalStateException("target unavailable");
                    if (rejectNextTransferIn) {
                        rejectNextTransferIn = false;
                        throw new AccountCommandRejectedException("REJECTED", "rejected");
                    }
                    return new ProductBalanceResponse(request.recipientUserId(), request.targetAccountType(),
                            request.asset(), 0L, 0L, 0L, Instant.now());
                });
                org.mockito.Mockito.doAnswer(inv -> {
                    ProductTransferOperationRequest request = inv.getArgument(0);
                    calls.add(new TransferCall("COMPLETE", request));
                    List<ProductTransferOperationRequest> sourcePending = pending.get(request.sourceProductLine());
                    if (sourcePending != null) sourcePending.remove(request);
                    return null;
                }).when(gateway).completeTransfer(any());
                when(gateway.pendingTransfers(anyInt())).thenAnswer(inv -> {
                    int limit = inv.getArgument(0);
                    if (line == unavailableSource) throw new IllegalStateException("source unavailable");
                    assertThat(limit).isBetween(1, 256);
                    return pending.getOrDefault(line, List.of()).stream().limit(limit).toList();
                });
                when(products.service(eq(line), eq(AccountCommandGateway.class))).thenReturn(gateway);
            }
        }

        GatewayProductServices products() { return products; }
        void rejectNextTransferIn() { rejectNextTransferIn = true; }
        void addPending(ProductTransferOperationRequest operation) {
            pending.computeIfAbsent(operation.sourceProductLine(), ignored -> new ArrayList<>()).add(operation);
        }
        void clearCalls() { calls.clear(); }
        List<TransferCall> calls() { return List.copyOf(calls); }
    }

    private record TransferCall(String phase, ProductTransferOperationRequest operation) {}
}
