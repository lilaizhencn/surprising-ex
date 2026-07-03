package com.surprising.account.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.AdminBalanceAdjustmentRecord;
import com.surprising.account.api.model.ProductBalanceAdjustmentRequest;
import com.surprising.account.api.model.ProductBalanceResponse;
import com.surprising.account.api.model.ProductTransferRequest;
import com.surprising.account.api.model.ProductTransferResponse;
import com.surprising.account.provider.repository.AccountRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProductAccountServiceTest {

    @Test
    void missingProductBalanceReturnsZeroWithoutCreatingLegacyBalance() {
        FakeProductAccountRepository repository = new FakeProductAccountRepository();
        AccountService service = new AccountService(repository, new PositionCalculator());

        ProductBalanceResponse response = service.productBalance(1001L, AccountType.SPOT, "usdt");

        assertThat(response.userId()).isEqualTo(1001L);
        assertThat(response.accountType()).isEqualTo(AccountType.SPOT);
        assertThat(response.asset()).isEqualTo("USDT");
        assertThat(response.availableUnits()).isZero();
        assertThat(response.lockedUnits()).isZero();
        assertThat(response.equityUnits()).isZero();
        assertThat(repository.balances).isEmpty();
    }

    @Test
    void productBalanceAdjustmentNormalizesAssetAndReference() {
        FakeProductAccountRepository repository = new FakeProductAccountRepository();
        AccountService service = new AccountService(repository, new PositionCalculator());

        ProductBalanceResponse response = service.adjustProductBalance(new ProductBalanceAdjustmentRequest(
                1001L, AccountType.FUNDING, "usdt", 1_000L, " deposit-1 ", "INITIAL_DEPOSIT"));

        assertThat(response.accountType()).isEqualTo(AccountType.FUNDING);
        assertThat(response.asset()).isEqualTo("USDT");
        assertThat(response.availableUnits()).isEqualTo(1_000L);
        assertThat(repository.adjustmentReferences).containsExactly("deposit-1");
    }

    @Test
    void adminProductBalanceAdjustmentRecordsGatewayIdentity() {
        FakeProductAccountRepository repository = new FakeProductAccountRepository();
        AccountService service = new AccountService(repository, new PositionCalculator());

        ProductBalanceResponse response = service.adminAdjustProductBalance(" 42 ", " risk-admin ",
                new ProductBalanceAdjustmentRequest(1001L, AccountType.FUNDING, "usdt", 1_000L,
                        " manual-credit-1 ", "MANUAL_CREDIT"));

        assertThat(response.availableUnits()).isEqualTo(1_000L);
        assertThat(repository.adminAdjustments).singleElement().satisfies(record -> {
            assertThat(record.adjustmentKind()).isEqualTo("PRODUCT");
            assertThat(record.adminUserId()).isEqualTo(42L);
            assertThat(record.adminUsername()).isEqualTo("risk-admin");
            assertThat(record.userId()).isEqualTo(1001L);
            assertThat(record.accountType()).isEqualTo(AccountType.FUNDING);
            assertThat(record.asset()).isEqualTo("USDT");
            assertThat(record.amountUnits()).isEqualTo(1_000L);
            assertThat(record.balanceAfterUnits()).isEqualTo(1_000L);
            assertThat(record.referenceId()).isEqualTo("manual-credit-1");
            assertThat(record.reason()).isEqualTo("MANUAL_CREDIT");
        });
    }

    @Test
    void transferMovesAvailableUnitsBetweenIsolatedProductAccounts() {
        FakeProductAccountRepository repository = new FakeProductAccountRepository();
        repository.put(1001L, AccountType.FUNDING, "USDT", 2_000L);
        AccountService service = new AccountService(repository, new PositionCalculator());

        ProductTransferResponse response = service.transfer(new ProductTransferRequest(
                1001L, AccountType.FUNDING, AccountType.SPOT, "usdt", 750L,
                " transfer-spot-1 ", "USER_TRANSFER"));

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.sourceBalance().availableUnits()).isEqualTo(1_250L);
        assertThat(response.targetBalance().availableUnits()).isEqualTo(750L);
        assertThat(repository.balance(1001L, AccountType.FUNDING, "USDT").availableUnits()).isEqualTo(1_250L);
        assertThat(repository.balance(1001L, AccountType.SPOT, "USDT").availableUnits()).isEqualTo(750L);
        assertThat(repository.transferReferences).containsExactly("transfer-spot-1");
    }

    @Test
    void transferRejectsSameProductAccountBeforeRepositoryMutation() {
        FakeProductAccountRepository repository = new FakeProductAccountRepository();
        AccountService service = new AccountService(repository, new PositionCalculator());

        assertThatThrownBy(() -> service.transfer(new ProductTransferRequest(
                1001L, AccountType.SPOT, AccountType.SPOT, "USDT", 1L, "same-account", "USER_TRANSFER")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be different");
        assertThat(repository.transferReferences).isEmpty();
    }

    private static final class FakeProductAccountRepository extends AccountRepository {
        private final Map<Long, EnumMap<AccountType, Map<String, ProductBalanceResponse>>> balances = new HashMap<>();
        private final List<String> adjustmentReferences = new ArrayList<>();
        private final List<AdminBalanceAdjustmentRecord> adminAdjustments = new ArrayList<>();
        private final List<String> transferReferences = new ArrayList<>();
        private long transferId;

        private FakeProductAccountRepository() {
            super(null, null);
        }

        @Override
        public Optional<ProductBalanceResponse> productBalance(long userId, AccountType accountType, String asset) {
            return Optional.ofNullable(balanceOrNull(userId, accountType, asset));
        }

        @Override
        public List<ProductBalanceResponse> productBalances(long userId, AccountType accountType) {
            EnumMap<AccountType, Map<String, ProductBalanceResponse>> byType = balances.get(userId);
            if (byType == null) {
                return List.of();
            }
            if (accountType != null) {
                return List.copyOf(byType.getOrDefault(accountType, Map.of()).values());
            }
            return byType.values().stream()
                    .flatMap(values -> values.values().stream())
                    .toList();
        }

        @Override
        public ProductBalanceResponse adjustProductBalance(long userId,
                                                           AccountType accountType,
                                                           String asset,
                                                           long amountUnits,
                                                           String referenceId,
                                                           String reason) {
            adjustmentReferences.add(referenceId);
            ProductBalanceResponse current = balance(userId, accountType, asset);
            ProductBalanceResponse next = new ProductBalanceResponse(userId, accountType, asset,
                    Math.addExact(current.availableUnits(), amountUnits), current.lockedUnits(),
                    Math.addExact(current.equityUnits(), amountUnits), Instant.now());
            put(next);
            return next;
        }

        @Override
        public AdminBalanceAdjustmentRecord recordAdminBalanceAdjustment(String adjustmentKind,
                                                                         long adminUserId,
                                                                         String adminUsername,
                                                                         long userId,
                                                                         AccountType accountType,
                                                                         String asset,
                                                                         long amountUnits,
                                                                         long balanceAfterUnits,
                                                                         String referenceId,
                                                                         String reason) {
            AdminBalanceAdjustmentRecord record = new AdminBalanceAdjustmentRecord(adminAdjustments.size() + 1L,
                    adjustmentKind, adminUserId, adminUsername, userId, accountType, asset, amountUnits,
                    balanceAfterUnits, referenceId, reason, Instant.now());
            adminAdjustments.add(record);
            return record;
        }

        @Override
        public ProductTransferResponse transferProductBalance(long userId,
                                                              AccountType sourceAccountType,
                                                              AccountType targetAccountType,
                                                              String asset,
                                                              long amountUnits,
                                                              String referenceId,
                                                              String reason) {
            transferReferences.add(referenceId);
            ProductBalanceResponse source = balance(userId, sourceAccountType, asset);
            if (source.availableUnits() < amountUnits) {
                throw new IllegalArgumentException("insufficient available balance");
            }
            ProductBalanceResponse nextSource = new ProductBalanceResponse(userId, sourceAccountType, asset,
                    source.availableUnits() - amountUnits, source.lockedUnits(), source.equityUnits() - amountUnits,
                    Instant.now());
            ProductBalanceResponse target = balance(userId, targetAccountType, asset);
            ProductBalanceResponse nextTarget = new ProductBalanceResponse(userId, targetAccountType, asset,
                    target.availableUnits() + amountUnits, target.lockedUnits(), target.equityUnits() + amountUnits,
                    Instant.now());
            put(nextSource);
            put(nextTarget);
            return new ProductTransferResponse(++transferId, userId, sourceAccountType, targetAccountType, asset,
                    amountUnits, referenceId, "COMPLETED", nextSource, nextTarget, Instant.now());
        }

        private void put(long userId, AccountType accountType, String asset, long availableUnits) {
            put(new ProductBalanceResponse(userId, accountType, asset, availableUnits, 0L, availableUnits,
                    Instant.now()));
        }

        private void put(ProductBalanceResponse balance) {
            balances.computeIfAbsent(balance.userId(), ignored -> new EnumMap<>(AccountType.class))
                    .computeIfAbsent(balance.accountType(), ignored -> new HashMap<>())
                    .put(balance.asset(), balance);
        }

        private ProductBalanceResponse balance(long userId, AccountType accountType, String asset) {
            ProductBalanceResponse current = balanceOrNull(userId, accountType, asset);
            return current == null
                    ? new ProductBalanceResponse(userId, accountType, asset, 0L, 0L, 0L, Instant.EPOCH)
                    : current;
        }

        private ProductBalanceResponse balanceOrNull(long userId, AccountType accountType, String asset) {
            return balances.getOrDefault(userId, new EnumMap<>(AccountType.class))
                    .getOrDefault(accountType, Map.of())
                    .get(asset);
        }
    }
}
