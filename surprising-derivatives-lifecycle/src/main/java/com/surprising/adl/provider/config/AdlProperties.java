package com.surprising.adl.provider.config;

import com.surprising.product.api.ProductLine;
import com.surprising.product.api.ProductTopicNames;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "surprising.adl")
public class AdlProperties {

    private Kafka kafka = new Kafka();
    private Scanner scanner = new Scanner();
    private Aeron aeron = new Aeron();

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
    public Aeron getAeron() { return aeron; }
    public void setAeron(Aeron aeron) { this.aeron = aeron == null ? new Aeron() : aeron; }

    public static class Kafka {
        private ProductLine productLine = ProductLine.LINEAR_PERPETUAL;
        private String bootstrapServers = "localhost:9092";
        private String positionRiskEventsTopic = "surprising.risk.position.events.v1";
        private String groupId = "surprising-adl-risk-index-v1";

        public ProductLine getProductLine() {
            return productLine;
        }

        public void setProductLine(ProductLine productLine) {
            this.productLine = productLine == null ? ProductLine.LINEAR_PERPETUAL : productLine;
        }

        public String getAccountType() {
            return productLine.accountTypeCode();
        }
        public String getBootstrapServers() { return bootstrapServers; }
        public void setBootstrapServers(String bootstrapServers) { this.bootstrapServers = bootstrapServers; }
        public String getPositionRiskEventsTopic() {
            return productTopics().positionRiskEventsTopic();
        }
        public void setPositionRiskEventsTopic(String positionRiskEventsTopic) { this.positionRiskEventsTopic = positionRiskEventsTopic; }
        public String getInstrumentSnapshotGroupId() {
            return "surprising-" + productLine.topicSegment() + "-adl-instrument-snapshot-v1";
        }
        public String getGroupId() { return productTopics().consumerGroup("adl-risk-index"); }
        public void setGroupId(String groupId) { this.groupId = groupId; }
        public String getUserCommandsTopic() {
            return productTopics().accountUserCommandsTopic();
        }
        private ProductTopicNames productTopics() {
            return ProductTopicNames.of(productLine);
        }
    }

    public static class Aeron {
        private java.util.List<String> hostnames = java.util.List.of("localhost", "localhost", "localhost");
        private String egressHostname = "localhost";
        private Duration responseTimeout = Duration.ofSeconds(5);
        private int clientConnections = 2;
        public java.util.List<String> getHostnames() { return hostnames; }
        public void setHostnames(java.util.List<String> value) {
            if (value == null || value.size() != 3 || value.stream().anyMatch(host -> host == null || host.isBlank())) {
                throw new IllegalArgumentException("aeron.hostnames must contain exactly three nonblank hosts");
            }
            hostnames = java.util.List.copyOf(value);
        }
        public String getEgressHostname() { return egressHostname; }
        public void setEgressHostname(String value) { egressHostname = value; }
        public Duration getResponseTimeout() { return responseTimeout; }
        public void setResponseTimeout(Duration value) {
            if (value == null || value.isZero() || value.isNegative()) throw new IllegalArgumentException("aeron.response-timeout must be positive");
            responseTimeout = value;
        }
        public int getClientConnections() { return clientConnections; }
        public void setClientConnections(int value) {
            if (value < 1 || value > 64) throw new IllegalArgumentException("aeron.client-connections must be in [1,64]");
            clientConnections = value;
        }
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
