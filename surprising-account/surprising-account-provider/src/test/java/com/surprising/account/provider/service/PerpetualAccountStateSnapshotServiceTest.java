package com.surprising.account.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.surprising.account.provider.repository.AccountProductBalanceRepository;
import com.surprising.account.provider.repository.AccountProductDeficitRepository;
import com.surprising.account.provider.repository.AccountRiskStateRevisionRepository;
import com.surprising.account.provider.repository.AccountStateOrderLockRepository;
import com.surprising.account.provider.repository.AccountSequenceRepository;
import com.surprising.account.provider.repository.PositionMarginRepository;
import com.surprising.account.provider.repository.PositionModeRepository;
import com.surprising.account.provider.repository.PositionRepository;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PerpetualAccountStateSnapshotServiceTest {

    @Test
    void restoresOneCompleteVersionedUserSnapshotFromDatabase() {
        AccountProductBalanceRepository balances = mock(AccountProductBalanceRepository.class);
        AccountProductDeficitRepository deficits = mock(AccountProductDeficitRepository.class);
        AccountStateOrderLockRepository orderLocks = mock(AccountStateOrderLockRepository.class);
        PositionMarginRepository margins = mock(PositionMarginRepository.class);
        PositionRepository positions = mock(PositionRepository.class);
        PositionModeRepository modes = mock(PositionModeRepository.class);
        AccountSequenceRepository sequences = mock(AccountSequenceRepository.class);
        AccountRiskStateRevisionRepository revisions = mock(AccountRiskStateRevisionRepository.class);
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        when(balances.findByUser(com.surprising.account.api.model.AccountType.USDT_PERPETUAL, 1001L))
                .thenReturn(List.of(new AccountProductBalanceRepository.BalanceRow(
                        com.surprising.account.api.model.AccountType.USDT_PERPETUAL,
                        1001L, "USDT", 90L, 10L, now)));
        when(deficits.findByUser(com.surprising.account.api.model.AccountType.USDT_PERPETUAL, 1001L))
                .thenReturn(List.of(new AccountProductDeficitRepository.DeficitRow(
                        com.surprising.account.api.model.AccountType.USDT_PERPETUAL,
                        1001L, "USDT", 5L, 2L, now)));
        when(orderLocks.findByUser(ProductLine.LINEAR_PERPETUAL, 1001L))
                .thenReturn(List.of(new AccountStateOrderLockRepository.LockProjectionRow(
                        "USDT", 30L, now)));
        when(margins.findByUser(ProductLine.LINEAR_PERPETUAL, 1001L)).thenReturn(List.of(
                new PositionMarginRepository.PositionMarginRow("BTC-USDT", "USDT", MarginMode.ISOLATED,
                        PositionSide.NET, 20L, now)));
        when(positions.findSnapshotByUser(ProductLine.LINEAR_PERPETUAL, 1001L)).thenReturn(List.of(
                new PositionRepository.PositionSnapshotRow("BTC-USDT", MarginMode.CROSS, PositionSide.NET,
                        3L, 2L, 100L, 200L, 0L, now)));
        when(modes.find(ProductLine.LINEAR_PERPETUAL, 1001L)).thenReturn(
                java.util.Optional.of(new PositionModeRepository.PositionModeRow(PositionMode.ONE_WAY, now)));
        when(sequences.nextAccountStateEventId()).thenReturn(88L);
        when(revisions.current(ProductLine.LINEAR_PERPETUAL, 1001L)).thenReturn(7L);

        var service = new PerpetualAccountStateSnapshotService(
                balances, deficits, orderLocks, margins, positions, modes, sequences, revisions);
        var event = service.snapshot(ProductLine.LINEAR_PERPETUAL, 1001L);

        assertThat(event.eventId()).isEqualTo(88L);
        assertThat(event.accountRevision()).isEqualTo(7L);
        assertThat(event.positions()).hasSize(1);
        assertThat(event.orderLocks().getFirst().lockedUnits()).isEqualTo(30L);
    }
}
