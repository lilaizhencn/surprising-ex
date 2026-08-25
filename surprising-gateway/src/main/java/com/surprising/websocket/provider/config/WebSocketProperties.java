package com.surprising.websocket.provider.config;

import com.surprising.product.api.ProductLine;
import com.surprising.product.api.ProductTopicNames;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "surprising.websocket")
public class WebSocketProperties {

    private Kafka kafka = new Kafka();
    private Session session = new Session();
    private Security security = new Security();
    private Fanout fanout = new Fanout();

    public Kafka getKafka() {
        return kafka;
    }

    public void setKafka(Kafka kafka) {
        this.kafka = kafka;
    }

    public Session getSession() {
        return session;
    }

    public void setSession(Session session) {
        this.session = session;
    }

    public Security getSecurity() {
        return security;
    }

    public void setSecurity(Security security) {
        this.security = security;
    }

    public Fanout getFanout() {
        return fanout;
    }

    public void setFanout(Fanout fanout) {
        this.fanout = fanout;
    }

    public static class Kafka {
        private String bootstrapServers = "localhost:9092";
        private ProductLine productLine;
        private String groupId = "surprising-websocket-local";
        private int concurrency = 2;
        private int maxPollRecords = 1000;

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
            if (productLine == null) {
                throw new IllegalArgumentException("websocket product line is required");
            }
            this.productLine = productLine;
        }

        public String getGroupId() {
            return productTopics().consumerGroup("websocket") + "-" + groupId;
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

        public String getCoreEventsTopic() {
            return productTopics().coreEventsTopic();
        }

        public String getCandleTopic() {
            return productTopics().candleEventsTopic();
        }

        public String getOrderBookDepthTopic() {
            return productTopics().orderBookDepthTopic();
        }

        public String getPriceEventsTopic() {
            return productTopics().priceEventsTopic();
        }

        public String getFundingRateTopic() {
            return productTopics().fundingRateTopic();
        }

        public boolean isFundingRateTopicEnabled() {
            return productLine.isFundingProduct();
        }

        public String getOrderEventsTopic() {
            return productTopics().orderEventsTopic();
        }

        public String getTriggerOrderEventsTopic() {
            return productTopics().triggerOrderEventsTopic();
        }

        public String getMatchTradesTopic() {
            return productTopics().matchTradesTopic();
        }

        public String getPositionEventsTopic() {
            return productTopics().accountPositionEventsTopic();
        }

        public String getAccountRiskEventsTopic() {
            return productTopics().accountRiskEventsTopic();
        }

        public String getPositionRiskEventsTopic() {
            return productTopics().positionRiskEventsTopic();
        }

        private ProductTopicNames productTopics() {
            if (productLine == null) {
                throw new IllegalStateException("websocket product line is required");
            }
            return ProductTopicNames.of(productLine);
        }
    }

    public static class Session {
        private int maxSubscriptions = 200;
        private int outboundQueueCapacity = 1000;
        private Duration sendTimeout = Duration.ofSeconds(5);

        public int getMaxSubscriptions() {
            return maxSubscriptions;
        }

        public void setMaxSubscriptions(int maxSubscriptions) {
            this.maxSubscriptions = maxSubscriptions;
        }

        public int getOutboundQueueCapacity() {
            return outboundQueueCapacity;
        }

        public void setOutboundQueueCapacity(int outboundQueueCapacity) {
            this.outboundQueueCapacity = outboundQueueCapacity;
        }

        public Duration getSendTimeout() {
            return sendTimeout;
        }

        public void setSendTimeout(Duration sendTimeout) {
            this.sendTimeout = sendTimeout;
        }
    }

    public static class Security {
        private String userIdHeader = "X-User-Id";
        private List<String> allowedOrigins = new ArrayList<>(List.of("*"));
        private String issuer = "surprising-ex-gateway";
        private String jwtSecret = "local-dev-change-me-surprising-ex-gateway-secret-2026";

        public String getUserIdHeader() {
            return userIdHeader;
        }

        public void setUserIdHeader(String userIdHeader) {
            this.userIdHeader = userIdHeader;
        }

        public List<String> getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(List<String> allowedOrigins) {
            this.allowedOrigins = allowedOrigins;
        }

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public String getJwtSecret() {
            return jwtSecret;
        }

        public void setJwtSecret(String jwtSecret) {
            this.jwtSecret = jwtSecret;
        }
    }

    public static class Fanout {
        private Duration candlePartialCoalesceWindow = Duration.ofMillis(250);
        private Duration markPriceMaxAge = Duration.ofSeconds(3);
        private Duration markPriceAllowedFutureSkew = Duration.ofSeconds(1);

        public Duration getCandlePartialCoalesceWindow() {
            return candlePartialCoalesceWindow;
        }

        public void setCandlePartialCoalesceWindow(Duration candlePartialCoalesceWindow) {
            this.candlePartialCoalesceWindow = candlePartialCoalesceWindow;
        }

        public Duration getMarkPriceMaxAge() {
            return markPriceMaxAge;
        }

        public void setMarkPriceMaxAge(Duration markPriceMaxAge) {
            this.markPriceMaxAge = markPriceMaxAge;
        }

        public Duration getMarkPriceAllowedFutureSkew() {
            return markPriceAllowedFutureSkew;
        }

        public void setMarkPriceAllowedFutureSkew(Duration markPriceAllowedFutureSkew) {
            this.markPriceAllowedFutureSkew = markPriceAllowedFutureSkew;
        }
    }
}
