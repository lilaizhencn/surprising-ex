package com.surprising.account.provider.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.account.provider.config.AccountProperties;
import com.surprising.account.provider.repository.AccountCommandRepository;
import com.surprising.account.provider.repository.TradeSettlementSideRepository;
import com.surprising.instrument.api.model.InstrumentLifecycleDrainComponent;
import com.surprising.instrument.api.model.InstrumentLifecycleDrainEvent;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

class AccountInstrumentDrainServiceTest {

    @Test
    void retriesWhileReservationCommandIsPending() {
        AccountCommandRepository commandRepository = mock(AccountCommandRepository.class);
        when(commandRepository.hasPendingOrderReservations(
                ProductLine.LINEAR_DELIVERY, "BTC-USDT-260327")).thenReturn(true);

        assertThatThrownBy(() -> service(commandRepository, mock(TradeSettlementSideRepository.class),
                kafkaTemplate()).confirmReleased(orderReady()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("尚未全部释放");
    }

    @Test
    void publishesAccountReadyAfterReservationsAreReleased() {
        AccountCommandRepository commandRepository = mock(AccountCommandRepository.class);
        when(commandRepository.orderReservationSnapshots(
                ProductLine.LINEAR_DELIVERY, "BTC-USDT-260327", 0L, 500)).thenReturn(List.of());
        KafkaTemplate<String, String> kafkaTemplate = kafkaTemplate();
        when(kafkaTemplate.send(
                org.mockito.ArgumentMatchers.eq("surprising.instrument.lifecycle-drain.v1"),
                org.mockito.ArgumentMatchers.eq("BTC-USDT-260327"),
                any(String.class))).thenReturn(CompletableFuture.completedFuture(null));

        service(commandRepository, mock(TradeSettlementSideRepository.class), kafkaTemplate)
                .confirmReleased(orderReady());

        verify(kafkaTemplate).send(
                org.mockito.ArgumentMatchers.eq("surprising.instrument.lifecycle-drain.v1"),
                org.mockito.ArgumentMatchers.eq("BTC-USDT-260327"),
                any(String.class));
    }

    private AccountInstrumentDrainService service(
            AccountCommandRepository commandRepository,
            TradeSettlementSideRepository tradeSettlementSideRepository,
            KafkaTemplate<String, String> kafkaTemplate) {
        AccountProperties properties = new AccountProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        return new AccountInstrumentDrainService(
                new ObjectMapper(), properties, commandRepository, tradeSettlementSideRepository, kafkaTemplate);
    }

    private InstrumentLifecycleDrainEvent orderReady() {
        return new InstrumentLifecycleDrainEvent(
                InstrumentLifecycleDrainEvent.CURRENT_SCHEMA_VERSION,
                "BTC-USDT-260327",
                2L,
                ProductLine.LINEAR_DELIVERY,
                InstrumentLifecycleDrainComponent.ORDER,
                Instant.now());
    }

    @SuppressWarnings("unchecked")
    private KafkaTemplate<String, String> kafkaTemplate() {
        return mock(KafkaTemplate.class);
    }
}
