package com.surprising.instrument.provider.config;

import lombok.Getter;
import lombok.Setter;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "surprising.instrument")
public class InstrumentProperties {

    private Kafka kafka = new Kafka();
    private Lifecycle lifecycle = new Lifecycle();
    private Outbox outbox = new Outbox();

    @Getter
    @Setter
    public static class Kafka {
        private String bootstrapServers = "localhost:9092";
        private String lifecycleDrainTopic = "surprising.instrument.lifecycle-drain.v1";
        private String lifecycleDrainGroupId = "surprising-instrument-lifecycle-drain-v1";
        private String deliverySettlementsTopic;
        private String optionExercisesTopic;

    }

    @Getter
    @Setter
    public static class Lifecycle {
        private boolean enabled = true;
        private long pollDelayMs = 1000L;
        private int batchSize = 100;

    }

    @Getter
    @Setter
    public static class Outbox {
        private int batchSize = 100;
        private long publishDelayMs = 100L;
        private long cleanupDelayMs = 60_000L;
        private Duration sendTimeout = Duration.ofSeconds(10);
        private Duration retention = Duration.ofDays(7);
        private int cleanupBatchSize = 500;
        private int cleanupMaxBatches = 10;

    }
}
