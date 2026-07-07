package com.surprising.instrument.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.instrument.api.model.ContractSettlementMethod;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.DeliverySettlementEvent;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.instrument.api.model.OptionExerciseEvent;
import com.surprising.instrument.api.model.OptionExerciseStyle;
import com.surprising.instrument.api.model.OptionType;
import com.surprising.instrument.provider.config.InstrumentProperties;
import com.surprising.instrument.provider.repository.InstrumentRepository;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

class InstrumentServiceTest {

    @Test
    void publishesDeliverySettlementToProductTopic() {
        KafkaTemplate<String, Object> kafkaTemplate = kafkaTemplate();
        InstrumentService service = service(kafkaTemplate, new InstrumentProperties());
        InstrumentResponse instrument = delivery("BTC-USDT-260327", InstrumentStatus.CLOSED);

        service.publishProductLifecycleEvent(instrument);

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq("surprising.linear-delivery.delivery.settlements.v1"),
                eq("BTC-USDT-260327"), event.capture());
        assertThat(event.getValue()).isInstanceOf(DeliverySettlementEvent.class);
        DeliverySettlementEvent deliveryEvent = (DeliverySettlementEvent) event.getValue();
        assertThat(deliveryEvent.symbol()).isEqualTo("BTC-USDT-260327");
        assertThat(deliveryEvent.status()).isEqualTo(InstrumentStatus.CLOSED);
    }

    @Test
    void publishesOptionExerciseToProductTopic() {
        KafkaTemplate<String, Object> kafkaTemplate = kafkaTemplate();
        InstrumentService service = service(kafkaTemplate, new InstrumentProperties());
        InstrumentResponse instrument = option("BTC-USDT-260327-50000-C", InstrumentStatus.CLOSED);

        service.publishProductLifecycleEvent(instrument);

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq("surprising.option.option.exercises.v1"),
                eq("BTC-USDT-260327-50000-C"), event.capture());
        assertThat(event.getValue()).isInstanceOf(OptionExerciseEvent.class);
        OptionExerciseEvent optionEvent = (OptionExerciseEvent) event.getValue();
        assertThat(optionEvent.underlyingSymbol()).isEqualTo("BTC-USDT");
        assertThat(optionEvent.optionType()).isEqualTo(OptionType.CALL);
    }

    @Test
    void usesConfiguredLifecycleTopicOverrides() {
        KafkaTemplate<String, Object> kafkaTemplate = kafkaTemplate();
        InstrumentProperties properties = new InstrumentProperties();
        properties.getKafka().setDeliverySettlementsTopic("custom.delivery.settlements");
        InstrumentService service = service(kafkaTemplate, properties);

        service.publishProductLifecycleEvent(delivery("BTC-USDT-260327", InstrumentStatus.CLOSED));

        verify(kafkaTemplate).send(eq("custom.delivery.settlements"), eq("BTC-USDT-260327"),
                org.mockito.ArgumentMatchers.any(Object.class));
    }

    @Test
    void latestAcceptsMatchingProductLine() {
        InstrumentRepository repository = mock(InstrumentRepository.class);
        InstrumentService service = service(repository);
        InstrumentResponse instrument = option("BTC-USDT-260327-50000-C", InstrumentStatus.TRADING);
        when(repository.latest("BTC-USDT-260327-50000-C")).thenReturn(Optional.of(instrument));

        InstrumentResponse response = service.latest("BTC-USDT-260327-50000-C", ProductLine.OPTION);

        assertThat(response).isSameAs(instrument);
    }

    @Test
    void latestRejectsMismatchedProductLine() {
        InstrumentRepository repository = mock(InstrumentRepository.class);
        InstrumentService service = service(repository);
        when(repository.latest("BTC-USDT-260327"))
                .thenReturn(Optional.of(delivery("BTC-USDT-260327", InstrumentStatus.TRADING)));

        assertThatThrownBy(() -> service.latest("BTC-USDT-260327", ProductLine.LINEAR_PERPETUAL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("instrument not found for productLine");
    }

    private InstrumentService service(KafkaTemplate<String, Object> kafkaTemplate, InstrumentProperties properties) {
        return new InstrumentService(mock(InstrumentRepository.class), mock(InstrumentValidator.class),
                properties, kafkaTemplate);
    }

    private InstrumentService service(InstrumentRepository repository) {
        return new InstrumentService(repository, mock(InstrumentValidator.class),
                new InstrumentProperties(), kafkaTemplate());
    }

    @SuppressWarnings("unchecked")
    private KafkaTemplate<String, Object> kafkaTemplate() {
        return mock(KafkaTemplate.class);
    }

    private InstrumentResponse delivery(String symbol, InstrumentStatus status) {
        return response(symbol, InstrumentType.DELIVERY, ContractType.LINEAR_DELIVERY,
                null, null, null, status);
    }

    private InstrumentResponse option(String symbol, InstrumentStatus status) {
        return response(symbol, InstrumentType.OPTION, ContractType.VANILLA_OPTION,
                "BTC-USDT", 50_000_000_000L, OptionType.CALL, status);
    }

    private InstrumentResponse response(String symbol,
                                        InstrumentType instrumentType,
                                        ContractType contractType,
                                        String underlyingSymbol,
                                        Long strikePriceUnits,
                                        OptionType optionType,
                                        InstrumentStatus status) {
        Instant now = Instant.parse("2026-03-27T08:05:00Z");
        return new InstrumentResponse(
                symbol,
                2L,
                instrumentType,
                contractType,
                "BTC",
                "USDT",
                "USDT",
                1_000_000L,
                "USDT",
                10_000_000L,
                100_000L,
                1L,
                100_000L,
                500_000_000L,
                1_000_000_000_000_000L,
                10_000L,
                1,
                3,
                List.of("LIMIT"),
                List.of("GTC", "IOC"),
                true,
                true,
                false,
                100_000_000L,
                10_000L,
                5_000L,
                200L,
                500L,
                500_000_000_000_000L,
                300_000L,
                25_000_000_000_000L,
                0,
                0L,
                0L,
                0L,
                1_000_000_000_000L,
                2,
                Instant.parse("2026-03-27T08:00:00Z"),
                Instant.parse("2026-03-27T08:05:00Z"),
                underlyingSymbol,
                strikePriceUnits,
                optionType,
                optionType == null ? null : OptionExerciseStyle.EUROPEAN,
                ContractSettlementMethod.CASH,
                status,
                now.minusSeconds(600),
                now.minusSeconds(600),
                now,
                List.of(),
                List.of());
    }
}
