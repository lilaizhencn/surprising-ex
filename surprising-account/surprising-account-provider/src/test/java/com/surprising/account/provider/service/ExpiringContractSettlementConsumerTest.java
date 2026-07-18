package com.surprising.account.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.account.provider.config.AccountProperties;
import com.surprising.instrument.api.model.ContractSettlementMethod;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.DeliverySettlementEvent;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.instrument.api.model.OptionExerciseEvent;
import com.surprising.instrument.api.model.OptionExerciseStyle;
import com.surprising.instrument.api.model.OptionType;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ExpiringContractSettlementConsumerTest {

    private static final Instant EVENT_TIME = Instant.parse("2026-07-01T08:00:00Z");

    @Test
    void processesDeliverySettlementWhenKafkaKeyMatchesPayloadSymbol() {
        ObjectMapper objectMapper = new ObjectMapper();
        RecordingFanoutService fanoutService = new RecordingFanoutService();
        AccountProperties properties = new AccountProperties();
        ExpiringContractSettlementConsumer consumer =
                new ExpiringContractSettlementConsumer(objectMapper, fanoutService, properties);
        DeliverySettlementEvent event = deliveryEvent("BTC-USDT-260327");

        consumer.onDeliverySettlement(new ConsumerRecord<>("surprising.linear-delivery.delivery.settlements.v1",
                0, 1L, "BTC-USDT-260327", objectMapper.writeValueAsString(event)));

        assertThat(fanoutService.deliveryEvent).isEqualTo(event);
    }

    @Test
    void processesOptionExerciseWhenKafkaKeyMatchesPayloadSymbol() {
        ObjectMapper objectMapper = new ObjectMapper();
        RecordingFanoutService fanoutService = new RecordingFanoutService();
        AccountProperties properties = new AccountProperties();
        ExpiringContractSettlementConsumer consumer =
                new ExpiringContractSettlementConsumer(objectMapper, fanoutService, properties);
        OptionExerciseEvent event = optionEvent("BTC-USDT-260925-70000-C");

        consumer.onOptionExercise(new ConsumerRecord<>("surprising.option.option.exercises.v1",
                0, 1L, "BTC-USDT-260925-70000-C", objectMapper.writeValueAsString(event)));

        assertThat(fanoutService.optionEvent).isEqualTo(event);
    }

    @Test
    void rejectsDeliverySettlementWhenKafkaKeyDoesNotMatchPayloadSymbol() {
        ObjectMapper objectMapper = new ObjectMapper();
        RecordingFanoutService fanoutService = new RecordingFanoutService();
        AccountProperties properties = new AccountProperties();
        ExpiringContractSettlementConsumer consumer =
                new ExpiringContractSettlementConsumer(objectMapper, fanoutService, properties);

        assertThatThrownBy(() -> consumer.onDeliverySettlement(new ConsumerRecord<>(
                "surprising.linear-delivery.delivery.settlements.v1", 0, 1L, "ETH-USDT-260327",
                objectMapper.writeValueAsString(deliveryEvent("BTC-USDT-260327")))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed to process delivery settlement")
                .satisfies(ex -> assertThat(ex.getCause())
                        .hasMessageContaining("delivery settlement Kafka key must match payload symbol"));

        assertThat(fanoutService.deliveryEvent).isNull();
    }

    @Test
    void rejectsDeliverySettlementFromOtherProductTopicBeforeSettlement() {
        ObjectMapper objectMapper = new ObjectMapper();
        RecordingFanoutService fanoutService = new RecordingFanoutService();
        AccountProperties properties = new AccountProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        properties.getKafka().setProductTopicsEnabled(true);
        ExpiringContractSettlementConsumer consumer =
                new ExpiringContractSettlementConsumer(objectMapper, fanoutService, properties);

        assertThatThrownBy(() -> consumer.onDeliverySettlement(new ConsumerRecord<>(
                "surprising.inverse-delivery.delivery.settlements.v1", 0, 1L, "BTC-USDT-260327",
                objectMapper.writeValueAsString(deliveryEvent("BTC-USDT-260327")))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed to process delivery settlement")
                .satisfies(ex -> assertThat(ex.getCause())
                        .hasMessageContaining("delivery settlement topic must match current product line")
                        .hasMessageContaining("surprising.linear-delivery.delivery.settlements.v1"));

        assertThat(fanoutService.deliveryEvent).isNull();
    }

    @Test
    void rejectsOptionExerciseFromOtherProductTopicBeforeSettlement() {
        ObjectMapper objectMapper = new ObjectMapper();
        RecordingFanoutService fanoutService = new RecordingFanoutService();
        AccountProperties properties = new AccountProperties();
        properties.getKafka().setProductLine(ProductLine.OPTION);
        properties.getKafka().setProductTopicsEnabled(true);
        ExpiringContractSettlementConsumer consumer =
                new ExpiringContractSettlementConsumer(objectMapper, fanoutService, properties);

        assertThatThrownBy(() -> consumer.onOptionExercise(new ConsumerRecord<>(
                "surprising.linear-delivery.option.exercises.v1", 0, 1L, "BTC-USDT-260925-70000-C",
                objectMapper.writeValueAsString(optionEvent("BTC-USDT-260925-70000-C")))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed to process option exercise")
                .satisfies(ex -> assertThat(ex.getCause())
                        .hasMessageContaining("option exercise topic must match current product line")
                        .hasMessageContaining("surprising.option.option.exercises.v1"));

        assertThat(fanoutService.optionEvent).isNull();
    }

    @Test
    void exposesResolvedLifecycleTopicsAndGroupFromProperties() {
        AccountProperties properties = new AccountProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        properties.getKafka().setProductTopicsEnabled(true);
        ExpiringContractSettlementConsumer deliveryConsumer = new ExpiringContractSettlementConsumer(
                new ObjectMapper(), new RecordingFanoutService(), properties);

        assertThat(deliveryConsumer.deliverySettlementsTopic())
                .isEqualTo("surprising.linear-delivery.delivery.settlements.v1");
        assertThat(deliveryConsumer.deliverySettlementsListenerEnabled()).isTrue();
        assertThat(deliveryConsumer.optionExercisesListenerEnabled()).isFalse();
        assertThat(deliveryConsumer.groupId()).isEqualTo("surprising-linear-delivery-account-v1");

        properties.getKafka().setProductLine(ProductLine.OPTION);
        assertThat(deliveryConsumer.deliverySettlementsListenerEnabled()).isFalse();
        assertThat(deliveryConsumer.optionExercisesListenerEnabled()).isTrue();
        assertThat(deliveryConsumer.optionExercisesTopic())
                .isEqualTo("surprising.option.option.exercises.v1");
        assertThat(deliveryConsumer.groupId()).isEqualTo("surprising-option-account-v1");
    }

    private static DeliverySettlementEvent deliveryEvent(String symbol) {
        return new DeliverySettlementEvent(symbol, 4L, ContractType.LINEAR_DELIVERY,
                EVENT_TIME, EVENT_TIME, ContractSettlementMethod.CASH, InstrumentStatus.CLOSED, EVENT_TIME, null);
    }

    private static OptionExerciseEvent optionEvent(String symbol) {
        return new OptionExerciseEvent(symbol, 6L, "BTC-USDT", 70_000_000L,
                OptionType.CALL, OptionExerciseStyle.EUROPEAN, EVENT_TIME, EVENT_TIME,
                ContractSettlementMethod.CASH, InstrumentStatus.CLOSED, EVENT_TIME, null);
    }

    private static final class RecordingFanoutService extends ExpiringContractSettlementFanoutService {
        private DeliverySettlementEvent deliveryEvent;
        private OptionExerciseEvent optionEvent;

        private RecordingFanoutService() {
            super(null, null, null, null);
        }

        @Override
        public int fanout(DeliverySettlementEvent event) {
            this.deliveryEvent = event;
            return 1;
        }

        @Override
        public int fanout(OptionExerciseEvent event) {
            this.optionEvent = event;
            return 1;
        }
    }
}
