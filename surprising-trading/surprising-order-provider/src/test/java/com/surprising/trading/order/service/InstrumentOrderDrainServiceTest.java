package com.surprising.trading.order.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.instrument.api.model.InstrumentEvent;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.order.config.TradingOrderProperties;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

class InstrumentOrderDrainServiceTest {

    @Test
    void waitsUntilOrdinaryOrdersReachTerminalState() {
        OrderService orderService = mock(OrderService.class);
        AlgoOrderService algoOrderService = mock(AlgoOrderService.class);
        when(orderService.hasLifecycleActiveOrders("BTC-USDT-260327")).thenReturn(true);
        InstrumentOrderDrainService service = service(orderService, algoOrderService, kafkaTemplate());

        assertThatThrownBy(() -> service.drain(event()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("尚未完成");

        verify(algoOrderService).cancelLifecycleOrders("BTC-USDT-260327", 1000);
        verify(orderService).requestLifecycleCancellation("BTC-USDT-260327", 1000);
    }

    @Test
    void publishesReadyAfterOrdersAndAlgosAreDrained() throws Exception {
        OrderService orderService = mock(OrderService.class);
        AlgoOrderService algoOrderService = mock(AlgoOrderService.class);
        OrderInstrumentLifecycleFenceService lifecycleFenceService =
                mock(OrderInstrumentLifecycleFenceService.class);
        KafkaTemplate<String, String> kafkaTemplate = kafkaTemplate();
        when(kafkaTemplate.send(
                org.mockito.ArgumentMatchers.eq("surprising.instrument.lifecycle-drain.v1"),
                org.mockito.ArgumentMatchers.eq("BTC-USDT-260327"),
                any(String.class))).thenReturn(CompletableFuture.completedFuture(null));

        TradingOrderProperties properties = new TradingOrderProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        new InstrumentOrderDrainService(
                new ObjectMapper(), properties, orderService, algoOrderService,
                kafkaTemplate, lifecycleFenceService).drain(event());

        verify(lifecycleFenceService).blockForSettlement(
                ProductLine.LINEAR_DELIVERY, "BTC-USDT-260327", 2L);
        verify(kafkaTemplate).send(
                org.mockito.ArgumentMatchers.eq("surprising.instrument.lifecycle-drain.v1"),
                org.mockito.ArgumentMatchers.eq("BTC-USDT-260327"),
                any(String.class));
    }

    private InstrumentOrderDrainService service(OrderService orderService,
                                                AlgoOrderService algoOrderService,
                                                KafkaTemplate<String, String> kafkaTemplate) {
        TradingOrderProperties properties = new TradingOrderProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        return new InstrumentOrderDrainService(
                new ObjectMapper(), properties, orderService, algoOrderService, kafkaTemplate);
    }

    private InstrumentEvent event() {
        InstrumentEvent event = mock(InstrumentEvent.class);
        when(event.status()).thenReturn(InstrumentStatus.SETTLING);
        when(event.symbol()).thenReturn("BTC-USDT-260327");
        when(event.version()).thenReturn(2L);
        when(event.productLine()).thenReturn(ProductLine.LINEAR_DELIVERY);
        return event;
    }

    @SuppressWarnings("unchecked")
    private KafkaTemplate<String, String> kafkaTemplate() {
        return mock(KafkaTemplate.class);
    }
}
