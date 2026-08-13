package com.surprising.insurance.provider.config;

import com.surprising.product.api.ProductLine;
import com.surprising.product.api.ProductLineConfiguration;
import com.surprising.product.api.ProductTopicNames;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "surprising.insurance")
public class InsuranceProperties {

    private Kafka kafka = new Kafka();
    private Coverage coverage = new Coverage();
    private Aeron aeron = new Aeron();

    /** 启动时拒绝未隔离的保险基金 Topic 配置。 */
    @PostConstruct
    void validateProductLineConfiguration() {
        ProductLineConfiguration.require(kafka.productLine, kafka.productTopicsEnabled, "insurance");
    }

    public Kafka getKafka() {
        return kafka;
    }

    public void setKafka(Kafka kafka) {
        this.kafka = kafka == null ? new Kafka() : kafka;
    }

    public Coverage getCoverage() {
        return coverage;
    }

    public void setCoverage(Coverage coverage) {
        this.coverage = coverage;
    }

    public Aeron getAeron() { return aeron; }
    public void setAeron(Aeron aeron) { this.aeron = aeron == null ? new Aeron() : aeron; }

    public static class Aeron {
        private java.util.List<String> hostnames = java.util.List.of("localhost", "localhost", "localhost");
        private String egressHostname = "localhost";
        private Duration responseTimeout = Duration.ofSeconds(5);
        private int clientConnections = 2;
        public java.util.List<String> getHostnames() { return hostnames; }
        public void setHostnames(java.util.List<String> hostnames) {
            if (hostnames == null || hostnames.size() != 3
                    || hostnames.stream().anyMatch(value -> value == null || value.isBlank())) {
                throw new IllegalArgumentException("aeron hostnames must contain three non-blank members");
            }
            this.hostnames = java.util.List.copyOf(hostnames);
        }
        public String getEgressHostname() { return egressHostname; }
        public void setEgressHostname(String value) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException("egress hostname is required");
            egressHostname = value.trim();
        }
        public Duration getResponseTimeout() { return responseTimeout; }
        public void setResponseTimeout(Duration value) {
            if (value == null || value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException("response timeout must be positive");
            }
            responseTimeout = value;
        }
        public int getClientConnections() { return clientConnections; }
        public void setClientConnections(int value) {
            if (value < 1 || value > 64) throw new IllegalArgumentException("client connections must be in [1,64]");
            clientConnections = value;
        }
    }

    public static class Kafka {
        private String bootstrapServers = "localhost:9092";
        private ProductLine productLine = ProductLine.LINEAR_PERPETUAL;
        private boolean productTopicsEnabled;
        private String groupId = "surprising-insurance-v1";
        private String liquidationFeeEventsTopic = "surprising.account.liquidation-fee.events.v1";
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
            return productTopicsEnabled ? productTopics().consumerGroup("insurance") : groupId;
        }

        public void setGroupId(String groupId) {
            this.groupId = groupId;
        }

        public String getLiquidationFeeEventsTopic() {
            return productTopicsEnabled
                    ? productTopics().accountLiquidationFeeEventsTopic()
                    : liquidationFeeEventsTopic;
        }

        public void setLiquidationFeeEventsTopic(String liquidationFeeEventsTopic) {
            this.liquidationFeeEventsTopic = liquidationFeeEventsTopic;
        }

        public String getInstrumentSnapshotGroupId() {
            return "surprising-" + productLine.topicSegment() + "-insurance-instrument-snapshot-v1";
        }

        public String getUserCommandsTopic() {
            return productTopics().accountUserCommandsTopic();
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

        public String getAccountType() {
            return productLine.accountTypeCode();
        }

        private ProductTopicNames productTopics() {
            return ProductTopicNames.of(productLine);
        }
    }

    public static class Coverage {
        private boolean enabled = true;
        private long scanDelayMs = 1000L;
        private int batchSize = 100;

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

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }
    }
}
