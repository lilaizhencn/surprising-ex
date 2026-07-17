package com.surprising.liquidation.provider.config;

import com.surprising.product.api.ProductLine;
import com.surprising.product.api.ProductTopicNames;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "surprising.liquidation")
public class LiquidationProperties {

    private Kafka kafka = new Kafka();
    private Outbox outbox = new Outbox();
    private Sizing sizing = new Sizing();
    private Risk risk = new Risk();
    private Execution execution = new Execution();
    private RedisIndex redisIndex = new RedisIndex();

    public Kafka getKafka() {
        return kafka;
    }

    public void setKafka(Kafka kafka) {
        this.kafka = kafka;
    }

    public Outbox getOutbox() {
        return outbox;
    }

    public void setOutbox(Outbox outbox) {
        this.outbox = outbox;
    }

    public Sizing getSizing() {
        return sizing;
    }

    public void setSizing(Sizing sizing) {
        this.sizing = sizing;
    }

    public Risk getRisk() {
        return risk;
    }

    public void setRisk(Risk risk) {
        this.risk = risk;
    }

    public Execution getExecution() {
        return execution;
    }

    public void setExecution(Execution execution) {
        this.execution = execution;
    }
    public RedisIndex getRedisIndex() { return redisIndex; }
    public void setRedisIndex(RedisIndex redisIndex) { this.redisIndex = redisIndex; }

    public static class Kafka {
        private String bootstrapServers = "localhost:9092";
        private ProductLine productLine = ProductLine.LINEAR_PERPETUAL;
        private boolean productTopicsEnabled;
        private String groupId = "surprising-liquidation-v1";
        private String liquidationCandidatesTopic = "surprising.perp.liquidation.candidates.v1";
        private String matchResultsTopic = "surprising.perp.match.results.v1";
        private String orderCommandsTopic = "surprising.perp.order.commands.v1";
        private String orderEventsTopic = "surprising.perp.order.events.v1";
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
            return productTopicsEnabled ? productTopics().consumerGroup("liquidation") : groupId;
        }

        public void setGroupId(String groupId) {
            this.groupId = groupId;
        }

        public String getLiquidationCandidatesTopic() {
            return productTopicsEnabled ? productTopics().liquidationCandidatesTopic() : liquidationCandidatesTopic;
        }

        public void setLiquidationCandidatesTopic(String liquidationCandidatesTopic) {
            this.liquidationCandidatesTopic = liquidationCandidatesTopic;
        }

        public String getMatchResultsTopic() {
            return productTopicsEnabled ? productTopics().matchResultsTopic() : matchResultsTopic;
        }

        public void setMatchResultsTopic(String matchResultsTopic) {
            this.matchResultsTopic = matchResultsTopic;
        }

        public String getOrderCommandsTopic() {
            return productTopicsEnabled ? productTopics().orderCommandsTopic() : orderCommandsTopic;
        }

        public void setOrderCommandsTopic(String orderCommandsTopic) {
            this.orderCommandsTopic = orderCommandsTopic;
        }

        public String getOrderEventsTopic() {
            return productTopicsEnabled ? productTopics().orderEventsTopic() : orderEventsTopic;
        }

        public void setOrderEventsTopic(String orderEventsTopic) {
            this.orderEventsTopic = orderEventsTopic;
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

    public static class Outbox {
        private int batchSize = 200;
        private long publishDelayMs = 100L;
        private Duration sendTimeout = Duration.ofSeconds(3);

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
    }

    public static class Sizing {
        private long normalCloseRatioPpm = 500_000L;
        private long severeMarginRatioPpm = 1_500_000L;
        private long severeCloseRatioPpm = 750_000L;
        private long fullCloseMarginRatioPpm = 3_000_000L;
        private long minCloseSteps = 1L;

        public long getNormalCloseRatioPpm() {
            return normalCloseRatioPpm;
        }

        public void setNormalCloseRatioPpm(long normalCloseRatioPpm) {
            this.normalCloseRatioPpm = normalCloseRatioPpm;
        }

        public long getSevereMarginRatioPpm() {
            return severeMarginRatioPpm;
        }

        public void setSevereMarginRatioPpm(long severeMarginRatioPpm) {
            this.severeMarginRatioPpm = severeMarginRatioPpm;
        }

        public long getSevereCloseRatioPpm() {
            return severeCloseRatioPpm;
        }

        public void setSevereCloseRatioPpm(long severeCloseRatioPpm) {
            this.severeCloseRatioPpm = severeCloseRatioPpm;
        }

        public long getFullCloseMarginRatioPpm() {
            return fullCloseMarginRatioPpm;
        }

        public void setFullCloseMarginRatioPpm(long fullCloseMarginRatioPpm) {
            this.fullCloseMarginRatioPpm = fullCloseMarginRatioPpm;
        }

        public long getMinCloseSteps() {
            return minCloseSteps;
        }

        public void setMinCloseSteps(long minCloseSteps) {
            this.minCloseSteps = minCloseSteps;
        }
    }

    public static class Risk {
        private Duration maxSnapshotAge = Duration.ofSeconds(5);

        public Duration getMaxSnapshotAge() {
            return maxSnapshotAge;
        }

        public void setMaxSnapshotAge(Duration maxSnapshotAge) {
            this.maxSnapshotAge = maxSnapshotAge;
        }
    }

    public static class Execution {
        private boolean enabled = true;
        private long liquidationFeeRatePpm = 3_000L;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getLiquidationFeeRatePpm() {
            return liquidationFeeRatePpm;
        }

        public void setLiquidationFeeRatePpm(long liquidationFeeRatePpm) {
            this.liquidationFeeRatePpm = liquidationFeeRatePpm;
        }
    }

    public static class RedisIndex {
        private String keyPrefix = "surprising:liquidation:v1";
        private int candidateBatchSize = 100;
        public String getKeyPrefix() { return keyPrefix; }
        public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }
        public int getCandidateBatchSize() { return candidateBatchSize; }
        public void setCandidateBatchSize(int candidateBatchSize) { this.candidateBatchSize = candidateBatchSize; }
    }
}
