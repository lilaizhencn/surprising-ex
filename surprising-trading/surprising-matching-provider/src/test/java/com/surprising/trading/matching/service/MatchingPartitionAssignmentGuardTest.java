package com.surprising.trading.matching.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.trading.matching.config.MatchingProperties;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

class MatchingPartitionAssignmentGuardTest {

    private static final String TOPIC = "surprising.perp.order.commands.v1";

    @Test
    void restartsWhenNewPartitionIsAssignedAfterCommandsWereProcessed() throws Exception {
        MatchingProperties properties = properties(0L, true);
        RecordingGuard guard = new RecordingGuard(properties);
        TopicPartition first = new TopicPartition(TOPIC, 0);
        TopicPartition second = new TopicPartition(TOPIC, 1);

        guard.onPartitionsAssigned(null, List.of(first));
        guard.recordProcessedCommand(TOPIC, 0);
        Thread.sleep(5L);
        guard.onPartitionsAssigned(null, List.of(first, second));

        assertThat(guard.restartReasons).hasSize(1);
        assertThat(guard.restartReasons.get(0))
                .contains("new Kafka partitions assigned")
                .contains(TOPIC + "-1");
    }

    @Test
    void doesNotRestartWhenOnlyKnownPartitionsAreReassigned() throws Exception {
        MatchingProperties properties = properties(0L, true);
        RecordingGuard guard = new RecordingGuard(properties);
        TopicPartition first = new TopicPartition(TOPIC, 0);

        guard.onPartitionsAssigned(null, List.of(first));
        guard.recordProcessedCommand(TOPIC, 0);
        Thread.sleep(5L);
        guard.onPartitionsAssigned(null, List.of(first));

        assertThat(guard.restartReasons).isEmpty();
    }

    @Test
    void startupGraceAllowsInitialPartitionExpansionBeforeRestartPolicyApplies() {
        MatchingProperties properties = properties(60_000L, true);
        RecordingGuard guard = new RecordingGuard(properties);

        guard.recordProcessedCommand(TOPIC, 0);
        guard.onPartitionsAssigned(null, List.of(
                new TopicPartition(TOPIC, 0),
                new TopicPartition(TOPIC, 1)));

        assertThat(guard.restartReasons).isEmpty();
    }

    @Test
    void restartsWhenPartitionIsLost() {
        MatchingProperties properties = properties(0L, true);
        RecordingGuard guard = new RecordingGuard(properties);

        guard.onPartitionsLost(null, List.of(new TopicPartition(TOPIC, 0)));

        assertThat(guard.restartReasons).hasSize(1);
        assertThat(guard.restartReasons.get(0))
                .contains("Kafka partitions were lost")
                .contains(TOPIC + "-0");
    }

    @Test
    void canDisableRestartOnPartitionReassignmentForControlledMaintenance() throws Exception {
        MatchingProperties properties = properties(0L, false);
        RecordingGuard guard = new RecordingGuard(properties);

        guard.recordProcessedCommand(TOPIC, 0);
        Thread.sleep(5L);
        guard.onPartitionsAssigned(null, List.of(
                new TopicPartition(TOPIC, 0),
                new TopicPartition(TOPIC, 2)));
        guard.onPartitionsLost(null, List.of(new TopicPartition(TOPIC, 0)));

        assertThat(guard.restartReasons).isEmpty();
    }

    private static MatchingProperties properties(long startupGraceMs, boolean restartOnPartitionReassignment) {
        MatchingProperties properties = new MatchingProperties();
        properties.getKafka().setPartitionAssignmentStartupGraceMs(startupGraceMs);
        properties.getKafka().setRestartOnPartitionReassignment(restartOnPartitionReassignment);
        return properties;
    }

    private static final class RecordingGuard extends MatchingPartitionAssignmentGuard {
        private final List<String> restartReasons = new ArrayList<>();

        private RecordingGuard(MatchingProperties properties) {
            super(properties, null);
        }

        @Override
        public void requestRestart(String reason) {
            restartReasons.add(reason);
        }
    }
}
