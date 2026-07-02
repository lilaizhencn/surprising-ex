package com.surprising.liquidation.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.risk.api.model.LiquidationCandidateEvent;
import java.time.Instant;
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

    private static final class RecordingLiquidationService extends LiquidationService {
        private LiquidationCandidateEvent processed;

        private RecordingLiquidationService() {
            super(null, null, null, null, null, null, null);
        }

        @Override
        public void processCandidate(LiquidationCandidateEvent event) {
            processed = event;
        }
    }
}
