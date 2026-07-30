package com.surprising.account.provider.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.provider.model.SpotInstrumentSpec;
import com.surprising.account.provider.repository.AccountSequenceRepository;
import com.surprising.account.provider.repository.ProductBalanceRepository;
import com.surprising.account.provider.repository.ProductLedgerRepository;
import com.surprising.account.provider.repository.SpotOrderReservationRepository;
import com.surprising.trading.api.model.OrderSide;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SpotTradeSettlementServiceTest {

    @Test
    void buySettlementAggregatesReservationBalanceAndLedgerRepositories() {
        AccountSequenceRepository sequenceRepository = mock(AccountSequenceRepository.class);
        ProductBalanceRepository balanceRepository = mock(ProductBalanceRepository.class);
        ProductLedgerRepository ledgerRepository = mock(ProductLedgerRepository.class);
        SpotOrderReservationRepository reservationRepository = mock(SpotOrderReservationRepository.class);
        SpotTradeSettlementService service = new SpotTradeSettlementService(
                sequenceRepository, balanceRepository, ledgerRepository, reservationRepository);
        Instant now = Instant.parse("2026-07-30T00:00:00Z");
        SpotInstrumentSpec spec = new SpotInstrumentSpec(7L, "BTC", "USDT", 10L, 3L);
        when(reservationRepository.lock(9001L, 1001L, "BTC-USDT"))
                .thenReturn(Optional.of(new SpotOrderReservationRepository.SpotReservationRow(
                        1001L, "BTC-USDT", OrderSide.BUY, "USDT", 350L, 0L, 0L)));
        when(balanceRepository.debitLocked(
                eq(1001L), eq(AccountType.SPOT), eq("USDT"), eq(300L), eq(now)))
                .thenReturn(1);
        when(balanceRepository.debitLocked(
                eq(1001L), eq(AccountType.SPOT), eq("USDT"), eq(3L), eq(now)))
                .thenReturn(1);
        when(balanceRepository.creditAvailable(
                eq(1001L), eq(AccountType.SPOT), eq("BTC"), eq(20L), eq(now)))
                .thenReturn(1);
        when(balanceRepository.moveLockedToAvailable(
                1001L, AccountType.SPOT, "USDT", 47L, now)).thenReturn(1);
        when(balanceRepository.equity(1001L, AccountType.SPOT, "USDT")).thenReturn(10_000L, 9_997L);
        when(balanceRepository.equity(1001L, AccountType.SPOT, "BTC")).thenReturn(20L);
        when(sequenceRepository.nextProductLedgerEntryId()).thenReturn(11L, 12L, 13L);
        when(ledgerRepository.insertSpotTrade(
                any(Long.class), eq(1001L), any(String.class), any(Long.class), any(Long.class),
                any(String.class), any(String.class), eq(now))).thenReturn(1);
        when(reservationRepository.settle(9001L, 303L, 47L, "TAKER_FEE", now)).thenReturn(1);

        service.settle(
                1001L, 9001L, 8001L, "BTC-USDT", OrderSide.BUY,
                50L, 2L, spec, 10_000L, "TAKER_FEE", true, now);

        verify(balanceRepository).debitLocked(1001L, AccountType.SPOT, "USDT", 300L, now);
        verify(balanceRepository).debitLocked(1001L, AccountType.SPOT, "USDT", 3L, now);
        verify(balanceRepository).creditAvailable(1001L, AccountType.SPOT, "BTC", 20L, now);
        verify(balanceRepository).moveLockedToAvailable(1001L, AccountType.SPOT, "USDT", 47L, now);
        verify(ledgerRepository).insertSpotTrade(
                11L, 1001L, "USDT", -300L, 10_000L,
                "8001:9001:SPOT_BUY_COST", "SPOT_BUY_COST", now);
        verify(ledgerRepository).insertSpotTrade(
                12L, 1001L, "USDT", -3L, 9_997L,
                "8001:9001:TAKER_FEE", "TAKER_FEE", now);
        verify(ledgerRepository).insertSpotTrade(
                13L, 1001L, "BTC", 20L, 20L,
                "8001:9001:SPOT_BUY_FILL", "SPOT_BUY_FILL", now);
        verify(reservationRepository).settle(9001L, 303L, 47L, "TAKER_FEE", now);
    }
}
