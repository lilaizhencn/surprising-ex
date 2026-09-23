package com.surprising.insurance.provider.config;

import lombok.Getter;
import lombok.Setter;

import com.surprising.product.api.ProductLine;
import com.surprising.product.api.ProductTopicNames;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@ConfigurationProperties(prefix = "surprising.insurance")
public class InsuranceProperties {

    private Kafka kafka = new Kafka();
    @Setter
    private Coverage coverage = new Coverage();
    private Aeron aeron = new Aeron();

    public void setKafka(Kafka kafka) {
        this.kafka = kafka == null ? new Kafka() : kafka;
    }

    public void setAeron(Aeron aeron) { this.aeron = aeron == null ? new Aeron() : aeron; }

    @Getter
    public static class Aeron {
        private java.util.List<String> hostnames = java.util.List.of("localhost", "localhost", "localhost");
        private String egressHostname = "localhost";
        private Duration responseTimeout = Duration.ofSeconds(5);
        private int clientConnections = 2;
        public void setHostnames(java.util.List<String> hostnames) {
            if (hostnames == null || (hostnames.size() != 1 && hostnames.size() != 3)
                    || hostnames.stream().anyMatch(value -> value == null || value.isBlank())) {
                throw new IllegalArgumentException("aeron hostnames must contain one or three non-blank members");
            }
            this.hostnames = java.util.List.copyOf(hostnames);
        }
        public void setEgressHostname(String value) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException("egress hostname is required");
            egressHostname = value.trim();
        }
        public void setResponseTimeout(Duration value) {
            if (value == null || value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException("response timeout must be positive");
            }
            responseTimeout = value;
        }
        public void setClientConnections(int value) {
            if (value < 1 || value > 64) throw new IllegalArgumentException("client connections must be in [1,64]");
            clientConnections = value;
        }
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
            return productTopics().consumerGroup("insurance");
        }


        public String getLiquidationFeeEventsTopic() {
            return productTopics().accountLiquidationFeeEventsTopic();
        }


        public String getInstrumentSnapshotGroupId() {
            return "surprising-" + productLine.topicSegment() + "-insurance-instrument-snapshot-v1";
        }

        public String getUserCommandsTopic() {
            return productTopics().accountUserCommandsTopic();
        }

        public String getAccountType() {
            return productLine.accountTypeCode();
        }

        private ProductTopicNames productTopics() {
            return ProductTopicNames.of(productLine);
        }
    }

    @Getter
    @Setter
    public static class Coverage {
        private boolean enabled = true;
        private long scanDelayMs = 1000L;
        private int batchSize = 100;

    }
}
