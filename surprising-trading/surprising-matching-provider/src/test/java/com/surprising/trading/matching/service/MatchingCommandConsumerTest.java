package com.surprising.trading.matching.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.trading.api.model.OrderCommandEvent;
import com.surprising.trading.api.model.OrderCommandType;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.TimeInForce;
import java.time.Instant;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class MatchingCommandConsumerTest {

    @Test
    void requestsMatcherRestartWhenDecodedCommandProcessingFails() {
        ObjectMapper objectMapper = new ObjectMapper();
        FailingMatchingService matchingService = new FailingMatchingService();
        RecordingPartitionGuard guard = new RecordingPartitionGuard();
        MatchingCommandConsumer consumer = new MatchingCommandConsumer(objectMapper, matchingService, guard);
        OrderCommandEvent command = new OrderCommandEvent(OrderCommandType.PLACE, 7001L, 8001L, 9001L,
                "cli-9001", "BTC-USDT", 3L, OrderSide.BUY, OrderType.LIMIT, TimeInForce.GTC,
                100L, 2L, false, false, Instant.parse("2026-07-01T00:00:00Z"));

        assertThatThrownBy(() -> consumer.onCommand(new ConsumerRecord<>("surprising.perp.order.commands.v1",
                5, 42L, "BTC-USDT", objectMapper.writeValueAsString(command))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed to process matching command");

        assertThat(matchingService.processed).isEqualTo(command);
        assertThat(guard.processedPartition).isEqualTo(5);
        assertThat(guard.restartReason)
                .contains("commandId=7001")
                .contains("orderId=8001")
                .contains("symbol=BTC-USDT");
    }

    @Test
    void doesNotMarkPartitionOrRestartForUndecodablePayload() {
        RecordingPartitionGuard guard = new RecordingPartitionGuard();
        MatchingCommandConsumer consumer = new MatchingCommandConsumer(new ObjectMapper(),
                new FailingMatchingService(), guard);

        assertThatThrownBy(() -> consumer.onCommand(new ConsumerRecord<>("surprising.perp.order.commands.v1",
                5, 42L, "BTC-USDT", "{bad json")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed to process matching command");

        assertThat(guard.processedPartition).isEqualTo(-1);
        assertThat(guard.restartReason).isNull();
    }

    @Test
    void rejectsCommandWhenKafkaKeyDoesNotMatchPayloadSymbolBeforeTouchingEngine() {
        ObjectMapper objectMapper = new ObjectMapper();
        FailingMatchingService matchingService = new FailingMatchingService();
        RecordingPartitionGuard guard = new RecordingPartitionGuard();
        MatchingCommandConsumer consumer = new MatchingCommandConsumer(objectMapper, matchingService, guard);
        OrderCommandEvent command = new OrderCommandEvent(OrderCommandType.PLACE, 7001L, 8001L, 9001L,
                "cli-9001", "BTC-USDT", 3L, OrderSide.BUY, OrderType.LIMIT, TimeInForce.GTC,
                100L, 2L, false, false, Instant.parse("2026-07-01T00:00:00Z"));

        assertThatThrownBy(() -> consumer.onCommand(new ConsumerRecord<>("surprising.perp.order.commands.v1",
                5, 42L, "ETH-USDT", objectMapper.writeValueAsString(command))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed to process matching command")
                .satisfies(ex -> assertThat(ex.getCause())
                        .hasMessageContaining("order command Kafka key must match payload symbol"));

        assertThat(matchingService.processed).isNull();
        assertThat(guard.processedPartition).isEqualTo(-1);
        assertThat(guard.restartReason).isNull();
    }

    private static final class FailingMatchingService extends MatchingService {
        private OrderCommandEvent processed;

        private FailingMatchingService() {
            super(null, null, null, null, null, null, null);
        }

        @Override
        public void process(OrderCommandEvent command) {
            processed = command;
            throw new IllegalStateException("simulated db failure after exchange-core submit");
        }
    }

    private static final class RecordingPartitionGuard extends MatchingPartitionAssignmentGuard {
        private int processedPartition = -1;
        private String restartReason;

        private RecordingPartitionGuard() {
            super(null, null);
        }

        @Override
        public void recordProcessedCommand(String topic, int partition) {
            processedPartition = partition;
        }

        @Override
        public void requestRestart(String reason) {
            restartReason = reason;
        }
    }
}
