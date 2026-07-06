package com.surprising.instrument.provider.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "surprising.instrument")
public class InstrumentProperties {

    private Kafka kafka = new Kafka();
    private Lifecycle lifecycle = new Lifecycle();

    public Kafka getKafka() {
        return kafka;
    }

    public void setKafka(Kafka kafka) {
        this.kafka = kafka;
    }

    public Lifecycle getLifecycle() {
        return lifecycle;
    }

    public void setLifecycle(Lifecycle lifecycle) {
        this.lifecycle = lifecycle;
    }

    public static class Kafka {
        private String bootstrapServers = "localhost:9092";
        private String eventsTopic = "surprising.instrument.events.v1";
        private String deliverySettlementsTopic;
        private String optionExercisesTopic;

        public String getBootstrapServers() {
            return bootstrapServers;
        }

        public void setBootstrapServers(String bootstrapServers) {
            this.bootstrapServers = bootstrapServers;
        }

        public String getEventsTopic() {
            return eventsTopic;
        }

        public void setEventsTopic(String eventsTopic) {
            this.eventsTopic = eventsTopic;
        }

        public String getDeliverySettlementsTopic() {
            return deliverySettlementsTopic;
        }

        public void setDeliverySettlementsTopic(String deliverySettlementsTopic) {
            this.deliverySettlementsTopic = deliverySettlementsTopic;
        }

        public String getOptionExercisesTopic() {
            return optionExercisesTopic;
        }

        public void setOptionExercisesTopic(String optionExercisesTopic) {
            this.optionExercisesTopic = optionExercisesTopic;
        }
    }

    public static class Lifecycle {
        private boolean enabled = true;
        private long pollDelayMs = 1000L;
        private int batchSize = 100;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getPollDelayMs() {
            return pollDelayMs;
        }

        public void setPollDelayMs(long pollDelayMs) {
            this.pollDelayMs = pollDelayMs;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }
    }
}
