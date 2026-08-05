package com.surprising.gateway.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProductTransferCoordinatorTest {

    @Test
    void transferDebitsAndCreditsOnceAndDuplicateKeyReturnsSameTransfer() {
        InMemoryProductTransferStore store = new InMemoryProductTransferStore();
        RecordingProductAccountClient accountClient = new RecordingProductAccountClient();
        ProductTransferCoordinator coordinator = new ProductTransferCoordinator(store, accountClient);
        ProductTransferCommand command = command("transfer-001", "FUNDING", "USDT_PERPETUAL", 1_250_000L);

        ProductTransferResult first = coordinator.transfer(command);
        ProductTransferResult duplicate = coordinator.transfer(command);

        assertThat(first.transferId()).isEqualTo(duplicate.transferId());
        assertThat(first.status()).isEqualTo(ProductTransferStatus.COMPLETED);
        assertThat(duplicate.status()).isEqualTo(ProductTransferStatus.COMPLETED);
        assertThat(accountClient.calls()).containsExactly(
                new AdjustmentCall("SPOT", -1_250_000L, "transfer-001:debit"),
                new AdjustmentCall("USDT_PERPETUAL", 1_250_000L, "transfer-001:credit"));
    }

    @Test
    void rejectedTargetIsCompensatedAndDoesNotReportSuccess() {
        InMemoryProductTransferStore store = new InMemoryProductTransferStore();
        RecordingProductAccountClient accountClient = new RecordingProductAccountClient();
        accountClient.reject("USDT_PERPETUAL");
        ProductTransferCoordinator coordinator = new ProductTransferCoordinator(store, accountClient);

        ProductTransferResult result = coordinator.transfer(
                command("transfer-002", "FUNDING", "USDT_PERPETUAL", 10L));

        assertThat(result.status()).isEqualTo(ProductTransferStatus.FAILED);
        assertThat(accountClient.calls()).containsExactly(
                new AdjustmentCall("SPOT", -10L, "transfer-002:debit"),
                new AdjustmentCall("USDT_PERPETUAL", 10L, "transfer-002:credit"),
                new AdjustmentCall("SPOT", 10L, "transfer-002:compensate"));
    }

    @Test
    void unknownCompensationIsRecoverableAndNeverReportedAsCompleted() {
        InMemoryProductTransferStore store = new InMemoryProductTransferStore();
        RecordingProductAccountClient accountClient = new RecordingProductAccountClient();
        accountClient.unknown("SPOT", "transfer-003:compensate");
        accountClient.reject("USDT_PERPETUAL");
        ProductTransferCoordinator coordinator = new ProductTransferCoordinator(store, accountClient);

        ProductTransferResult result = coordinator.transfer(
                command("transfer-003", "FUNDING", "USDT_PERPETUAL", 10L));

        assertThat(result.status()).isEqualTo(ProductTransferStatus.COMPENSATION_REQUIRED);
        assertThat(result.status()).isNotEqualTo(ProductTransferStatus.COMPLETED);
    }

    @Test
    void reusingKeyWithDifferentRequestIsRejected() {
        InMemoryProductTransferStore store = new InMemoryProductTransferStore();
        ProductTransferCoordinator coordinator = new ProductTransferCoordinator(
                store, new RecordingProductAccountClient());
        coordinator.transfer(command("transfer-004", "FUNDING", "USDT_PERPETUAL", 10L));

        assertThatThrownBy(() -> coordinator.transfer(
                command("transfer-004", "FUNDING", "USDT_PERPETUAL", 11L)))
                .isInstanceOf(ProductTransferConflictException.class)
                .hasMessageContaining("idempotency key");
    }

    @Test
    void sameUnderlyingFundingAndSpotAreRejectedBeforeProviderCall() {
        RecordingProductAccountClient accountClient = new RecordingProductAccountClient();
        ProductTransferCoordinator coordinator = new ProductTransferCoordinator(
                new InMemoryProductTransferStore(), accountClient);

        assertThatThrownBy(() -> coordinator.transfer(
                command("transfer-005", "FUNDING", "SPOT", 10L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same account");
        assertThat(accountClient.calls()).isEmpty();
    }

    @Test
    void sameDerivativeAccountIsRejectedBeforeProviderCall() {
        RecordingProductAccountClient accountClient = new RecordingProductAccountClient();
        ProductTransferCoordinator coordinator = new ProductTransferCoordinator(
                new InMemoryProductTransferStore(), accountClient);

        assertThatThrownBy(() -> coordinator.transfer(
                command("transfer-006", "USDT_PERPETUAL", "USDT_PERPETUAL", 10L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same account");
        assertThat(accountClient.calls()).isEmpty();
    }

    private ProductTransferCommand command(String key, String source, String target, long amount) {
        return new ProductTransferCommand(42L, key, source, target, "USDT", amount, key, "test transfer");
    }

    private static final class InMemoryProductTransferStore implements ProductTransferStore {
        private final Map<Long, ProductTransferState> rows = new HashMap<>();
        private long nextId = 100L;

        @Override
        public ProductTransferState createOrGet(ProductTransferCreateRequest request) {
            return rows.values().stream()
                    .filter(row -> row.userId() == request.userId()
                            && row.idempotencyKey().equals(request.idempotencyKey()))
                    .findFirst()
                    .orElseGet(() -> {
                        ProductTransferState row = ProductTransferState.pending(
                                nextId++, request, Instant.parse("2026-08-05T00:00:00Z"));
                        rows.put(row.transferId(), row);
                        return row;
                    });
        }

        @Override
        public ProductTransferState lock(long transferId) {
            return rows.get(transferId);
        }

        @Override
        public ProductTransferState update(ProductTransferState state) {
            rows.put(state.transferId(), state);
            return state;
        }
    }

    private static final class RecordingProductAccountClient implements ProductAccountClient {
        private final java.util.List<AdjustmentCall> calls = new java.util.ArrayList<>();
        private final java.util.Set<String> rejectedAccounts = new java.util.HashSet<>();
        private final java.util.Set<String> unknownCalls = new java.util.HashSet<>();

        @Override
        public ProductAccountAdjustment adjust(String accountType, long amountUnits, String referenceId,
                                                String reason, long userId, String asset) {
            calls.add(new AdjustmentCall(accountType, amountUnits, referenceId));
            if (unknownCalls.contains(accountType + ":" + referenceId)) {
                return ProductAccountAdjustment.unknown("unknown");
            }
            if (rejectedAccounts.contains(accountType)) {
                return ProductAccountAdjustment.rejected("rejected");
            }
            return ProductAccountAdjustment.applied("applied");
        }

        void reject(String accountType) {
            rejectedAccounts.add(accountType);
        }

        void unknown(String accountType, String referenceId) {
            unknownCalls.add(accountType + ":" + referenceId);
        }

        java.util.List<AdjustmentCall> calls() {
            return calls;
        }
    }

    private record AdjustmentCall(String accountType, long amountUnits, String referenceId) {
    }
}
