package com.surprising.price.mark.config;

import lombok.Getter;
import lombok.Setter;

import com.surprising.product.api.ProductLine;
import com.surprising.product.api.ProductTopicNames;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Validated
@ConfigurationProperties(prefix = "surprising.price.mark")
public class MarkPriceProperties {

    @Setter
    private Kafka kafka = new Kafka();
    @Setter
    private Calculation calculation = new Calculation();
    @Setter
    private Coordination coordination = new Coordination();
    @Setter
    private Audit audit = new Audit();
    private Aeron aeron = new Aeron();

    public void setAeron(Aeron aeron) {
        this.aeron = aeron == null ? new Aeron() : aeron;
    }

    @Getter
    public static class Aeron {
        private List<String> hostnames = List.of("localhost", "localhost", "localhost");
        private String egressHostname = "localhost";
        private Duration responseTimeout = Duration.ofSeconds(5);
        private int clientConnections = 1;

        public void setHostnames(List<String> hostnames) {
            if (hostnames == null || (hostnames.size() != 1 && hostnames.size() != 3)
                    || hostnames.stream().anyMatch(value -> value == null || value.isBlank())) {
                throw new IllegalArgumentException("aeron hostnames must contain one or three non-blank members");
            }
            this.hostnames = List.copyOf(hostnames);
        }
        public void setEgressHostname(String egressHostname) {
            if (egressHostname == null || egressHostname.isBlank()) {
                throw new IllegalArgumentException("aeron egress hostname is required");
            }
            this.egressHostname = egressHostname.trim();
        }
        public void setResponseTimeout(Duration responseTimeout) {
            if (responseTimeout == null || responseTimeout.isZero() || responseTimeout.isNegative()) {
                throw new IllegalArgumentException("aeron response timeout must be positive");
            }
            this.responseTimeout = responseTimeout;
        }
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

    @Getter
    public static class Kafka {
        @Setter
        private String bootstrapServers = "localhost:9092";
        private ProductLine productLine = ProductLine.LINEAR_PERPETUAL;
        @Setter
        private int concurrency = 2;
        @Setter
        private int maxPollRecords = 500;

        public void setProductLine(ProductLine productLine) {
            this.productLine = productLine == null ? ProductLine.LINEAR_PERPETUAL : productLine;
        }

        public String getGroupId() {
            return ProductTopicNames.of(productLine).consumerGroup("mark-price");
        }

        public String getInstrumentSnapshotGroupId() {
            return "surprising-" + productLine.topicSegment() + "-mark-instrument-snapshot-v1";
        }

    }

    @Getter
    @Setter
    public static class Calculation {
        @Min(100)
        @Max(1000)
        private long publishIntervalMs = 1000L;
        private Duration basisWindow = Duration.ofSeconds(60);
        private Duration maxInputAge = Duration.ofSeconds(5);
        private BigDecimal clampRatio = new BigDecimal("0.03");
        private int defaultFundingIntervalHours = 8;
        private int scale = 18;

    }

    @Getter
    @Setter
    public static class Coordination {
        private boolean enabled = true;
        private String nodeId;
        private Duration leaseDuration = Duration.ofSeconds(15);

    }

    @Getter
    @Setter
    public static class Audit {
        private Duration retention = Duration.ofDays(3);
        private long cleanupDelayMs = Duration.ofMinutes(1).toMillis();
        private int cleanupBatchSize = 10_000;
        private int maxBatchesPerRun = 10;

    }
}
