package com.surprising.account.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.account.provider.repository.AccountBalanceRepository;
import com.surprising.account.provider.repository.AccountDeficitRepository;
import com.surprising.account.provider.repository.AccountOrderLockRepository;
import com.surprising.account.provider.repository.AccountOutboxRepository;
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
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PerpetualAccountStateSnapshotServiceTest {

    @Test
    void publishesOneCompleteVersionedUserSnapshot() {
        AccountBalanceRepository balances = mock(AccountBalanceRepository.class);
        AccountDeficitRepository deficits = mock(AccountDeficitRepository.class);
        AccountOrderLockRepository orderLocks = mock(AccountOrderLockRepository.class);
        PositionMarginRepository margins = mock(PositionMarginRepository.class);
        PositionRepository positions = mock(PositionRepository.class);
        PositionModeRepository modes = mock(PositionModeRepository.class);
        AccountSequenceRepository sequences = mock(AccountSequenceRepository.class);
        AccountOutboxRepository outbox = mock(AccountOutboxRepository.class);
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        when(balances.findByUser(1001L)).thenReturn(List.of(
                new AccountBalanceRepository.BalanceRow(1001L, "USDT", 90L, 10L, now)));
        when(deficits.findByUser(1001L)).thenReturn(List.of(
                new AccountDeficitRepository.DeficitRow("USDT", 5L, 2L)));
        when(orderLocks.sumOpenIsolatedByAsset(ProductLine.LINEAR_PERPETUAL, 1001L,
                com.surprising.account.api.model.AccountType.USDT_PERPETUAL))
                .thenReturn(Map.of("USDT", 30L));
        when(margins.findByUser(ProductLine.LINEAR_PERPETUAL, 1001L)).thenReturn(List.of(
                new PositionMarginRepository.PositionMarginRow("BTC-USDT", "USDT", MarginMode.ISOLATED,
                        PositionSide.NET, 20L, now)));
        when(positions.findSnapshotByUser(ProductLine.LINEAR_PERPETUAL, 1001L)).thenReturn(List.of(
                new PositionRepository.PositionSnapshotRow("BTC-USDT", MarginMode.CROSS, PositionSide.NET,
                        3L, 2L, 100L, 200L, 0L, now)));
        when(modes.find(ProductLine.LINEAR_PERPETUAL, 1001L)).thenReturn(
                java.util.Optional.of(new PositionModeRepository.PositionModeRow(PositionMode.ONE_WAY, now)));
        when(sequences.nextAccountStateEventId()).thenReturn(88L);

        var service = new PerpetualAccountStateSnapshotService(
                balances, deficits, orderLocks, margins, positions, modes, sequences, outbox, new ObjectMapper());
        var event = service.publish(ProductLine.LINEAR_PERPETUAL, 1001L, 7L,
                "surprising.linear-perp.account.state.events.v1", now, "trace");

        assertThat(event.eventId()).isEqualTo(88L);
        assertThat(event.accountRevision()).isEqualTo(7L);
        assertThat(event.positions()).hasSize(1);
        assertThat(event.orderLocks().getFirst().lockedUnits()).isEqualTo(30L);
        verify(outbox).insert(eq("LINEAR_PERPETUAL"), eq("ACCOUNT_STATE"), eq(88L),
                eq("surprising.linear-perp.account.state.events.v1"),
                eq("LINEAR_PERPETUAL:1001"), eq("ACCOUNT_STATE_UPDATED"), contains("\"accountRevision\":7"), eq(now));
    }
}
