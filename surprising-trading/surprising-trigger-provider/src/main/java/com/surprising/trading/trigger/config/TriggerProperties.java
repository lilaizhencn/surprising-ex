package com.surprising.trading.trigger.config;

import com.surprising.product.api.ProductLine;
import com.surprising.product.api.ProductLineConfiguration;
import com.surprising.product.api.ProductTopicNames;
import java.time.Duration;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "surprising.trading.trigger")
public class TriggerProperties {

    private Kafka kafka = new Kafka();
    private Execution execution = new Execution();
    private RedisIndex redisIndex = new RedisIndex();
    private Outbox outbox = new Outbox();
    private Aeron aeron = new Aeron();

    /** 启动时拒绝未隔离的条件单 Topic 配置。 */
    @PostConstruct
    void validateProductLineConfiguration() {
        ProductLineConfiguration.require(kafka.productLine, kafka.productTopicsEnabled, "trigger");
    }

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

    public RedisIndex getRedisIndex() {
        return redisIndex;
    }

    public void setRedisIndex(RedisIndex redisIndex) {
        this.redisIndex = redisIndex;
    }

    public Outbox getOutbox() {
        return outbox;
    }

    public Aeron getAeron() { return aeron; }
    public void setAeron(Aeron aeron) { this.aeron = aeron == null ? new Aeron() : aeron; }

    public static class Aeron {
        private java.util.List<String> hostnames = java.util.List.of("localhost", "localhost", "localhost");
        private String egressHostname = "localhost";
        private java.time.Duration responseTimeout = java.time.Duration.ofSeconds(5);
        private int clientConnections = 4;
        private int nodeId;
        public java.util.List<String> getHostnames() { return hostnames; }
        public void setHostnames(java.util.List<String> hostnames) { this.hostnames = java.util.List.copyOf(hostnames); }
        public String getEgressHostname() { return egressHostname; }
        public void setEgressHostname(String value) { this.egressHostname = value; }
        public java.time.Duration getResponseTimeout() { return responseTimeout; }
        public void setResponseTimeout(java.time.Duration value) { this.responseTimeout = value; }
        public int getClientConnections() { return clientConnections; }
        public void setClientConnections(int value) { this.clientConnections = value; }
        public int getNodeId() { return nodeId; }
        public void setNodeId(int value) { this.nodeId = value; }
    }

    public void setOutbox(Outbox outbox) {
        this.outbox = outbox;
    }

    public static class Kafka {
        private String bootstrapServers = "localhost:9092";
        /** 必须由部署配置显式指定，禁止缺省落到永续产品线。 */
        private ProductLine productLine;
        private boolean productTopicsEnabled;
        private String groupId = "surprising-trigger-v1";
        private String markPriceTopic = "surprising.perp.mark.price.v1";
        private String positionEventsTopic = "surprising.account.position.events.v1";
        private String triggerOrderEventsTopic = "surprising.perp.trigger-order.events.v1";
        private String instrumentLifecycleDrainTopic = "surprising.instrument.lifecycle-drain.v1";
        private int concurrency = 2;
        private int maxPollRecords = 500;

        public String getBootstrapServers() {
            return bootstrapServers;
        }

        public void setBootstrapServers(String bootstrapServers) {
            this.bootstrapServers = bootstrapServers;
        }

        public ProductLine getProductLine() {
            return productLine;
        }

        public void setProductLine(ProductLine productLine) {
            this.productLine = productLine;
        }

        public boolean isProductTopicsEnabled() {
            return productTopicsEnabled;
        }

        public void setProductTopicsEnabled(boolean productTopicsEnabled) {
            this.productTopicsEnabled = productTopicsEnabled;
        }

        public String getGroupId() {
            return productTopicsEnabled ? productTopics().consumerGroup("trigger") : groupId;
        }

        public void setGroupId(String groupId) {
            this.groupId = groupId;
        }

        public String getMarkPriceTopic() {
            return productTopicsEnabled ? productTopics().markPriceTopic() : markPriceTopic;
        }

        public void setMarkPriceTopic(String markPriceTopic) {
            this.markPriceTopic = markPriceTopic;
        }

        public String getPositionEventsTopic() {
            return productTopicsEnabled ? productTopics().accountPositionEventsTopic() : positionEventsTopic;
        }

        public void setPositionEventsTopic(String positionEventsTopic) {
            this.positionEventsTopic = positionEventsTopic;
        }

        public String getTriggerOrderEventsTopic() {
            return productTopicsEnabled ? productTopics().triggerOrderEventsTopic() : triggerOrderEventsTopic;
        }

        public void setTriggerOrderEventsTopic(String triggerOrderEventsTopic) {
            this.triggerOrderEventsTopic = triggerOrderEventsTopic;
        }

        public String getInstrumentSnapshotGroupId() {
            return "surprising-" + productLine.topicSegment() + "-trigger-instrument-snapshot-v1";
        }

        public String getInstrumentLifecycleDrainTopic() {
            return instrumentLifecycleDrainTopic;
        }

        public void setInstrumentLifecycleDrainTopic(String instrumentLifecycleDrainTopic) {
            this.instrumentLifecycleDrainTopic = instrumentLifecycleDrainTopic;
        }

        public String getInstrumentLifecycleGroupId() {
            return productTopics().consumerGroup("trigger-instrument-lifecycle");
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

        private ProductTopicNames productTopics() {
            return ProductTopicNames.of(productLine);
        }
    }

    public static class Execution {
        private int triggerBatchSize = 200;
        private int maxTriggerScanPages = 16;
        private Duration staleTriggeringAfter = Duration.ofSeconds(30);
        private long maintenanceDelayMs = 1000L;
        private boolean coreOnly;

        public int getTriggerBatchSize() {
            return triggerBatchSize;
        }

        public void setTriggerBatchSize(int triggerBatchSize) {
            this.triggerBatchSize = triggerBatchSize;
        }

        public int getMaxTriggerScanPages() {
            return maxTriggerScanPages;
        }

        public void setMaxTriggerScanPages(int maxTriggerScanPages) {
            this.maxTriggerScanPages = maxTriggerScanPages;
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

        public boolean isCoreOnly() {
            return coreOnly;
        }

        public void setCoreOnly(boolean coreOnly) {
            this.coreOnly = coreOnly;
        }
    }

    public static class RedisIndex {
        private String keyPrefix = "surprising:trigger:v1";
        private int candidateBatchSize = 400;
        private int rebuildBatchSize = 1_000;
        private long reconcileDelayMs = 10_000L;
        private Duration readyTtl = Duration.ofSeconds(30);
        private Duration lockTtl = Duration.ofSeconds(30);

        public String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }

        public int getCandidateBatchSize() {
            return candidateBatchSize;
        }

        public void setCandidateBatchSize(int candidateBatchSize) {
            this.candidateBatchSize = candidateBatchSize;
        }

        public int getRebuildBatchSize() {
            return rebuildBatchSize;
        }

        public void setRebuildBatchSize(int rebuildBatchSize) {
            this.rebuildBatchSize = rebuildBatchSize;
        }

        public long getReconcileDelayMs() {
            return reconcileDelayMs;
        }

        public void setReconcileDelayMs(long reconcileDelayMs) {
            this.reconcileDelayMs = reconcileDelayMs;
        }

        public Duration getReadyTtl() {
            return readyTtl;
        }

        public void setReadyTtl(Duration readyTtl) {
            this.readyTtl = readyTtl;
        }

        public Duration getLockTtl() {
            return lockTtl;
        }

        public void setLockTtl(Duration lockTtl) {
            this.lockTtl = lockTtl;
        }
    }

    public static class Outbox {
        private int batchSize = 200;
        private long publishDelayMs = 200L;
        private Duration sendTimeout = Duration.ofSeconds(3);
        private int maxInFlight = 32;
        private Duration retention = Duration.ofDays(7);
        private long cleanupDelayMs = 60_000L;
        private int cleanupBatchSize = 10_000;
        private int cleanupMaxBatches = 10;

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public long getPublishDelayMs() {
            return publishDelayMs;
        }

        public void setPublishDelayMs(long publishDelayMs) {
            this.publishDelayMs = publishDelayMs;
        }

        public Duration getSendTimeout() {
            return sendTimeout;
        }

        public void setSendTimeout(Duration sendTimeout) {
            this.sendTimeout = sendTimeout;
        }

        public int getMaxInFlight() {
            return maxInFlight;
        }

        public void setMaxInFlight(int maxInFlight) {
            if (maxInFlight <= 0) {
                throw new IllegalArgumentException("trigger outbox maxInFlight must be positive");
            }
            this.maxInFlight = maxInFlight;
        }

        public Duration getRetention() {
            return retention;
        }

        public void setRetention(Duration retention) {
            if (retention == null || retention.isZero() || retention.isNegative()) {
                throw new IllegalArgumentException("trigger outbox retention must be positive");
            }
            this.retention = retention;
        }

        public long getCleanupDelayMs() {
            return cleanupDelayMs;
        }

        public void setCleanupDelayMs(long cleanupDelayMs) {
            if (cleanupDelayMs <= 0) {
                throw new IllegalArgumentException("trigger outbox cleanupDelayMs must be positive");
            }
            this.cleanupDelayMs = cleanupDelayMs;
        }

        public int getCleanupBatchSize() {
            return cleanupBatchSize;
        }

        public void setCleanupBatchSize(int cleanupBatchSize) {
            if (cleanupBatchSize <= 0) {
                throw new IllegalArgumentException("trigger outbox cleanupBatchSize must be positive");
            }
            this.cleanupBatchSize = cleanupBatchSize;
        }

        public int getCleanupMaxBatches() {
            return cleanupMaxBatches;
        }

        public void setCleanupMaxBatches(int cleanupMaxBatches) {
            if (cleanupMaxBatches <= 0) {
                throw new IllegalArgumentException("trigger outbox cleanupMaxBatches must be positive");
            }
            this.cleanupMaxBatches = cleanupMaxBatches;
        }
    }
}
