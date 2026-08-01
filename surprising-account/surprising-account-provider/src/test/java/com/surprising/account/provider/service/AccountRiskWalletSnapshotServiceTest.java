package com.surprising.account.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.account.api.model.AccountRiskWalletUpdatedEvent;
import com.surprising.account.provider.repository.AccountBalanceRepository;
import com.surprising.account.provider.repository.AccountDeficitRepository;
import com.surprising.account.provider.repository.AccountOrderLockRepository;
import com.surprising.account.provider.repository.AccountOutboxRepository;
import com.surprising.account.provider.repository.AccountRiskStateRevisionRepository;
import com.surprising.account.provider.repository.AccountSequenceRepository;
import com.surprising.account.provider.repository.PositionMarginRepository;
import com.surprising.account.provider.repository.PositionRepository;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AccountRiskWalletSnapshotServiceTest {

    @Test
    void publishesWalletAfterSubtractingDeficitMarginAndOrderLock() {
        AccountBalanceRepository balances = mock(AccountBalanceRepository.class);
        AccountDeficitRepository deficits = mock(AccountDeficitRepository.class);
        AccountOrderLockRepository orderLocks = mock(AccountOrderLockRepository.class);
        PositionMarginRepository margins = mock(PositionMarginRepository.class);
        PositionRepository positions = mock(PositionRepository.class);
        InstrumentSnapshotCache instruments = mock(InstrumentSnapshotCache.class);
        AccountRiskStateRevisionRepository revisions = mock(AccountRiskStateRevisionRepository.class);
        AccountSequenceRepository sequences = mock(AccountSequenceRepository.class);
        AccountOutboxRepository outbox = mock(AccountOutboxRepository.class);
        when(balances.findByUser(1001L)).thenReturn(List.of(
                new AccountBalanceRepository.BalanceRow(1001L, "USDT", 100_000L, 20_000L, Instant.now())));
        when(deficits.findByUser(1001L)).thenReturn(List.of(new AccountDeficitRepository.DeficitRow("USDT", 3_000L)));
        when(margins.sumOpenIsolatedByAsset(ProductLine.LINEAR_PERPETUAL, 1001L))
                .thenReturn(Map.of("USDT", 4_000L));
        when(orderLocks.sumOpenIsolatedByAsset(ProductLine.LINEAR_PERPETUAL, 1001L,
                com.surprising.account.api.model.AccountType.USDT_PERPETUAL))
                .thenReturn(Map.of("USDT", 5_000L));
        when(revisions.next(any(), any(Long.class), any())).thenReturn(7L);
        when(sequences.nextRiskWalletEventId()).thenReturn(8L);

        AccountRiskWalletSnapshotService service = new AccountRiskWalletSnapshotService(
                balances, deficits, orderLocks, margins, positions, instruments, revisions, sequences, outbox,
                new ObjectMapper());
        List<AccountRiskWalletUpdatedEvent> events = service.publish(ProductLine.LINEAR_PERPETUAL, 1001L,
                "surprising.linear-perp.account.risk-wallet.events.v1", Instant.now(), "trace");

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.walletBalanceUnits()).isEqualTo(108_000L);
            assertThat(event.accountRevision()).isEqualTo(7L);
        });
        verify(outbox).insert(any(), any(), any(Long.class), any(), any(), any(), any(), any());
    }
}
