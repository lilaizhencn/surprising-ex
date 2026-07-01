package com.surprising.trading.trigger.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "surprising.trading.trigger")
public class TriggerProperties {

    private Kafka kafka = new Kafka();
    private Execution execution = new Execution();

    public Kafka getKafka() {
        return kafka;
    }

    public void setKafka(Kafka kafka) {
        this.kafka = kafka;
    }

    public Execution getExecution() {
        return execution;
    }

    public void setExecution(Execution execution) {
        this.execution = execution;
    }

    public static class Kafka {
        private String bootstrapServers = "localhost:9092";
        private String groupId = "surprising-trigger-v1";
        private String markPriceTopic = "surprising.perp.mark.price.v1";
        private int concurrency = 2;
        private int maxPollRecords = 500;

        public String getBootstrapServers() {
            return bootstrapServers;
        }

        public void setBootstrapServers(String bootstrapServers) {
            this.bootstrapServers = bootstrapServers;
        }

        public String getGroupId() {
            return groupId;
        }

        public void setGroupId(String groupId) {
            this.groupId = groupId;
        }

        public String getMarkPriceTopic() {
            return markPriceTopic;
        }

        public void setMarkPriceTopic(String markPriceTopic) {
            this.markPriceTopic = markPriceTopic;
        }

        public int getConcurrency() {
            return concurrency;
        }

        public void setConcurrency(int concurrency) {
            this.concurrency = concurrency;
        }

        public int getMaxPollRecords() {
            return maxPollRecords;
        }

        public void setMaxPollRecords(int maxPollRecords) {
            this.maxPollRecords = maxPollRecords;
        }
    }

    public static class Execution {
        private int triggerBatchSize = 200;
        private Duration staleTriggeringAfter = Duration.ofSeconds(30);
        private long maintenanceDelayMs = 1000L;

        public int getTriggerBatchSize() {
            return triggerBatchSize;
        }

        public void setTriggerBatchSize(int triggerBatchSize) {
            this.triggerBatchSize = triggerBatchSize;
        }

        public Duration getStaleTriggeringAfter() {
            return staleTriggeringAfter;
        }

        public void setStaleTriggeringAfter(Duration staleTriggeringAfter) {
            this.staleTriggeringAfter = staleTriggeringAfter;
        }

        public long getMaintenanceDelayMs() {
            return maintenanceDelayMs;
        }

        public void setMaintenanceDelayMs(long maintenanceDelayMs) {
            this.maintenanceDelayMs = maintenanceDelayMs;
        }
    }
}
