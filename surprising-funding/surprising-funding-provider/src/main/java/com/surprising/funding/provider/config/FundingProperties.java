package com.surprising.funding.provider.config;

import com.surprising.product.api.ProductLine;
import com.surprising.product.api.ProductLineConfiguration;
import com.surprising.product.api.ProductTopicNames;
import jakarta.annotation.PostConstruct;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "surprising.funding")
public class FundingProperties {

    @Valid
    private Kafka kafka = new Kafka();
    @Valid
    private Calculation calculation = new Calculation();
    @Valid
    private Settlement settlement = new Settlement();
    @Valid
    private Coordination coordination = new Coordination();
    @Valid
    private Aeron aeron = new Aeron();

    /** 启动时拒绝未隔离的资金费 Topic 配置。 */
    @PostConstruct
    void validateProductLineConfiguration() {
        ProductLineConfiguration.require(kafka.productLine, kafka.productTopicsEnabled, "funding");
    }

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

    public Settlement getSettlement() {
        return settlement;
    }

    public void setSettlement(Settlement settlement) {
        this.settlement = settlement;
    }

    public Coordination getCoordination() {
        return coordination;
    }

    public void setCoordination(Coordination coordination) {
        this.coordination = coordination;
    }

    public Aeron getAeron() {
        return aeron;
    }

    public void setAeron(Aeron aeron) {
        this.aeron = aeron == null ? new Aeron() : aeron;
    }

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
        public void setEgressHostname(String egressHostname) {
            if (egressHostname == null || egressHostname.isBlank()) {
                throw new IllegalArgumentException("aeron egress hostname is required");
            }
            this.egressHostname = egressHostname.trim();
        }
        public Duration getResponseTimeout() { return responseTimeout; }
        public void setResponseTimeout(Duration responseTimeout) {
            if (responseTimeout == null || responseTimeout.isZero() || responseTimeout.isNegative()) {
                throw new IllegalArgumentException("aeron response timeout must be positive");
            }
            this.responseTimeout = responseTimeout;
        }
        public int getClientConnections() { return clientConnections; }
        public void setClientConnections(int clientConnections) {
            if (clientConnections < 1 || clientConnections > 64) {
                throw new IllegalArgumentException("aeron client connections must be in [1,64]");
            }
            this.clientConnections = clientConnections;
        }
    }

    public static class Kafka {
        private String bootstrapServers = "localhost:9092";
        private ProductLine productLine = ProductLine.LINEAR_PERPETUAL;
        private boolean productTopicsEnabled;
        private String fundingRateTopic = "surprising.perp.funding.rate.v1";
        private String cacheGroupId = "surprising-funding-rate-cache-local";
        private int concurrency = 1;
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

        public String getFundingRateTopic() {
            return isFundingProductLine() && productTopicsEnabled
                    ? ProductTopicNames.of(productLine).fundingRateTopic()
                    : fundingRateTopic;
        }

        public boolean isFundingProductLine() {
            return !productTopicsEnabled || productLine.isFundingProduct();
        }

        public void setFundingRateTopic(String fundingRateTopic) {
            this.fundingRateTopic = fundingRateTopic;
        }

        public String getInstrumentSnapshotGroupId() {
            return "surprising-" + productLine.topicSegment() + "-funding-instrument-snapshot-v1";
        }

        public String getCacheGroupId() {
            return cacheGroupId;
        }

        public void setCacheGroupId(String cacheGroupId) {
            this.cacheGroupId = cacheGroupId;
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

    public static class Calculation {
        private boolean enabled = true;
        private long publishDelayMs = 1000L;
        private Duration maxMarkAge = Duration.ofSeconds(10);
        private Duration maxRateAge = Duration.ofSeconds(5);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getPublishDelayMs() {
            return publishDelayMs;
        }

        public void setPublishDelayMs(long publishDelayMs) {
            this.publishDelayMs = publishDelayMs;
        }

        public Duration getMaxMarkAge() {
            return maxMarkAge;
        }

        public void setMaxMarkAge(Duration maxMarkAge) {
            this.maxMarkAge = maxMarkAge;
        }

        public Duration getMaxRateAge() {
            return maxRateAge;
        }

        public void setMaxRateAge(Duration maxRateAge) {
            this.maxRateAge = maxRateAge;
        }
    }

    public static class Settlement {
        private boolean enabled = true;
        @Min(1)
        private long settleDelayMs = 1000L;
        @Min(1)
        @Max(10_000)
        private int batchSize = 20;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getSettleDelayMs() {
            return settleDelayMs;
        }

        public void setSettleDelayMs(long settleDelayMs) {
            this.settleDelayMs = settleDelayMs;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

    }

    public static class Coordination {
        private boolean enabled = true;
        private String nodeId;
        private Duration leaseDuration = Duration.ofSeconds(15);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getNodeId() {
            return nodeId;
        }

        public void setNodeId(String nodeId) {
            this.nodeId = nodeId;
        }

        public Duration getLeaseDuration() {
            return leaseDuration;
        }

        public void setLeaseDuration(Duration leaseDuration) {
            this.leaseDuration = leaseDuration;
        }
    }
}
