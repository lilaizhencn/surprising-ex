package com.surprising.funding.provider.config;

import com.surprising.product.api.ProductLine;
import com.surprising.product.api.ProductTopicNames;
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

    public static class Kafka {
        private String bootstrapServers = "localhost:9092";
        private ProductLine productLine = ProductLine.LINEAR_PERPETUAL;
        private boolean productTopicsEnabled;
        private String fundingRateTopic = "surprising.perp.funding.rate.v1";
        private String cacheGroupId = "surprising-funding-rate-cache-local";
        private int concurrency = 1;
        private int commandResultsConcurrency = 4;
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

        public String getUserCommandsTopic() {
            return ProductTopicNames.of(productLine).accountUserCommandsTopic();
        }

        public String getCommandResultsTopic() {
            return ProductTopicNames.of(productLine).accountCommandResultsTopic();
        }

        public String getCacheGroupId() {
            return cacheGroupId;
        }

        public void setCacheGroupId(String cacheGroupId) {
            this.cacheGroupId = cacheGroupId;
        }

        public String getCommandResultsGroupId() {
            return ProductTopicNames.of(productLine).consumerGroup("funding-account-results");
        }

        public int getConcurrency() {
            return concurrency;
        }

        public void setConcurrency(int concurrency) {
            this.concurrency = concurrency;
        }

        public int getCommandResultsConcurrency() {
            return commandResultsConcurrency;
        }

        public void setCommandResultsConcurrency(int commandResultsConcurrency) {
            this.commandResultsConcurrency = commandResultsConcurrency;
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
        @Min(1)
        @Max(10_000)
        private int paymentPageSize = 500;
        @Min(1)
        @Max(1_000)
        private int maxPagesPerRun = 20;
        @Min(1)
        @Max(10_000)
        private int reconcileBatchSize = 500;

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

        public int getPaymentPageSize() {
            return paymentPageSize;
        }

        public void setPaymentPageSize(int paymentPageSize) {
            this.paymentPageSize = paymentPageSize;
        }

        public int getMaxPagesPerRun() {
            return maxPagesPerRun;
        }

        public void setMaxPagesPerRun(int maxPagesPerRun) {
            this.maxPagesPerRun = maxPagesPerRun;
        }

        public int getReconcileBatchSize() {
            return reconcileBatchSize;
        }

        public void setReconcileBatchSize(int reconcileBatchSize) {
            this.reconcileBatchSize = reconcileBatchSize;
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
