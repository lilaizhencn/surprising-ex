package com.surprising.account.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent;
import com.surprising.account.provider.repository.AccountBalanceRepository;
import com.surprising.account.provider.repository.AccountDeficitRepository;
import com.surprising.account.provider.repository.AccountRiskStateRevisionRepository;
import com.surprising.account.provider.repository.AccountStateOrderLockRepository;
import com.surprising.account.provider.repository.PositionMarginRepository;
import com.surprising.account.provider.repository.PositionModeRepository;
import com.surprising.account.provider.repository.PositionRepository;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.PositionMode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AccountStateProjectionServiceTest {

    @Test
    void projectsWholeSnapshotOnlyAfterRevisionFenceIsAcquired() {
        AccountBalanceRepository balances = mock(AccountBalanceRepository.class);
        AccountDeficitRepository deficits = mock(AccountDeficitRepository.class);
        PositionRepository positions = mock(PositionRepository.class);
        PositionMarginRepository margins = mock(PositionMarginRepository.class);
        PositionModeRepository modes = mock(PositionModeRepository.class);
        AccountStateOrderLockRepository locks = mock(AccountStateOrderLockRepository.class);
        AccountRiskStateRevisionRepository revisions = mock(AccountRiskStateRevisionRepository.class);
        Instant eventTime = Instant.parse("2026-08-02T00:00:00Z");
        PerpetualAccountStateUpdatedEvent event = event(9L, eventTime);
        when(revisions.beginProjection(ProductLine.LINEAR_PERPETUAL, 1001L, 9L, eventTime))
                .thenReturn(true);

        AccountStateProjectionService service = new AccountStateProjectionService(
                balances, deficits, positions, margins, modes, locks, revisions);

        assertThat(service.project(event)).isTrue();

        verify(balances).replaceProjection(eq(1001L), eq(event.balances()), eq(eventTime));
        verify(deficits).replaceProjection(eq(1001L), eq(event.deficits()), eq(eventTime));
        verify(positions).replaceProjection(ProductLine.LINEAR_PERPETUAL, 1001L,
                event.positions(), eventTime);
        verify(margins).replaceProjection(ProductLine.LINEAR_PERPETUAL, 1001L,
                event.positionMargins(), eventTime);
        verify(modes).upsert(ProductLine.LINEAR_PERPETUAL, 1001L, PositionMode.ONE_WAY, eventTime);
        verify(locks).replaceProjection(eq(ProductLine.LINEAR_PERPETUAL), eq(1001L), any(), eq(eventTime));
    }

    @Test
    void staleOrDuplicateRevisionDoesNotTouchProjectionTables() {
        AccountRiskStateRevisionRepository revisions = mock(AccountRiskStateRevisionRepository.class);
        when(revisions.beginProjection(eq(ProductLine.LINEAR_PERPETUAL), eq(1001L), eq(8L), any()))
                .thenReturn(false);
        AccountBalanceRepository balances = mock(AccountBalanceRepository.class);
        AccountDeficitRepository deficits = mock(AccountDeficitRepository.class);
        PositionRepository positions = mock(PositionRepository.class);
        PositionMarginRepository margins = mock(PositionMarginRepository.class);
        PositionModeRepository modes = mock(PositionModeRepository.class);
        AccountStateOrderLockRepository locks = mock(AccountStateOrderLockRepository.class);
        AccountStateProjectionService service = new AccountStateProjectionService(
                balances, deficits, positions, margins, modes, locks, revisions);

        assertThat(service.project(event(8L, Instant.parse("2026-08-02T00:00:00Z")))).isFalse();
        verifyNoInteractions(balances, deficits, positions, margins, modes, locks);
    }

    private PerpetualAccountStateUpdatedEvent event(long revision, Instant eventTime) {
        return new PerpetualAccountStateUpdatedEvent(
                PerpetualAccountStateUpdatedEvent.CURRENT_SCHEMA_VERSION,
                revision,
                revision,
                ProductLine.LINEAR_PERPETUAL,
                1001L,
                "USDT_PERPETUAL",
                List.of(new PerpetualAccountStateUpdatedEvent.Balance("USDT", 90L, 10L)),
                List.of(new PerpetualAccountStateUpdatedEvent.Deficit("USDT", 0L, 0L)),
                List.of(), List.of(), List.of(), PositionMode.ONE_WAY, eventTime, "test");
    }
}
