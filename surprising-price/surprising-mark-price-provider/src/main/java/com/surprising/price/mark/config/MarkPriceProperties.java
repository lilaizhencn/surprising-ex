package com.surprising.price.mark.config;

import com.surprising.product.api.ProductLine;
import com.surprising.product.api.ProductTopicNames;
import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "surprising.price.mark")
public class MarkPriceProperties {

    private Kafka kafka = new Kafka();
    private Topics topics = new Topics();
    private Calculation calculation = new Calculation();
    private Coordination coordination = new Coordination();

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

    public String indexPriceTopic() {
        return kafka.productTopicsEnabled ? productTopics().indexPriceTopic() : topics.getIndexPriceTopic();
    }

    public String bookTickerTopic() {
        return kafka.productTopicsEnabled ? productTopics().bookTickerTopic() : topics.getBookTickerTopic();
    }

    public String tradeTopic() {
        return kafka.productTopicsEnabled ? productTopics().publicTradesTopic() : topics.getTradeTopic();
    }

    public String fundingRateTopic() {
        return isFundingRateExpected() && kafka.productTopicsEnabled
                ? productTopics().fundingRateTopic()
                : topics.getFundingRateTopic();
    }

    public boolean isFundingRateExpected() {
        return !kafka.productTopicsEnabled || kafka.productLine.isFundingProduct();
    }

    public String markPriceTopic() {
        return kafka.productTopicsEnabled ? productTopics().markPriceTopic() : topics.getMarkPriceTopic();
    }

    public String markPriceAuditTopic() {
        return kafka.productTopicsEnabled ? productTopics().markPriceAuditTopic() : topics.getMarkPriceAuditTopic();
    }

    private ProductTopicNames productTopics() {
        return ProductTopicNames.of(kafka.productLine);
    }

    public static class Kafka {
        private String bootstrapServers = "localhost:9092";
        private ProductLine productLine = ProductLine.LINEAR_PERPETUAL;
        private boolean productTopicsEnabled;
        private String groupId = "surprising-mark-price-v1";
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
            return productTopicsEnabled ? ProductTopicNames.of(productLine).consumerGroup("mark-price") : groupId;
        }

        public void setGroupId(String groupId) {
            this.groupId = groupId;
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

    public static class Topics {
        private String indexPriceTopic = "surprising.perp.index.price.v1";
        private String bookTickerTopic = "surprising.perp.book.ticker.v1";
        private String tradeTopic = "surprising.perp.trade.events.v1";
        private String fundingRateTopic = "surprising.perp.funding.rate.v1";
        private String markPriceTopic = "surprising.perp.mark.price.v1";
        private String markPriceAuditTopic = "surprising.perp.mark.price.audit.v1";

        public String getIndexPriceTopic() {
            return indexPriceTopic;
        }

        public void setIndexPriceTopic(String indexPriceTopic) {
            this.indexPriceTopic = indexPriceTopic;
        }

        public String getBookTickerTopic() {
            return bookTickerTopic;
        }

        public void setBookTickerTopic(String bookTickerTopic) {
            this.bookTickerTopic = bookTickerTopic;
        }

        public String getTradeTopic() {
            return tradeTopic;
        }

        public void setTradeTopic(String tradeTopic) {
            this.tradeTopic = tradeTopic;
        }

        public String getFundingRateTopic() {
            return fundingRateTopic;
        }

        public void setFundingRateTopic(String fundingRateTopic) {
            this.fundingRateTopic = fundingRateTopic;
        }

        public String getMarkPriceTopic() {
            return markPriceTopic;
        }

        public void setMarkPriceTopic(String markPriceTopic) {
            this.markPriceTopic = markPriceTopic;
        }

        public String getMarkPriceAuditTopic() {
            return markPriceAuditTopic;
        }

        public void setMarkPriceAuditTopic(String markPriceAuditTopic) {
            this.markPriceAuditTopic = markPriceAuditTopic;
        }
    }

    public static class Calculation {
        private long publishDelayMs = 1000L;
        private Duration basisWindow = Duration.ofSeconds(60);
        private Duration maxInputAge = Duration.ofSeconds(5);
        private BigDecimal clampRatio = new BigDecimal("0.03");
        private int defaultFundingIntervalHours = 8;
        private int scale = 18;

        public long getPublishDelayMs() {
            return publishDelayMs;
        }

        public void setPublishDelayMs(long publishDelayMs) {
            this.publishDelayMs = publishDelayMs;
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
}
