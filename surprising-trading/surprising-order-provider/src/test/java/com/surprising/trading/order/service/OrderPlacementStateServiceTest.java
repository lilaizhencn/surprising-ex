package com.surprising.trading.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.account.api.cache.PerpetualAccountStateSnapshotCache;
import com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionMode;
import java.time.Instant;
import java.util.List;
import com.surprising.trading.order.repository.OrderAlgoStateRepository;
import com.surprising.trading.order.repository.OrderCoordinationRepository;
import com.surprising.trading.order.repository.OrderPositionModeRepository;
import com.surprising.trading.order.repository.OrderPositionRepository;
import com.surprising.trading.order.repository.OrderRepository;
import com.surprising.trading.order.repository.OrderTriggerStateRepository;
import org.junit.jupiter.api.Test;

class OrderPlacementStateServiceTest {

    @Test
    void perpetualPositionModeComesFromReadyAccountSnapshot() {
        OrderPositionModeRepository modeRepository = org.mockito.Mockito.mock(OrderPositionModeRepository.class);
        PerpetualAccountStateSnapshotCache cache = new PerpetualAccountStateSnapshotCache();
        PerpetualAccountStateUpdatedEvent event = new PerpetualAccountStateUpdatedEvent(
                PerpetualAccountStateUpdatedEvent.CURRENT_SCHEMA_VERSION, 11L, 7L,
                ProductLine.LINEAR_PERPETUAL, 1001L, "USDT_PERPETUAL",
                List.of(), List.of(), List.of(), List.of(), List.of(), PositionMode.HEDGE,
                Instant.parse("2026-07-01T00:00:00Z"), "trace");
        cache.apply(event);
        cache.markReady();
        OrderPlacementStateService service = new OrderPlacementStateService(
                org.mockito.Mockito.mock(OrderCoordinationRepository.class), modeRepository,
                org.mockito.Mockito.mock(OrderPositionRepository.class),
                org.mockito.Mockito.mock(OrderRepository.class),
                org.mockito.Mockito.mock(OrderTriggerStateRepository.class),
                org.mockito.Mockito.mock(OrderAlgoStateRepository.class), cache);

        assertThat(service.positionMode(ProductLine.LINEAR_PERPETUAL, 1001L)).isEqualTo(PositionMode.HEDGE);
        org.mockito.Mockito.verifyNoInteractions(modeRepository);
    }

    @Test
    void marginModeConflictIsAggregatedInServiceAcrossSingleTableRepositories() {
        OrderCoordinationRepository coordinationRepository =
                org.mockito.Mockito.mock(OrderCoordinationRepository.class);
        OrderPositionModeRepository positionModeRepository =
                org.mockito.Mockito.mock(OrderPositionModeRepository.class);
        OrderPositionRepository positionRepository =
                org.mockito.Mockito.mock(OrderPositionRepository.class);
        OrderRepository orderRepository = org.mockito.Mockito.mock(OrderRepository.class);
        OrderTriggerStateRepository triggerRepository =
                org.mockito.Mockito.mock(OrderTriggerStateRepository.class);
        OrderAlgoStateRepository algoRepository =
                org.mockito.Mockito.mock(OrderAlgoStateRepository.class);
        OrderPlacementStateService service = new OrderPlacementStateService(
                coordinationRepository, positionModeRepository, positionRepository,
                orderRepository, triggerRepository, algoRepository);
        when(positionRepository.hasMarginModeConflict(
                ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", MarginMode.ISOLATED)).thenReturn(false);
        when(orderRepository.hasActiveMarginModeConflict(
                ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", MarginMode.ISOLATED)).thenReturn(false);
        when(triggerRepository.hasMarginModeConflict(
                ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", MarginMode.ISOLATED)).thenReturn(false);
        when(algoRepository.hasMarginModeConflict(
                ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", MarginMode.ISOLATED)).thenReturn(true);

        boolean conflict = service.hasActiveMarginModeConflict(
                ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", MarginMode.ISOLATED);

        assertThat(conflict).isTrue();
        verify(positionRepository).hasMarginModeConflict(
                ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", MarginMode.ISOLATED);
        verify(orderRepository).hasActiveMarginModeConflict(
                ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", MarginMode.ISOLATED);
        verify(triggerRepository).hasMarginModeConflict(
                ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", MarginMode.ISOLATED);
        verify(algoRepository).hasMarginModeConflict(
                ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", MarginMode.ISOLATED);
    }

    @Test
    void transactionLockIsDelegatedToCoordinationRepository() {
        OrderCoordinationRepository coordinationRepository =
                org.mockito.Mockito.mock(OrderCoordinationRepository.class);
        OrderPlacementStateService service = new OrderPlacementStateService(
                coordinationRepository,
                org.mockito.Mockito.mock(OrderPositionModeRepository.class),
                org.mockito.Mockito.mock(OrderPositionRepository.class),
                org.mockito.Mockito.mock(OrderRepository.class),
                org.mockito.Mockito.mock(OrderTriggerStateRepository.class),
                org.mockito.Mockito.mock(OrderAlgoStateRepository.class));

        service.lockUserSymbolMarginScope(ProductLine.OPTION, 1001L, "BTC-USDT-260925-70000-C");

        verify(coordinationRepository).lockUserSymbolMarginScope(
                ProductLine.OPTION, 1001L, "BTC-USDT-260925-70000-C");
    }
}
