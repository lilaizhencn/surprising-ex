package com.surprising.trading.trigger.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.InstrumentEvent;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.trigger.config.TriggerProperties;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

class InstrumentTriggerDrainServiceTest {

    @Test
    void retriesWhileTriggerStateMachineIsActive() {
        TriggerOrderService triggerOrderService = mock(TriggerOrderService.class);
        when(triggerOrderService.hasLifecycleActiveOrders("BTC-USDT-260327")).thenReturn(true);

        assertThatThrownBy(() -> service(triggerOrderService, kafkaTemplate()).drain(event()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("尚未完成");
    }

    @Test
    void publishesReadyAfterTriggerOrdersAreCanceled() {
        TriggerOrderService triggerOrderService = mock(TriggerOrderService.class);
        TriggerInstrumentLifecycleFenceService lifecycleFenceService =
                mock(TriggerInstrumentLifecycleFenceService.class);
        KafkaTemplate<String, String> kafkaTemplate = kafkaTemplate();
        when(kafkaTemplate.send(
                org.mockito.ArgumentMatchers.eq("surprising.instrument.lifecycle-drain.v1"),
                org.mockito.ArgumentMatchers.eq("BTC-USDT-260327"),
                any(String.class))).thenReturn(CompletableFuture.completedFuture(null));

        TriggerProperties properties = new TriggerProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        new InstrumentTriggerDrainService(
                new ObjectMapper(), properties, triggerOrderService,
                kafkaTemplate, lifecycleFenceService).drain(event());

        verify(lifecycleFenceService).blockForSettlement(
                ProductLine.LINEAR_DELIVERY, "BTC-USDT-260327", 2L);
        verify(triggerOrderService).cancelLifecycleOrders("BTC-USDT-260327", 1000);
        verify(kafkaTemplate).send(
                org.mockito.ArgumentMatchers.eq("surprising.instrument.lifecycle-drain.v1"),
                org.mockito.ArgumentMatchers.eq("BTC-USDT-260327"),
                any(String.class));
    }

    private InstrumentTriggerDrainService service(
            TriggerOrderService triggerOrderService, KafkaTemplate<String, String> kafkaTemplate) {
        TriggerProperties properties = new TriggerProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        return new InstrumentTriggerDrainService(
                new ObjectMapper(), properties, triggerOrderService, kafkaTemplate);
    }

    private InstrumentEvent event() {
        InstrumentEvent event = mock(InstrumentEvent.class);
        InstrumentResponse snapshot = mock(InstrumentResponse.class);
        when(snapshot.contractType()).thenReturn(ContractType.LINEAR_DELIVERY);
        when(event.status()).thenReturn(InstrumentStatus.SETTLING);
        when(event.symbol()).thenReturn("BTC-USDT-260327");
        when(event.version()).thenReturn(2L);
        when(event.snapshot()).thenReturn(snapshot);
        return event;
    }

    @SuppressWarnings("unchecked")
    private KafkaTemplate<String, String> kafkaTemplate() {
        return mock(KafkaTemplate.class);
    }
}
