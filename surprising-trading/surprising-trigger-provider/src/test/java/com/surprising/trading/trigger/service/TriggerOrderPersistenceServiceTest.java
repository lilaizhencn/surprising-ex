package com.surprising.trading.trigger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.trigger.repository.TriggerCoordinationRepository;
import com.surprising.trading.trigger.repository.TriggerOpenOrderRepository;
import com.surprising.trading.trigger.repository.TriggerOrderRepository;
import com.surprising.trading.trigger.repository.TriggerPositionModeRepository;
import com.surprising.trading.trigger.repository.TriggerPositionRepository;
import com.surprising.trading.trigger.repository.TriggerSequenceRepository;
import org.junit.jupiter.api.Test;

class TriggerOrderPersistenceServiceTest {

    @Test
    void marginModeConflictIsAggregatedAcrossSingleTableRepositories() {
        TriggerOrderRepository triggerOrderRepository = mock(TriggerOrderRepository.class);
        TriggerPositionRepository positionRepository = mock(TriggerPositionRepository.class);
        TriggerOpenOrderRepository openOrderRepository = mock(TriggerOpenOrderRepository.class);
        TriggerOrderPersistenceService service = new TriggerOrderPersistenceService(
                triggerOrderRepository,
                mock(TriggerSequenceRepository.class),
                mock(TriggerCoordinationRepository.class),
                mock(TriggerPositionModeRepository.class),
                positionRepository,
                openOrderRepository);
        when(positionRepository.hasActiveMarginModeConflict(
                ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", MarginMode.CROSS)).thenReturn(false);
        when(openOrderRepository.hasActiveMarginModeConflict(
                ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", MarginMode.CROSS)).thenReturn(false);
        when(triggerOrderRepository.hasActiveMarginModeConflict(
                ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", MarginMode.CROSS)).thenReturn(true);

        boolean conflict = service.hasActiveMarginModeConflict(
                ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", MarginMode.CROSS);

        assertThat(conflict).isTrue();
        verify(positionRepository).hasActiveMarginModeConflict(
                ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", MarginMode.CROSS);
        verify(openOrderRepository).hasActiveMarginModeConflict(
                ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", MarginMode.CROSS);
        verify(triggerOrderRepository).hasActiveMarginModeConflict(
                ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", MarginMode.CROSS);
    }
}
