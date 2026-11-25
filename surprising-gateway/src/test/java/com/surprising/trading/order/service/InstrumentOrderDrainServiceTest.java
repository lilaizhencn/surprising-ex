package com.surprising.trading.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.surprising.instrument.api.model.InstrumentEvent;
import com.surprising.instrument.api.model.InstrumentLifecycleDrainComponent;
import com.surprising.instrument.api.model.InstrumentLifecycleDrainEvent;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.order.config.TradingOrderProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

class InstrumentOrderDrainServiceTest {
    private final com.surprising.trading.trigger.service.TriggerOrderService triggers =
            mock(com.surprising.trading.trigger.service.TriggerOrderService.class);

    @Test
    void waitsUntilOrdinaryOrdersReachTerminalState() {
        OrderAdminService orderAdminService = mock(OrderAdminService.class);
        AlgoOrderService algoOrderService = mock(AlgoOrderService.class);
        when(orderAdminService.hasLifecycleActiveOrders("1")).thenReturn(true);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        InstrumentOrderDrainService service = service(orderAdminService, algoOrderService, publisher);

        assertThatThrownBy(() -> service.drain(event()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("尚未完成");

        verify(algoOrderService).cancelLifecycleOrders("1", 1000);
        verify(orderAdminService).requestLifecycleCancellation("1", 1000);
        verifyNoInteractions(publisher);
    }

    @Test
    void publishesReadyAfterOrdersAndAlgosAreDrained() {
        OrderAdminService orderAdminService = mock(OrderAdminService.class);
        AlgoOrderService algoOrderService = mock(AlgoOrderService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);

        service(orderAdminService, algoOrderService, publisher).drain(event());

        ArgumentCaptor<InstrumentLifecycleDrainEvent> captor = ArgumentCaptor.forClass(InstrumentLifecycleDrainEvent.class);
        verify(publisher).publishEvent(captor.capture());
        assertThat(captor.getValue().instrumentId()).isEqualTo("1");
        assertThat(captor.getValue().productLine()).isEqualTo(ProductLine.LINEAR_DELIVERY);
        assertThat(captor.getValue().component()).isEqualTo(InstrumentLifecycleDrainComponent.ORDER);
    }

    @Test
    void pendingStopsPreventReadyAcknowledgement() {
        var orders = mock(OrderAdminService.class);
        var algos = mock(AlgoOrderService.class);
        var publisher = mock(ApplicationEventPublisher.class);
        when(triggers.hasLifecycleActiveOrders("1")).thenReturn(true);
        assertThatThrownBy(() -> service(orders, algos, publisher).drain(event())).hasMessageContaining("尚未完成");
        verify(triggers).cancelLifecycleOrders("1", 1000);
        verifyNoInteractions(publisher);
    }

    private InstrumentOrderDrainService service(OrderAdminService orderAdminService,
                                                AlgoOrderService algoOrderService,
                                                ApplicationEventPublisher publisher) {
        TradingOrderProperties properties = new TradingOrderProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        return new InstrumentOrderDrainService(properties, orderAdminService, algoOrderService, triggers, publisher);
    }

    private InstrumentEvent event() {
        InstrumentEvent event = mock(InstrumentEvent.class);
        when(event.status()).thenReturn(InstrumentStatus.SETTLING);
        when(event.productLine()).thenReturn(ProductLine.LINEAR_DELIVERY);
        when(event.instrumentId()).thenReturn(1);
        when(event.changeId()).thenReturn(2L);
        return event;
    }
}
