package com.surprising.price.mark.config;

import com.surprising.product.api.ProductLine;
import com.surprising.product.api.ProductTopicNames;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "surprising.price.mark")
public class MarkPriceProperties {

    private Kafka kafka = new Kafka();
    private Topics topics = new Topics();
    private Calculation calculation = new Calculation();
    private Coordination coordination = new Coordination();
    private Audit audit = new Audit();
    private Aeron aeron = new Aeron();

    public Kafka getKafka() {
        return kafka;
    }

    public void setKafka(Kafka kafka) {
        this.kafka = kafka;
    }

    public Topics getTopics() {
        return topics;
    }

    public void setTopics(Topics topics) {
        this.topics = topics;
    }

    public Calculation getCalculation() {
        return calculation;
    }

    public void setCalculation(Calculation calculation) {
        this.calculation = calculation;
    }

    public Coordination getCoordination() {
        return coordination;
    }

    public void setCoordination(Coordination coordination) {
        this.coordination = coordination;
    }

    public Audit getAudit() {
        return audit;
    }

    public void setAudit(Audit audit) {
        this.audit = audit;
    }

    public Aeron getAeron() {
        return aeron;
    }

    public void setAeron(Aeron aeron) {
        this.aeron = aeron == null ? new Aeron() : aeron;
    }

    public static class Aeron {
        private List<String> hostnames = List.of("localhost", "localhost", "localhost");
        private String egressHostname = "localhost";
        private Duration responseTimeout = Duration.ofSeconds(5);
        private int clientConnections = 1;

        public List<String> getHostnames() { return hostnames; }
        public void setHostnames(List<String> hostnames) {
            if (hostnames == null || hostnames.size() != 3
                    || hostnames.stream().anyMatch(value -> value == null || value.isBlank())) {
                throw new IllegalArgumentException("aeron hostnames must contain three non-blank members");
            }
            this.hostnames = List.copyOf(hostnames);
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
            if (clientConnections != 1) throw new IllegalArgumentException("price Aeron requires one client connection");
            this.clientConnections = clientConnections;
        }
    }

    public String bookTickerTopic() {
        return productTopics().bookTickerTopic();
    }

    public String matchTradesTopic() {
        return productTopics().matchTradesTopic();
    }

    public String fundingRateTopic() {
        return productTopics().fundingRateTopic();
    }

    public boolean isFundingRateExpected() {
        return kafka.productLine.isFundingProduct();
    }

    public String priceEventsTopic() {
        return productTopics().priceEventsTopic();
    }

    private ProductTopicNames productTopics() {
        return ProductTopicNames.of(kafka.productLine);
    }

    public static class Kafka {
        private String bootstrapServers = "localhost:9092";
        private ProductLine productLine = ProductLine.LINEAR_PERPETUAL;
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

        public String getGroupId() {
            return ProductTopicNames.of(productLine).consumerGroup("mark-price");
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

        public String getInstrumentSnapshotGroupId() {
            return "surprising-" + productLine.topicSegment() + "-mark-instrument-snapshot-v1";
        }

        public void setMaxPollRecords(int maxPollRecords) {
            this.maxPollRecords = maxPollRecords;
        }
    }

    public static class Topics {
        private String bookTickerTopic = "surprising.perp.book.ticker.v1";
        private String fundingRateTopic = "surprising.perp.funding.rate.v1";
        private String priceEventsTopic = "surprising.perp.price.events.v1";

        public String getBookTickerTopic() {
            return bookTickerTopic;
        }

        public void setBookTickerTopic(String bookTickerTopic) {
            this.bookTickerTopic = bookTickerTopic;
        }

        public String getFundingRateTopic() {
            return fundingRateTopic;
        }

        public void setFundingRateTopic(String fundingRateTopic) {
            this.fundingRateTopic = fundingRateTopic;
        }

        public String getPriceEventsTopic() {
            return priceEventsTopic;
        }

        public void setPriceEventsTopic(String priceEventsTopic) {
            this.priceEventsTopic = priceEventsTopic;
        }

    }

    public static class Calculation {
        @Min(100)
        @Max(1000)
        private long publishIntervalMs = 1000L;
        private Duration basisWindow = Duration.ofSeconds(60);
        private Duration maxInputAge = Duration.ofSeconds(5);
        private BigDecimal clampRatio = new BigDecimal("0.03");
        private int defaultFundingIntervalHours = 8;
        private int scale = 18;

        public long getPublishIntervalMs() {
            return publishIntervalMs;
        }

        public void setPublishIntervalMs(long publishIntervalMs) {
            this.publishIntervalMs = publishIntervalMs;
        }

        public Duration getBasisWindow() {
            return basisWindow;
        }

        public void setBasisWindow(Duration basisWindow) {
            this.basisWindow = basisWindow;
        }

        public Duration getMaxInputAge() {
            return maxInputAge;
        }

        public void setMaxInputAge(Duration maxInputAge) {
            this.maxInputAge = maxInputAge;
        }

        public BigDecimal getClampRatio() {
            return clampRatio;
        }

        public void setClampRatio(BigDecimal clampRatio) {
            this.clampRatio = clampRatio;
        }

        public int getDefaultFundingIntervalHours() {
            return defaultFundingIntervalHours;
        }

        public void setDefaultFundingIntervalHours(int defaultFundingIntervalHours) {
            this.defaultFundingIntervalHours = defaultFundingIntervalHours;
        }

        public int getScale() {
            return scale;
        }

        public void setScale(int scale) {
            this.scale = scale;
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

    public static class Audit {
        private Duration retention = Duration.ofDays(3);
        private long cleanupDelayMs = Duration.ofMinutes(1).toMillis();
        private int cleanupBatchSize = 10_000;
        private int maxBatchesPerRun = 10;

        public Duration getRetention() {
            return retention;
        }

        public void setRetention(Duration retention) {
            this.retention = retention;
        }

        public long getCleanupDelayMs() {
            return cleanupDelayMs;
        }

        public void setCleanupDelayMs(long cleanupDelayMs) {
            this.cleanupDelayMs = cleanupDelayMs;
        }

        public int getCleanupBatchSize() {
            return cleanupBatchSize;
        }

        public void setCleanupBatchSize(int cleanupBatchSize) {
            this.cleanupBatchSize = cleanupBatchSize;
        }

        public int getMaxBatchesPerRun() {
            return maxBatchesPerRun;
        }

        public void setMaxBatchesPerRun(int maxBatchesPerRun) {
            this.maxBatchesPerRun = maxBatchesPerRun;
        }
    }
}
