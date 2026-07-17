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
        private ProductLine productLine = ProductLine.LINEAR_PERPETUAL;
        private boolean productTopicsEnabled;
        private String groupId = "surprising-websocket-local";
        private int concurrency = 2;
        private int maxPollRecords = 1000;
        private String candleTopic = "surprising.perp.candle.events.v1";
        private String tradeTopic = "surprising.perp.trade.events.v1";
        private String orderBookDepthTopic = "surprising.perp.orderbook.depth.v1";
        private String indexPriceTopic = "surprising.perp.index.price.v1";
        private String markPriceTopic = "surprising.perp.mark.price.v1";
        private String fundingRateTopic = "surprising.perp.funding.rate.v1";
        private String orderEventsTopic = "surprising.perp.order.events.v1";
        private String triggerOrderEventsTopic = "surprising.perp.trigger-order.events.v1";
        private String matchResultsTopic = "surprising.perp.match.results.v1";
        private String matchTradesTopic = "surprising.perp.match.trades.v1";
        private String positionEventsTopic = "surprising.account.position.events.v1";
        private String accountRiskEventsTopic = "surprising.risk.account.events.v1";
        private String positionRiskEventsTopic = "surprising.risk.position.events.v1";

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
            return productTopicsEnabled ? productTopics().consumerGroup("websocket") + "-" + groupId : groupId;
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

        public String getCandleTopic() {
            return productTopicsEnabled ? productTopics().candleEventsTopic() : candleTopic;
        }

        public void setCandleTopic(String candleTopic) {
            this.candleTopic = candleTopic;
        }

        public String getTradeTopic() {
            return productTopicsEnabled ? productTopics().publicTradesTopic() : tradeTopic;
        }

        public void setTradeTopic(String tradeTopic) {
            this.tradeTopic = tradeTopic;
        }

        public String getOrderBookDepthTopic() {
            return productTopicsEnabled ? productTopics().orderBookDepthTopic() : orderBookDepthTopic;
        }

        public void setOrderBookDepthTopic(String orderBookDepthTopic) {
            this.orderBookDepthTopic = orderBookDepthTopic;
        }

        public String getIndexPriceTopic() {
            return productTopicsEnabled ? productTopics().indexPriceTopic() : indexPriceTopic;
        }

        public void setIndexPriceTopic(String indexPriceTopic) {
            this.indexPriceTopic = indexPriceTopic;
        }

        public String getMarkPriceTopic() {
            return productTopicsEnabled ? productTopics().markPriceTopic() : markPriceTopic;
        }

        public void setMarkPriceTopic(String markPriceTopic) {
            this.markPriceTopic = markPriceTopic;
        }

        public String getFundingRateTopic() {
            return isFundingRateTopicEnabled() && productTopicsEnabled
                    ? productTopics().fundingRateTopic()
                    : fundingRateTopic;
        }

        public void setFundingRateTopic(String fundingRateTopic) {
            this.fundingRateTopic = fundingRateTopic;
        }

        public boolean isFundingRateTopicEnabled() {
            return !productTopicsEnabled || productLine.isFundingProduct();
        }

        public String getOrderEventsTopic() {
            return productTopicsEnabled ? productTopics().orderEventsTopic() : orderEventsTopic;
        }

        public void setOrderEventsTopic(String orderEventsTopic) {
            this.orderEventsTopic = orderEventsTopic;
        }

        public String getTriggerOrderEventsTopic() {
            return productTopicsEnabled ? productTopics().triggerOrderEventsTopic() : triggerOrderEventsTopic;
        }

        public void setTriggerOrderEventsTopic(String triggerOrderEventsTopic) {
            this.triggerOrderEventsTopic = triggerOrderEventsTopic;
        }

        public String getMatchResultsTopic() {
            return productTopicsEnabled ? productTopics().matchResultsTopic() : matchResultsTopic;
        }

        public void setMatchResultsTopic(String matchResultsTopic) {
            this.matchResultsTopic = matchResultsTopic;
        }

        public String getMatchTradesTopic() {
            return productTopicsEnabled ? productTopics().matchTradesTopic() : matchTradesTopic;
        }

        public void setMatchTradesTopic(String matchTradesTopic) {
            this.matchTradesTopic = matchTradesTopic;
        }

        public String getPositionEventsTopic() {
            return productTopicsEnabled ? productTopics().accountPositionEventsTopic() : positionEventsTopic;
        }

        public void setPositionEventsTopic(String positionEventsTopic) {
            this.positionEventsTopic = positionEventsTopic;
        }

        public String getAccountRiskEventsTopic() {
            return productTopicsEnabled ? productTopics().accountRiskEventsTopic() : accountRiskEventsTopic;
        }

        public void setAccountRiskEventsTopic(String accountRiskEventsTopic) {
            this.accountRiskEventsTopic = accountRiskEventsTopic;
        }

        public String getPositionRiskEventsTopic() {
            return productTopicsEnabled ? productTopics().positionRiskEventsTopic() : positionRiskEventsTopic;
        }

        public void setPositionRiskEventsTopic(String positionRiskEventsTopic) {
            this.positionRiskEventsTopic = positionRiskEventsTopic;
        }

        private ProductTopicNames productTopics() {
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
        private boolean allowQueryUserIdFallback = true;
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

        public boolean isAllowQueryUserIdFallback() {
            return allowQueryUserIdFallback;
        }

        public void setAllowQueryUserIdFallback(boolean allowQueryUserIdFallback) {
            this.allowQueryUserIdFallback = allowQueryUserIdFallback;
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
