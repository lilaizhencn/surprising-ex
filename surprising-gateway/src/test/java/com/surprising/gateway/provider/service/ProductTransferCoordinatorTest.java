package com.surprising.gateway.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.account.api.model.ProductTransferOperationRequest;
import com.surprising.product.api.ProductLine;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductTransferCoordinatorTest {

    @Test
    void transferUsesStableRuntimeIdentityAndThreeForwardPhases() {
        RecordingProductAccountClient accountClient = new RecordingProductAccountClient();
        ProductTransferCoordinator coordinator = new ProductTransferCoordinator(accountClient);
        ProductTransferCommand command = command("transfer-001", "FUNDING", "USDT_PERPETUAL", 1_250_000L);

        ProductTransferResult first = coordinator.transfer(command);
        ProductTransferResult duplicate = coordinator.transfer(command);

        assertThat(first.transferId()).isEqualTo(duplicate.transferId());
        assertThat(first.status()).isEqualTo(ProductTransferStatus.COMPLETED);
        assertThat(duplicate.status()).isEqualTo(ProductTransferStatus.COMPLETED);
        assertThat(accountClient.calls()).extracting(TransferCall::phase)
                .containsExactly("OUT", "IN", "COMPLETE", "OUT", "IN", "COMPLETE");
        assertThat(accountClient.calls()).extracting(call -> call.operation().transferId())
                .containsOnly(first.transferId());
    }

    @Test
    void rejectedTargetRemainsSourceDebitedForForwardRecovery() {
        RecordingProductAccountClient accountClient = new RecordingProductAccountClient();
        accountClient.rejectNextTransferIn();
        ProductTransferCoordinator coordinator = new ProductTransferCoordinator(accountClient);

        ProductTransferResult result = coordinator.transfer(
                command("transfer-002", "FUNDING", "USDT_PERPETUAL", 10L));

        assertThat(result.status()).isEqualTo(ProductTransferStatus.SOURCE_DEBITED);
        assertThat(accountClient.calls()).extracting(TransferCall::phase)
                .containsExactly("OUT", "IN");
    }

    @Test
    void reconciliationReadsPendingRuntimeAndOnlyRunsRemainingPhases() {
        RecordingProductAccountClient accountClient = new RecordingProductAccountClient();
        ProductTransferCoordinator coordinator = new ProductTransferCoordinator(accountClient);
        accountClient.rejectNextTransferIn();
        ProductTransferResult started = coordinator.transfer(
                command("transfer-003", "FUNDING", "USDT_PERPETUAL", 10L));
        ProductTransferOperationRequest pending = accountClient.calls().get(0).operation();
        accountClient.addPending(pending);
        accountClient.clearCalls();

        assertThat(coordinator.reconcile(10)).isEqualTo(1);
        assertThat(accountClient.calls()).extracting(TransferCall::phase)
                .containsExactly("IN", "COMPLETE");
        assertThat(accountClient.calls()).extracting(call -> call.operation().transferId())
                .containsOnly(started.transferId());
    }

    @Test
    void sameUnderlyingFundingAndSpotAreRejectedBeforeProviderCall() {
        RecordingProductAccountClient accountClient = new RecordingProductAccountClient();
        ProductTransferCoordinator coordinator = new ProductTransferCoordinator(accountClient);

        assertThatThrownBy(() -> coordinator.transfer(
                command("transfer-005", "FUNDING", "SPOT", 10L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same account");
        assertThat(accountClient.calls()).isEmpty();
    }

    @Test
    void sameDerivativeAccountIsRejectedBeforeProviderCall() {
        RecordingProductAccountClient accountClient = new RecordingProductAccountClient();
        ProductTransferCoordinator coordinator = new ProductTransferCoordinator(accountClient);

        assertThatThrownBy(() -> coordinator.transfer(
                command("transfer-006", "USDT_PERPETUAL", "USDT_PERPETUAL", 10L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same account");
        assertThat(accountClient.calls()).isEmpty();
    }

    @Test
    void distinctIdempotencyKeysProduceDistinctRuntimeTransferIds() {
        RecordingProductAccountClient accountClient = new RecordingProductAccountClient();
        ProductTransferCoordinator coordinator = new ProductTransferCoordinator(accountClient);

        ProductTransferResult first = coordinator.transfer(new ProductTransferCommand(
                42L, "transfer-007", "FUNDING", "USDT_PERPETUAL",
                "USDT", 10L, "same-reference", "test"));
        ProductTransferResult second = coordinator.transfer(new ProductTransferCommand(
                42L, "transfer-008", "FUNDING", "USDT_PERPETUAL",
                "USDT", 10L, "same-reference", "test"));

        assertThat(first.transferId()).isNotEqualTo(second.transferId());
    }

    private ProductTransferCommand command(String key, String source, String target, long amount) {
        return new ProductTransferCommand(42L, key, source, target, "USDT", amount, key, "test transfer");
    }

    private static final class RecordingProductAccountClient implements ProductAccountClient {
        private final List<TransferCall> calls = new ArrayList<>();
        private final EnumMap<ProductLine, List<ProductTransferOperationRequest>> pending =
                new EnumMap<>(ProductLine.class);
        private boolean rejectNextTransferIn;

        @Override
        public ProductAccountAdjustment transferOut(String accountType, ProductTransferOperationRequest request) {
            calls.add(new TransferCall("OUT", accountType, request));
            return ProductAccountAdjustment.applied("applied");
        }

        @Override
        public ProductAccountAdjustment transferIn(String accountType, ProductTransferOperationRequest request) {
            calls.add(new TransferCall("IN", accountType, request));
            if (rejectNextTransferIn) {
                rejectNextTransferIn = false;
                return ProductAccountAdjustment.rejected("rejected");
            }
            return ProductAccountAdjustment.applied("applied");
        }

        @Override
        public ProductAccountAdjustment completeTransfer(String accountType,
                                                         ProductTransferOperationRequest request) {
            calls.add(new TransferCall("COMPLETE", accountType, request));
            List<ProductTransferOperationRequest> sourcePending = pending.get(request.sourceProductLine());
            if (sourcePending != null) sourcePending.remove(request);
            return ProductAccountAdjustment.applied("applied");
        }

        @Override
        public List<ProductTransferOperationRequest> pendingTransfers(ProductLine productLine, int limit) {
            return pending.getOrDefault(productLine, List.of()).stream().limit(limit).toList();
        }

        void rejectNextTransferIn() {
            rejectNextTransferIn = true;
        }

        void addPending(ProductTransferOperationRequest operation) {
            pending.computeIfAbsent(operation.sourceProductLine(), ignored -> new ArrayList<>()).add(operation);
        }

        void clearCalls() {
            calls.clear();
        }

        List<TransferCall> calls() {
            return calls;
        }
    }

    private record TransferCall(String phase, String accountType, ProductTransferOperationRequest operation) {
    }
}
