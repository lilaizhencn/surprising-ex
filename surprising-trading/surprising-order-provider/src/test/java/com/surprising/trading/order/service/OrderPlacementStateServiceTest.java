package com.surprising.trading.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.order.repository.OrderAlgoStateRepository;
import com.surprising.trading.order.repository.OrderCoordinationRepository;
import com.surprising.trading.order.repository.OrderPositionModeRepository;
import com.surprising.trading.order.repository.OrderPositionRepository;
import com.surprising.trading.order.repository.OrderRepository;
import com.surprising.trading.order.repository.OrderTriggerStateRepository;
import org.junit.jupiter.api.Test;

class OrderPlacementStateServiceTest {

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
