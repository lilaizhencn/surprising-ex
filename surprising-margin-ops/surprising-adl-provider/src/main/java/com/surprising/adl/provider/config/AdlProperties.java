package com.surprising.adl.provider.config;

import com.surprising.product.api.ProductLine;
import com.surprising.product.api.ProductTopicNames;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "surprising.adl")
public class AdlProperties {

    private Kafka kafka = new Kafka();
    private Scanner scanner = new Scanner();
    private RedisIndex redisIndex = new RedisIndex();

    public Kafka getKafka() {
        return kafka;
    }

    public void setKafka(Kafka kafka) {
        this.kafka = kafka == null ? new Kafka() : kafka;
    }

    public Scanner getScanner() {
        return scanner;
    }

    public void setScanner(Scanner scanner) {
        this.scanner = scanner;
    }
    public RedisIndex getRedisIndex() { return redisIndex; }
    public void setRedisIndex(RedisIndex redisIndex) { this.redisIndex = redisIndex; }

    public static class Kafka {
        private ProductLine productLine = ProductLine.LINEAR_PERPETUAL;
        private boolean productTopicsEnabled;
        private String bootstrapServers = "localhost:9092";
        private String positionRiskEventsTopic = "surprising.risk.position.events.v1";
        private String groupId = "surprising-adl-risk-index-v1";

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

        public String getAccountType() {
            return productLine.accountTypeCode();
        }
        public String getBootstrapServers() { return bootstrapServers; }
        public void setBootstrapServers(String bootstrapServers) { this.bootstrapServers = bootstrapServers; }
        public String getPositionRiskEventsTopic() {
            return productTopicsEnabled ? productTopics().positionRiskEventsTopic() : positionRiskEventsTopic;
        }
        public void setPositionRiskEventsTopic(String positionRiskEventsTopic) { this.positionRiskEventsTopic = positionRiskEventsTopic; }
        public String getGroupId() { return productTopicsEnabled ? productTopics().consumerGroup("adl-risk-index") : groupId; }
        public void setGroupId(String groupId) { this.groupId = groupId; }
        public String getUserCommandsTopic() {
            return productTopics().accountUserCommandsTopic();
        }
        private ProductTopicNames productTopics() {
            return ProductTopicNames.of(productLine);
        }
    }

    public static class RedisIndex {
        private String keyPrefix = "surprising:adl:v1";
        private long readyTtlMs = 30_000L;
        public String getKeyPrefix() { return keyPrefix; }
        public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }
        public long getReadyTtlMs() { return readyTtlMs; }
        public void setReadyTtlMs(long readyTtlMs) { this.readyTtlMs = readyTtlMs; }
    }

    public static class Scanner {
        private boolean enabled = true;
        private long scanDelayMs = 1000L;
        private long minDeficitAgeMs = 10_000L;
        private long maxMarkAgeMs = 5_000L;
        private int batchSize = 50;
        private int maxDeleveragesPerDeficit = 20;
        private int candidateMultiplier = 5;

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

        public long getMinDeficitAgeMs() {
            return minDeficitAgeMs;
        }

        public void setMinDeficitAgeMs(long minDeficitAgeMs) {
            this.minDeficitAgeMs = minDeficitAgeMs;
        }

        public long getMaxMarkAgeMs() {
            return maxMarkAgeMs;
        }

        public void setMaxMarkAgeMs(long maxMarkAgeMs) {
            this.maxMarkAgeMs = maxMarkAgeMs;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public int getMaxDeleveragesPerDeficit() {
            return maxDeleveragesPerDeficit;
        }

        public void setMaxDeleveragesPerDeficit(int maxDeleveragesPerDeficit) {
            this.maxDeleveragesPerDeficit = maxDeleveragesPerDeficit;
        }

        public int getCandidateMultiplier() {
            return candidateMultiplier;
        }

        public void setCandidateMultiplier(int candidateMultiplier) {
            this.candidateMultiplier = candidateMultiplier;
        }
    }
}
