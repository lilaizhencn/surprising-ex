package com.surprising.account.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.BalanceResponse;
import com.surprising.account.api.model.ProductBalanceResponse;
import com.surprising.account.api.model.ProductTransferResponse;
import com.surprising.account.provider.repository.AccountBalanceRepository;
import com.surprising.account.provider.repository.AccountLedgerRepository;
import com.surprising.account.provider.repository.AccountSequenceRepository;
import com.surprising.account.provider.repository.ProductBalanceRepository;
import com.surprising.account.provider.repository.ProductLedgerRepository;
import com.surprising.account.provider.repository.ProductTransferRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AccountBalanceCommandServiceTest {

    private AccountSequenceRepository sequenceRepository;
    private AccountLedgerRepository accountLedgerRepository;
    private ProductLedgerRepository productLedgerRepository;
    private ProductTransferRepository productTransferRepository;
    private AccountBalanceRepository accountBalanceRepository;
    private ProductBalanceRepository productBalanceRepository;
    private AccountQueryService accountQueryService;
    private AccountBalanceCommandService service;

    @BeforeEach
    void setUp() {
        sequenceRepository = mock(AccountSequenceRepository.class);
        accountLedgerRepository = mock(AccountLedgerRepository.class);
        productLedgerRepository = mock(ProductLedgerRepository.class);
        productTransferRepository = mock(ProductTransferRepository.class);
        accountBalanceRepository = mock(AccountBalanceRepository.class);
        productBalanceRepository = mock(ProductBalanceRepository.class);
        accountQueryService = mock(AccountQueryService.class);
        service = new AccountBalanceCommandService(
                sequenceRepository,
                accountLedgerRepository,
                productLedgerRepository,
                productTransferRepository,
                accountBalanceRepository,
                productBalanceRepository,
                accountQueryService);
    }

    @Test
    void adjustsLegacyBalanceThroughSingleTableRepositories() {
        BalanceResponse expected = new BalanceResponse(
                1001L, "USDT", 12_000L, 300L, 12_300L, Instant.parse("2026-07-30T00:00:00Z"));
        when(sequenceRepository.nextLedgerEntryId()).thenReturn(11L);
        when(accountLedgerRepository.insertBalanceAdjustment(
                eq(11L), eq(1001L), eq("USDT"), eq(2_000L), eq("adj-1"), eq("充值"), any()))
                .thenReturn(1);
        when(accountBalanceRepository.applyAvailableDelta(
                eq(1001L), eq("USDT"), eq(2_000L), any()))
                .thenReturn(12_000L);
        when(accountQueryService.balance(1001L, "USDT")).thenReturn(Optional.of(expected));
        when(accountLedgerRepository.updateBalanceAdjustmentBalance(
                1001L, "USDT", "adj-1", 12_000L))
                .thenReturn(1);

        BalanceResponse actual = service.adjustBalance(1001L, "USDT", 2_000L, "adj-1", "充值");

        assertThat(actual).isEqualTo(expected);
        verify(accountBalanceRepository).applyAvailableDelta(
                eq(1001L), eq("USDT"), eq(2_000L), any());
    }

    @Test
    void returnsExistingAdjustmentWithoutApplyingBalanceAgain() {
        BalanceResponse expected = new BalanceResponse(
                1001L, "USDT", 12_000L, 300L, 12_300L, Instant.parse("2026-07-30T00:00:00Z"));
        when(sequenceRepository.nextLedgerEntryId()).thenReturn(12L);
        when(accountLedgerRepository.insertBalanceAdjustment(
                eq(12L), eq(1001L), eq("USDT"), eq(2_000L), eq("adj-2"), eq("充值"), any()))
                .thenReturn(0);
        when(accountLedgerRepository.findBalanceAdjustment(1001L, "USDT", "adj-2"))
                .thenReturn(Optional.of(new AccountLedgerRepository.AdjustmentReference(2_000L, "充值")));
        when(accountQueryService.balance(1001L, "USDT")).thenReturn(Optional.of(expected));

        BalanceResponse actual = service.adjustBalance(1001L, "USDT", 2_000L, "adj-2", "充值");

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void transfersBalanceByAggregatingTwoBalanceRepositoriesAndLedgerRepository() {
        Instant updatedAt = Instant.parse("2026-07-30T00:00:00Z");
        ProductBalanceResponse source = new ProductBalanceResponse(
                1001L, AccountType.SPOT, "USDT", 9_000L, 0L, 9_000L, updatedAt);
        ProductBalanceResponse target = new ProductBalanceResponse(
                1001L, AccountType.COIN_PERPETUAL, "USDT", 1_000L, 0L, 1_000L, updatedAt);
        when(sequenceRepository.nextProductTransferId()).thenReturn(21L);
        when(productTransferRepository.insert(
                eq(21L), eq(1001L), eq(AccountType.SPOT), eq(AccountType.COIN_PERPETUAL),
                eq("USDT"), eq(1_000L), eq("transfer-1"), eq("划转"), any()))
                .thenReturn(1);
        when(productBalanceRepository.applyAvailableDelta(
                eq(1001L), eq(AccountType.SPOT), eq("USDT"), eq(-1_000L), any()))
                .thenReturn(9_000L);
        when(productBalanceRepository.applyAvailableDelta(
                eq(1001L), eq(AccountType.COIN_PERPETUAL), eq("USDT"), eq(1_000L), any()))
                .thenReturn(1_000L);
        when(sequenceRepository.nextProductLedgerEntryId()).thenReturn(31L, 32L);
        when(productLedgerRepository.insertTransfer(
                anyLong(), eq(1001L), any(AccountType.class), eq("USDT"), anyLong(),
                anyLong(), any(String.class), eq("划转"), any()))
                .thenReturn(1);
        when(accountQueryService.productBalance(1001L, AccountType.SPOT, "USDT"))
                .thenReturn(Optional.of(source));
        when(accountQueryService.productBalance(1001L, AccountType.COIN_PERPETUAL, "USDT"))
                .thenReturn(Optional.of(target));

        ProductTransferResponse actual = service.transferProductBalance(
                1001L, AccountType.SPOT, AccountType.COIN_PERPETUAL,
                "USDT", 1_000L, "transfer-1", "划转");

        assertThat(actual.transferId()).isEqualTo(21L);
        assertThat(actual.sourceBalance()).isEqualTo(source);
        assertThat(actual.targetBalance()).isEqualTo(target);
        verify(productLedgerRepository).insertTransfer(
                eq(31L), eq(1001L), eq(AccountType.SPOT), eq("USDT"), eq(-1_000L),
                eq(9_000L), eq("transfer-1:OUT"), eq("划转"), any());
        verify(productLedgerRepository).insertTransfer(
                eq(32L), eq(1001L), eq(AccountType.COIN_PERPETUAL), eq("USDT"), eq(1_000L),
                eq(1_000L), eq("transfer-1:IN"), eq("划转"), any());
    }
}
