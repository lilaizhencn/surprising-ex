package com.surprising.insurance.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.account.api.model.LiquidationFeeSettledEvent;
import com.surprising.insurance.provider.config.InsuranceProperties;
import com.surprising.insurance.provider.repository.InsuranceRepository;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import java.time.Instant;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class LiquidationFeeEventConsumerTest {

    private final ObjectMapper objectMapper = new JsonMapper();

    @Test
    void processesLiquidationFeeFromCurrentProductTopic() throws Exception {
        InsuranceProperties properties = new InsuranceProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        properties.getKafka().setProductTopicsEnabled(true);
        RecordingInsuranceService service = new RecordingInsuranceService(properties);
        LiquidationFeeEventConsumer consumer = new LiquidationFeeEventConsumer(objectMapper, service, properties);
        LiquidationFeeSettledEvent event = event("USDT_DELIVERY");

        consumer.onLiquidationFee(new ConsumerRecord<>("surprising.linear-delivery.account.liquidation-fee.events.v1",
                0, 1L, "USDT", objectMapper.writeValueAsString(event)));

        assertThat(service.event).isEqualTo(event);
    }

    @Test
    void rejectsMismatchedLiquidationFeeTopicBeforeCollecting() throws Exception {
        InsuranceProperties properties = new InsuranceProperties();
        properties.getKafka().setProductLine(ProductLine.OPTION);
        properties.getKafka().setProductTopicsEnabled(true);
        RecordingInsuranceService service = new RecordingInsuranceService(properties);
        LiquidationFeeEventConsumer consumer = new LiquidationFeeEventConsumer(objectMapper, service, properties);

        assertThatThrownBy(() -> consumer.onLiquidationFee(new ConsumerRecord<>(
                "surprising.linear-delivery.account.liquidation-fee.events.v1", 0, 1L, "USDT",
                objectMapper.writeValueAsString(event("OPTION")))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed to process liquidation fee event")
                .hasRootCauseMessage("liquidation fee topic must match current product line: expected="
                        + "surprising.option.account.liquidation-fee.events.v1 actual="
                        + "surprising.linear-delivery.account.liquidation-fee.events.v1");

        assertThat(service.event).isNull();
    }

    @Test
    void rejectsMismatchedAssetKeyBeforeCollecting() throws Exception {
        InsuranceProperties properties = new InsuranceProperties();
        RecordingInsuranceService service = new RecordingInsuranceService(properties);
        LiquidationFeeEventConsumer consumer = new LiquidationFeeEventConsumer(objectMapper, service, properties);

        assertThatThrownBy(() -> consumer.onLiquidationFee(new ConsumerRecord<>(
                "surprising.account.liquidation-fee.events.v1", 0, 1L, "USDC",
                objectMapper.writeValueAsString(event("USDT_PERPETUAL")))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed to process liquidation fee event")
                .hasRootCauseMessage("liquidation fee event key must match asset");

        assertThat(service.event).isNull();
    }

    @Test
    void resolvesProductTopicAndGroupId() {
        InsuranceProperties properties = new InsuranceProperties();
        properties.getKafka().setProductLine(ProductLine.INVERSE_DELIVERY);
        properties.getKafka().setProductTopicsEnabled(true);
        LiquidationFeeEventConsumer consumer = new LiquidationFeeEventConsumer(objectMapper,
                new RecordingInsuranceService(properties), properties);

        assertThat(consumer.liquidationFeeEventsTopic())
                .isEqualTo("surprising.inverse-delivery.account.liquidation-fee.events.v1");
        assertThat(consumer.groupId()).isEqualTo("surprising-inverse-delivery-insurance-v1");
    }

    private static LiquidationFeeSettledEvent event(String accountType) {
        return new LiquidationFeeSettledEvent(8801L, 9201L, 7001L, 6001L, 9401L, 2002L,
                "BTC-USDT-260925", MarginMode.CROSS, accountType, "USDT", 70L, 500L,
                Instant.parse("2026-07-01T00:00:00Z"), "trace-1");
    }

    private static final class RecordingInsuranceService extends InsuranceService {
        private LiquidationFeeSettledEvent event;

        private RecordingInsuranceService(InsuranceProperties properties) {
            super(properties, new InsuranceRepository(null));
        }

        @Override
        public void collectLiquidationFee(LiquidationFeeSettledEvent event) {
            this.event = event;
        }
    }
}
