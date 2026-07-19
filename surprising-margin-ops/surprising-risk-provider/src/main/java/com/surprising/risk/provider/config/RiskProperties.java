package com.surprising.risk.provider.config;

import com.surprising.product.api.ProductLine;
import com.surprising.product.api.ProductTopicNames;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "surprising.risk")
public class RiskProperties {

    private Kafka kafka = new Kafka();
    private Calculation calculation = new Calculation();
    private Outbox outbox = new Outbox();
    private RedisState redisState = new RedisState();

    public Kafka getKafka() {
        return kafka;
    }

    public void setKafka(Kafka kafka) {
        this.kafka = kafka;
    }

    public Calculation getCalculation() {
        return calculation;
    }

    public void setCalculation(Calculation calculation) {
        this.calculation = calculation;
    }

    public Outbox getOutbox() {
        return outbox;
    }

    public void setOutbox(Outbox outbox) {
        this.outbox = outbox;
    }

    public RedisState getRedisState() {
        return redisState;
    }

    public void setRedisState(RedisState redisState) {
        this.redisState = redisState;
    }

    public static class Kafka {
        private String bootstrapServers = "localhost:9092";
        private ProductLine productLine = ProductLine.LINEAR_PERPETUAL;
        private boolean productTopicsEnabled;
        private String groupId = "surprising-risk-v1";
        private String positionEventsTopic = "surprising.account.position.events.v1";
        private String accountRiskEventsTopic = "surprising.risk.account.events.v1";
        private String positionRiskEventsTopic = "surprising.risk.position.events.v1";
        private String liquidationCandidatesTopic = "surprising.perp.liquidation.candidates.v1";
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
            return productTopicsEnabled ? productTopics().consumerGroup("risk") : groupId;
        }

        public void setGroupId(String groupId) {
            this.groupId = groupId;
        }

        public String getPositionEventsTopic() {
            return productTopicsEnabled ? productTopics().accountPositionEventsTopic() : positionEventsTopic;
        }

        public void setPositionEventsTopic(String positionEventsTopic) {
            this.positionEventsTopic = positionEventsTopic;
        }

        public String getAccountRiskEventsTopic() {
            return productTopicsEnabled ? productTopics().accountRiskEventsTopic() : accountRiskEventsTopic;
        }

        public void setAccountRiskEventsTopic(String accountRiskEventsTopic) {
            this.accountRiskEventsTopic = accountRiskEventsTopic;
        }

        public String getPositionRiskEventsTopic() {
            return productTopicsEnabled ? productTopics().positionRiskEventsTopic() : positionRiskEventsTopic;
        }

        public void setPositionRiskEventsTopic(String positionRiskEventsTopic) {
            this.positionRiskEventsTopic = positionRiskEventsTopic;
        }

        public String getLiquidationCandidatesTopic() {
            return productTopicsEnabled ? productTopics().liquidationCandidatesTopic() : liquidationCandidatesTopic;
        }

        public void setLiquidationCandidatesTopic(String liquidationCandidatesTopic) {
            this.liquidationCandidatesTopic = liquidationCandidatesTopic;
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

    public static class Calculation {
        private boolean enabled = true;
        private long scanDelayMs = 1000L;
        private long warningMarginRatioPpm = 800_000L;
        private long liquidationMarginRatioPpm = 1_000_000L;
        private Duration maxMarkAge = Duration.ofSeconds(10);
        private int scanBatchSize = 500;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getScanDelayMs() {
            return scanDelayMs;
        }

        public void setScanDelayMs(long scanDelayMs) {
            this.scanDelayMs = scanDelayMs;
        }

        public long getWarningMarginRatioPpm() {
            return warningMarginRatioPpm;
        }

        public void setWarningMarginRatioPpm(long warningMarginRatioPpm) {
            this.warningMarginRatioPpm = warningMarginRatioPpm;
        }

        public long getLiquidationMarginRatioPpm() {
            return liquidationMarginRatioPpm;
        }

        public void setLiquidationMarginRatioPpm(long liquidationMarginRatioPpm) {
            this.liquidationMarginRatioPpm = liquidationMarginRatioPpm;
        }

        public Duration getMaxMarkAge() {
            return maxMarkAge;
        }

        public void setMaxMarkAge(Duration maxMarkAge) {
            this.maxMarkAge = maxMarkAge;
        }

        public int getScanBatchSize() {
            return scanBatchSize;
        }

        public void setScanBatchSize(int scanBatchSize) {
            this.scanBatchSize = scanBatchSize;
        }
    }

    public static class Outbox {
        private int batchSize = 1000;
        private long publishDelayMs = 20L;
        private Duration sendTimeout = Duration.ofSeconds(3);
        private boolean asyncEnabled = true;
        private int maxInFlight = 32;
        private int maxRowsPerKey = 32;
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

        public boolean isAsyncEnabled() {
            return asyncEnabled;
        }

        public void setAsyncEnabled(boolean asyncEnabled) {
            this.asyncEnabled = asyncEnabled;
        }

        public int getMaxInFlight() {
            return maxInFlight;
        }

        public void setMaxInFlight(int maxInFlight) {
            this.maxInFlight = maxInFlight;
        }

        public int getMaxRowsPerKey() {
            return maxRowsPerKey;
        }

        public void setMaxRowsPerKey(int maxRowsPerKey) {
            this.maxRowsPerKey = maxRowsPerKey;
        }

        public Duration getRetention() {
            return retention;
        }

        public void setRetention(Duration retention) {
            if (retention == null || retention.isZero() || retention.isNegative()) {
                throw new IllegalArgumentException("risk outbox retention must be positive");
            }
            this.retention = retention;
        }

        public long getCleanupDelayMs() {
            return cleanupDelayMs;
        }

        public void setCleanupDelayMs(long cleanupDelayMs) {
            if (cleanupDelayMs <= 0) {
                throw new IllegalArgumentException("risk outbox cleanupDelayMs must be positive");
            }
            this.cleanupDelayMs = cleanupDelayMs;
        }

        public int getCleanupBatchSize() {
            return cleanupBatchSize;
        }

        public void setCleanupBatchSize(int cleanupBatchSize) {
            if (cleanupBatchSize <= 0) {
                throw new IllegalArgumentException("risk outbox cleanupBatchSize must be positive");
            }
            this.cleanupBatchSize = cleanupBatchSize;
        }

        public int getCleanupMaxBatches() {
            return cleanupMaxBatches;
        }

        public void setCleanupMaxBatches(int cleanupMaxBatches) {
            if (cleanupMaxBatches <= 0) {
                throw new IllegalArgumentException("risk outbox cleanupMaxBatches must be positive");
            }
            this.cleanupMaxBatches = cleanupMaxBatches;
        }
    }

    public static class RedisState {
        private String keyPrefix = "surprising:risk-state:v2";
        private Duration stateTtl = Duration.ofMinutes(10);
        private Duration readyTtl = Duration.ofSeconds(30);
        private Duration unchangedTriggerInterval = Duration.ofSeconds(30);
        private int triggerBatchSize = 250;
        private int triggerConcurrency = 4;

        public String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }

        public Duration getStateTtl() {
            return stateTtl;
        }

        public void setStateTtl(Duration stateTtl) {
            this.stateTtl = stateTtl;
        }

        public Duration getReadyTtl() {
            return readyTtl;
        }

        public void setReadyTtl(Duration readyTtl) {
            this.readyTtl = readyTtl;
        }

        public Duration getUnchangedTriggerInterval() {
            return unchangedTriggerInterval;
        }

        public void setUnchangedTriggerInterval(Duration unchangedTriggerInterval) {
            this.unchangedTriggerInterval = unchangedTriggerInterval;
        }

        public int getTriggerBatchSize() {
            return triggerBatchSize;
        }

        public void setTriggerBatchSize(int triggerBatchSize) {
            this.triggerBatchSize = triggerBatchSize;
        }

        public int getTriggerConcurrency() {
            return triggerConcurrency;
        }

        public void setTriggerConcurrency(int triggerConcurrency) {
            this.triggerConcurrency = triggerConcurrency;
        }
    }

}
