package com.surprising.trading.trigger.config;

import com.surprising.product.api.ProductLine;
import com.surprising.product.api.ProductTopicNames;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "surprising.trading.trigger")
public class TriggerProperties {

    private Kafka kafka = new Kafka();
    private Execution execution = new Execution();
    private RedisIndex redisIndex = new RedisIndex();

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

    public static class Kafka {
        private String bootstrapServers = "localhost:9092";
        private ProductLine productLine = ProductLine.LINEAR_PERPETUAL;
        private boolean productTopicsEnabled;
        private String groupId = "surprising-trigger-v1";
        private String markPriceTopic = "surprising.perp.mark.price.v1";
        private String indexPriceTopic = "surprising.perp.index.price.v1";
        private String lastPriceTopic = "surprising.perp.match.trades.v1";
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
            this.productLine = productLine == null ? ProductLine.LINEAR_PERPETUAL : productLine;
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

        public String getIndexPriceTopic() {
            return productTopicsEnabled ? productTopics().indexPriceTopic() : indexPriceTopic;
        }

        public void setIndexPriceTopic(String indexPriceTopic) {
            this.indexPriceTopic = indexPriceTopic;
        }

        public String getLastPriceTopic() {
            return productTopicsEnabled ? productTopics().matchTradesTopic() : lastPriceTopic;
        }

        public void setLastPriceTopic(String lastPriceTopic) {
            this.lastPriceTopic = lastPriceTopic;
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

    public static class RedisIndex {
        private boolean enabled;
        private String keyPrefix = "surprising:trigger:v1";
        private int candidateBatchSize = 400;
        private int rebuildBatchSize = 1_000;
        private long reconcileDelayMs = 10_000L;
        private Duration readyTtl = Duration.ofSeconds(30);
        private Duration lockTtl = Duration.ofSeconds(30);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

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
}
