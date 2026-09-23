package com.surprising.websocket.provider.config;

import lombok.Getter;
import lombok.Setter;

import com.surprising.product.api.ProductLine;
import com.surprising.product.api.ProductTopicNames;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "surprising.websocket")
public class WebSocketProperties {

    private Kafka kafka = new Kafka();
    private Session session = new Session();
    private Security security = new Security();
    private Fanout fanout = new Fanout();

    public static class Kafka {
        @Getter
        @Setter
        private String bootstrapServers = "localhost:9092";
        @Getter
        private ProductLine productLine;
        @Setter
        private String groupId = "surprising-websocket-local";
        @Getter
        @Setter
        private int concurrency = 2;
        @Getter
        @Setter
        private int maxPollRecords = 1000;

        public void setProductLine(ProductLine productLine) {
            if (productLine == null) {
                throw new IllegalArgumentException("websocket product line is required");
            }
            this.productLine = productLine;
        }

        public String getGroupId() {
            return productTopics().consumerGroup("websocket") + "-" + groupId;
        }

        public String getCandleTopic() {
            return productTopics().candleEventsTopic();
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

    @Getter
    @Setter
    public static class Session {
        private int maxSubscriptions = 200;
        private int outboundQueueCapacity = 1000;
        private Duration sendTimeout = Duration.ofSeconds(5);

    }

    @Getter
    @Setter
    public static class Security {
        private String userIdHeader = "X-User-Id";
        private List<String> allowedOrigins = new ArrayList<>(List.of("*"));
        private String issuer = "surprising-ex-gateway";
        private String jwtSecret = "local-dev-change-me-surprising-ex-gateway-secret-2026";

    }

    @Getter
    @Setter
    public static class Fanout {
        private Duration candlePartialCoalesceWindow = Duration.ofMillis(250);
        private Duration markPriceMaxAge = Duration.ofSeconds(3);
        private Duration markPriceAllowedFutureSkew = Duration.ofSeconds(1);

    }
}
