package com.surprising.account.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.account.api.model.PositionModeResponse;
import com.surprising.account.provider.repository.PositionModeRepository;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.PositionMode;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PositionModeCommandServiceTest {

    @Test
    void skipsGuardChecksWhenModeDoesNotChange() {
        PositionModeRepository repository = mock(PositionModeRepository.class);
        PositionModeSwitchGuard guard = mock(PositionModeSwitchGuard.class);
        PositionModeCommandService service = new PositionModeCommandService(repository, guard);
        Instant now = Instant.parse("2026-07-30T00:00:00Z");
        when(repository.find(ProductLine.LINEAR_PERPETUAL, 1001L))
                .thenReturn(Optional.of(new PositionModeRepository.PositionModeRow(
                        PositionMode.ONE_WAY, now.minusSeconds(60))));

        PositionModeResponse response = service.update(
                ProductLine.LINEAR_PERPETUAL, 1001L, PositionMode.ONE_WAY, now);

        assertThat(response.positionMode()).isEqualTo(PositionMode.ONE_WAY);
        verify(guard).lock(ProductLine.LINEAR_PERPETUAL, 1001L);
        verify(guard, never()).requireSwitchable(ProductLine.LINEAR_PERPETUAL, 1001L);
        verify(repository, never()).upsert(
                ProductLine.LINEAR_PERPETUAL, 1001L, PositionMode.ONE_WAY, now);
    }

    @Test
    void checksAllGuardsBeforeChangingMode() {
        PositionModeRepository repository = mock(PositionModeRepository.class);
        PositionModeSwitchGuard guard = mock(PositionModeSwitchGuard.class);
        PositionModeCommandService service = new PositionModeCommandService(repository, guard);
        Instant now = Instant.parse("2026-07-30T00:00:00Z");
        when(repository.find(ProductLine.OPTION, 1001L)).thenReturn(Optional.empty());
        when(repository.upsert(ProductLine.OPTION, 1001L, PositionMode.HEDGE, now)).thenReturn(1);

        PositionModeResponse response = service.update(
                ProductLine.OPTION, 1001L, PositionMode.HEDGE, now);

        assertThat(response.productLine()).isEqualTo(ProductLine.OPTION);
        assertThat(response.positionMode()).isEqualTo(PositionMode.HEDGE);
        verify(guard).lock(ProductLine.OPTION, 1001L);
        verify(guard).requireSwitchable(ProductLine.OPTION, 1001L);
        verify(repository).upsert(ProductLine.OPTION, 1001L, PositionMode.HEDGE, now);
    }
}
