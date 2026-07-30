package com.surprising.account.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.provider.repository.AccountBalanceRepository;
import com.surprising.account.provider.repository.AccountDeficitRepository;
import com.surprising.account.provider.repository.AccountLedgerRepository;
import com.surprising.account.provider.repository.AdminBalanceAdjustmentRepository;
import com.surprising.account.provider.repository.ProductBalanceRepository;
import com.surprising.account.provider.repository.ProductDeficitRepository;
import com.surprising.account.provider.repository.ProductLedgerRepository;
import com.surprising.account.provider.repository.ProductTransferRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AccountQueryServiceTest {

    private AccountBalanceRepository accountBalanceRepository;
    private AccountDeficitRepository accountDeficitRepository;
    private ProductBalanceRepository productBalanceRepository;
    private ProductDeficitRepository productDeficitRepository;
    private AccountQueryService service;

    @BeforeEach
    void setUp() {
        accountBalanceRepository = mock(AccountBalanceRepository.class);
        accountDeficitRepository = mock(AccountDeficitRepository.class);
        productBalanceRepository = mock(ProductBalanceRepository.class);
        productDeficitRepository = mock(ProductDeficitRepository.class);
        service = new AccountQueryService(
                mock(AccountLedgerRepository.class),
                mock(ProductLedgerRepository.class),
                mock(ProductTransferRepository.class),
                mock(AdminBalanceAdjustmentRepository.class),
                accountBalanceRepository,
                accountDeficitRepository,
                productBalanceRepository,
                productDeficitRepository);
    }

    @Test
    void balanceAggregatesBalanceAndDeficitTables() {
        Instant updatedAt = Instant.parse("2026-07-30T00:00:00Z");
        when(accountBalanceRepository.find(1001L, "USDT"))
                .thenReturn(Optional.of(new AccountBalanceRepository.BalanceRow(
                        1001L, "USDT", 900L, 200L, updatedAt)));
        when(accountDeficitRepository.findUnits(1001L, "USDT"))
                .thenReturn(OptionalLong.of(100L));

        var response = service.balance(1001L, "USDT");

        assertThat(response).isPresent();
        assertThat(response.orElseThrow().equityUnits()).isEqualTo(1_000L);
    }

    @Test
    void productBalanceAggregatesProductBalanceAndDeficitTables() {
        Instant updatedAt = Instant.parse("2026-07-30T00:00:00Z");
        when(productBalanceRepository.find(1001L, AccountType.USDT_DELIVERY, "USDT"))
                .thenReturn(Optional.of(new ProductBalanceRepository.ProductBalanceRow(
                        1001L, AccountType.USDT_DELIVERY, "USDT", 800L, 300L, updatedAt)));
        when(productDeficitRepository.findUnits(1001L, AccountType.USDT_DELIVERY, "USDT"))
                .thenReturn(OptionalLong.of(50L));

        var response = service.productBalance(1001L, AccountType.USDT_DELIVERY, "USDT");

        assertThat(response).isPresent();
        assertThat(response.orElseThrow().equityUnits()).isEqualTo(1_050L);
    }

    @Test
    void allProductBalancesCombineLegacyAndIsolatedAccountsWithoutDuplicateLegacyRows() {
        Instant updatedAt = Instant.parse("2026-07-30T00:00:00Z");
        when(accountBalanceRepository.findByUser(1001L)).thenReturn(List.of(
                new AccountBalanceRepository.BalanceRow(1001L, "USDT", 900L, 100L, updatedAt)));
        when(accountDeficitRepository.findByUser(1001L)).thenReturn(List.of());
        when(productBalanceRepository.findByUser(1001L, null)).thenReturn(List.of(
                new ProductBalanceRepository.ProductBalanceRow(
                        1001L, AccountType.USDT_PERPETUAL, "USDT", 1L, 0L, updatedAt),
                new ProductBalanceRepository.ProductBalanceRow(
                        1001L, AccountType.USDT_DELIVERY, "USDT", 700L, 200L, updatedAt)));
        when(productDeficitRepository.findByUser(1001L, null)).thenReturn(List.of(
                new ProductDeficitRepository.ProductDeficitRow(
                        AccountType.USDT_DELIVERY, "USDT", 25L)));

        var responses = service.productBalances(1001L, null);

        assertThat(responses).extracting(response -> response.accountType())
                .containsExactly(AccountType.USDT_PERPETUAL, AccountType.USDT_DELIVERY);
        assertThat(responses).extracting(response -> response.equityUnits())
                .containsExactly(1_000L, 875L);
    }
}
