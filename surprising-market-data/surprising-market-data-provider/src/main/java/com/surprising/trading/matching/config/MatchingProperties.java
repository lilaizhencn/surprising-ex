package com.surprising.trading.matching.config;

import com.surprising.product.api.ProductLine;
import com.surprising.product.api.ProductTopicNames;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "surprising.trading.matching")
public class MatchingProperties {

    private Kafka kafka = new Kafka();
    private Aeron aeron = new Aeron();
    private MarketData marketData = new MarketData();

    @PostConstruct
    void validateProductLineConfiguration() {
        if (kafka.productLine == null) {
            throw new IllegalStateException("matching-market-data 必须显式配置 product-line");
        }
    }

    public Kafka getKafka() {
        return kafka;
    }

    public void setKafka(Kafka kafka) {
        this.kafka = kafka == null ? new Kafka() : kafka;
    }

    public MarketData getMarketData() {
        return marketData;
    }

    public Aeron getAeron() {
        return aeron;
    }

    public void setAeron(Aeron aeron) {
        this.aeron = aeron == null ? new Aeron() : aeron;
    }

    public void setMarketData(MarketData marketData) {
        this.marketData = marketData == null ? new MarketData() : marketData;
    }

    public static class Kafka {
        private String bootstrapServers = "localhost:9092";
        private ProductLine productLine;
        private String clientId = "surprising-matching-market-data";
        private String coreEventsTopic;
        private int maxPollRecords = 1_024;

        public String getBootstrapServers() {
            return bootstrapServers;
        }

        public void setBootstrapServers(String bootstrapServers) {
            if (bootstrapServers == null || bootstrapServers.isBlank()) {
                throw new IllegalArgumentException("Kafka bootstrap servers are required");
            }
            this.bootstrapServers = bootstrapServers.trim();
        }

        public ProductLine getProductLine() {
            return productLine;
        }

        public void setProductLine(ProductLine productLine) {
            this.productLine = productLine;
        }

        public String getGroupId() {
            return productTopics().consumerGroup("matching-market-data");
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = required(clientId, "Kafka client id");
        }

        public String getCoreEventsTopic() {
            if (coreEventsTopic != null && !coreEventsTopic.isBlank()) {
                return coreEventsTopic.trim();
            }
            if (productLine == null) {
                throw new IllegalStateException("product line is required for Core events topic");
            }
            return "surprising." + productLine.topicSegment() + ".core.events.v1";
        }

        public void setCoreEventsTopic(String coreEventsTopic) {
            this.coreEventsTopic = coreEventsTopic;
        }

        public String getMatchTradesTopic() {
            return productTopics().matchTradesTopic();
        }

        public String getOrderBookDepthTopic() {
            return productTopics().orderBookDepthTopic();
        }

        public int getMaxPollRecords() {
            return maxPollRecords;
        }

        public void setMaxPollRecords(int maxPollRecords) {
            if (maxPollRecords <= 0) {
                throw new IllegalArgumentException("max poll records must be positive");
            }
            this.maxPollRecords = maxPollRecords;
        }

        private ProductTopicNames productTopics() {
            return ProductTopicNames.of(productLine);
        }

        private static String required(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " is required");
            }
            return value.trim();
        }
    }

    public static class MarketData {
        private boolean enabled = true;
        private int depthLevels = 30;
        private int batchSize = 512;
        private int maxInFlight = 256;
        private long publishDelayMs = 5;
        private long maxBlockMs = 5;
        private int deliveryTimeoutMs = 500;
        private int requestTimeoutMs = 300;
        private int lingerMs = 10;
        private int producerBatchSize = 65_536;
        private long bufferMemoryBytes = 33_554_432;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getDepthLevels() { return depthLevels; }
        public void setDepthLevels(int value) {
            depthLevels = positive(value, "depth levels");
            if (depthLevels > 100) throw new IllegalArgumentException("depth levels must not exceed 100");
        }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int value) { batchSize = positive(value, "batch size"); }
        public int getMaxInFlight() { return maxInFlight; }
        public void setMaxInFlight(int value) { maxInFlight = positive(value, "max in flight"); }
        public long getPublishDelayMs() { return publishDelayMs; }
        public void setPublishDelayMs(long value) { publishDelayMs = positive(value, "publish delay"); }
        public long getMaxBlockMs() { return maxBlockMs; }
        public void setMaxBlockMs(long value) { maxBlockMs = nonNegative(value, "max block"); }
        public int getDeliveryTimeoutMs() { return deliveryTimeoutMs; }
        public void setDeliveryTimeoutMs(int value) { deliveryTimeoutMs = positive(value, "delivery timeout"); }
        public int getRequestTimeoutMs() { return requestTimeoutMs; }
        public void setRequestTimeoutMs(int value) { requestTimeoutMs = positive(value, "request timeout"); }
        public int getLingerMs() { return lingerMs; }
        public void setLingerMs(int value) { lingerMs = Math.toIntExact(nonNegative(value, "linger")); }
        public int getProducerBatchSize() { return producerBatchSize; }
        public void setProducerBatchSize(int value) { producerBatchSize = positive(value, "producer batch size"); }
        public long getBufferMemoryBytes() { return bufferMemoryBytes; }
        public void setBufferMemoryBytes(long value) { bufferMemoryBytes = positive(value, "buffer memory"); }

        private static int positive(int value, String name) {
            if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
            return value;
        }

        private static long positive(long value, String name) {
            if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
            return value;
        }

        private static long nonNegative(long value, String name) {
            if (value < 0) throw new IllegalArgumentException(name + " must not be negative");
            return value;
        }
    }

    public static class Aeron {
        private List<String> hostnames = List.of("localhost", "localhost", "localhost");
        private String egressHostname = "localhost";
        private Duration responseTimeout = Duration.ofSeconds(5);
        private Duration bootstrapTimeout = Duration.ofSeconds(45);

        public List<String> getHostnames() { return hostnames; }
        public void setHostnames(List<String> hostnames) {
            if (hostnames == null || hostnames.size() != 3
                    || hostnames.stream().anyMatch(value -> value == null || value.isBlank())) {
                throw new IllegalArgumentException("Aeron hostnames must contain three members");
            }
            this.hostnames = List.copyOf(hostnames);
        }
        public String getEgressHostname() { return egressHostname; }
        public void setEgressHostname(String value) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException("Aeron egress host is required");
            egressHostname = value.trim();
        }
        public Duration getResponseTimeout() { return responseTimeout; }
        public void setResponseTimeout(Duration value) {
            if (value == null || value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException("Aeron response timeout must be positive");
            }
            responseTimeout = value;
        }
        public Duration getBootstrapTimeout() { return bootstrapTimeout; }
        public void setBootstrapTimeout(Duration value) {
            if (value == null || value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException("Aeron bootstrap timeout must be positive");
            }
            bootstrapTimeout = value;
        }
    }
}
