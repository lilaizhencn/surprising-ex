package com.surprising.liquidation.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.surprising.liquidation.provider.config.LiquidationProperties;
import com.surprising.product.api.ProductLine;
import com.surprising.risk.api.model.LiquidationCandidateEvent;
import com.surprising.trading.api.model.MatchResultEvent;
import com.surprising.trading.api.model.OrderCommandType;
import com.surprising.trading.api.model.OrderStatus;
import java.time.Instant;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class LiquidationCandidateConsumerTest {

    private static final LiquidationCandidateEvent CANDIDATE = new LiquidationCandidateEvent(
            9401L,
            9301L,
            2002L,
            "BTC-USDT",
            8L,
            "USDT",
            10L,
            590_000L,
            -200_000_000L,
            88_500_000L,
            1_100_000L,
            Instant.parse("2026-07-01T00:00:00Z"));

    @Test
    void processesCandidateWhenKafkaKeyMatchesPayloadSymbol() {
        ObjectMapper objectMapper = new ObjectMapper();
        RecordingLiquidationService liquidationService = new RecordingLiquidationService();
        LiquidationCandidateConsumer consumer = new LiquidationCandidateConsumer(objectMapper, liquidationService);

        consumer.onCandidate(new ConsumerRecord<>("surprising.perp.liquidation.candidates.v1", 1, 10L,
                "BTC-USDT", objectMapper.writeValueAsString(CANDIDATE)));

        assertThat(liquidationService.processed).isEqualTo(CANDIDATE);
    }

    @Test
    void rejectsCandidateWhenKafkaKeyDoesNotMatchPayloadSymbol() {
        ObjectMapper objectMapper = new ObjectMapper();
        RecordingLiquidationService liquidationService = new RecordingLiquidationService();
        LiquidationCandidateConsumer consumer = new LiquidationCandidateConsumer(objectMapper, liquidationService);

        assertThatThrownBy(() -> consumer.onCandidate(new ConsumerRecord<>(
                "surprising.perp.liquidation.candidates.v1",
                1,
                10L,
                "ETH-USDT",
                objectMapper.writeValueAsString(CANDIDATE))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed to process liquidation candidate")
                .satisfies(ex -> assertThat(ex.getCause())
                        .hasMessageContaining("liquidation candidate Kafka key must match payload symbol"));

        assertThat(liquidationService.processed).isNull();
    }

    @Test
    void rejectsCandidateFromOtherProductTopicBeforeProcessing() {
        ObjectMapper objectMapper = new ObjectMapper();
        RecordingLiquidationService liquidationService = new RecordingLiquidationService();
        LiquidationProperties properties = new LiquidationProperties();
        properties.getKafka().setProductLine(ProductLine.OPTION);
        properties.getKafka().setProductTopicsEnabled(true);
        LiquidationCandidateConsumer consumer = new LiquidationCandidateConsumer(objectMapper, liquidationService,
                properties);

        assertThatThrownBy(() -> consumer.onCandidate(new ConsumerRecord<>(
                "surprising.linear-delivery.liquidation.candidates.v1", 1, 10L, "BTC-USDT",
                objectMapper.writeValueAsString(CANDIDATE))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed to process liquidation candidate")
                .satisfies(ex -> assertThat(ex.getCause())
                        .hasMessageContaining("liquidation candidate topic must match current product line")
                        .hasMessageContaining("surprising.option.liquidation.candidates.v1"));

        assertThat(liquidationService.processed).isNull();
    }

    @Test
    void rejectsMatchResultFromOtherProductTopicBeforeProcessing() {
        ObjectMapper objectMapper = new ObjectMapper();
        RecordingLiquidationService liquidationService = new RecordingLiquidationService();
        LiquidationProperties properties = new LiquidationProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        properties.getKafka().setProductTopicsEnabled(true);
        LiquidationMatchResultConsumer consumer = new LiquidationMatchResultConsumer(objectMapper, liquidationService,
                properties);
        MatchResultEvent event = new MatchResultEvent(9201L, 9001L, 1001L, "BTC-USDT-260925", 7L,
                OrderCommandType.PLACE, "OK", 10L, OrderStatus.FILLED,
                Instant.parse("2026-07-01T00:00:00Z"), List.of(), "trace-1");

        assertThatThrownBy(() -> consumer.onMatchResult(new ConsumerRecord<>(
                "surprising.option.match.results.v1", 1, 10L, "BTC-USDT-260925",
                objectMapper.writeValueAsString(event))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed to process liquidation match result")
                .satisfies(ex -> assertThat(ex.getCause())
                        .hasMessageContaining("liquidation match result topic must match current product line")
                        .hasMessageContaining("surprising.linear-delivery.match.results.v1"));

        assertThat(liquidationService.matchResult).isNull();
    }

    @Test
    void resolvesCandidateTopicAndGroupFromProductLine() {
        LiquidationProperties properties = new LiquidationProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        properties.getKafka().setProductTopicsEnabled(true);
        LiquidationCandidateConsumer consumer = new LiquidationCandidateConsumer(new ObjectMapper(),
                mock(LiquidationService.class), properties);

        assertThat(consumer.liquidationCandidatesTopic())
                .isEqualTo("surprising.linear-delivery.liquidation.candidates.v1");
        assertThat(consumer.groupId()).isEqualTo("surprising-linear-delivery-liquidation-v1");
    }

    @Test
    void resolvesMatchResultTopicAndGroupFromProductLine() {
        LiquidationProperties properties = new LiquidationProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        properties.getKafka().setProductTopicsEnabled(true);
        LiquidationMatchResultConsumer consumer = new LiquidationMatchResultConsumer(new ObjectMapper(),
                mock(LiquidationService.class), properties);

        assertThat(consumer.matchResultsTopic()).isEqualTo("surprising.linear-delivery.match.results.v1");
        assertThat(consumer.groupId()).isEqualTo("surprising-linear-delivery-liquidation-v1");
    }

    private static final class RecordingLiquidationService extends LiquidationService {
        private LiquidationCandidateEvent processed;
        private MatchResultEvent matchResult;

        private RecordingLiquidationService() {
            super(null, null, null, null, null, null, null);
        }

        @Override
        public void processCandidate(LiquidationCandidateEvent event) {
            processed = event;
        }

        @Override
        public void processMatchResult(MatchResultEvent event) {
            matchResult = event;
        }
    }
}
