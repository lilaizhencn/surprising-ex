package com.surprising.trading.order.config;

import lombok.Getter;
import lombok.Setter;

import com.surprising.product.api.ProductLine;
import com.surprising.product.api.ProductTopicNames;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import jakarta.annotation.PostConstruct;

@Getter
@ConfigurationProperties(prefix = "surprising.trading.order")
public class TradingOrderProperties {

    @Setter
    private Kafka kafka = new Kafka();
    private EventPublish eventPublish = new EventPublish();
    @Setter
    private Risk risk = new Risk();
    @Setter
    private Algo algo = new Algo();
    private Aeron aeron = new Aeron();

    public void setEventPublish(EventPublish eventPublish) {
        this.eventPublish = eventPublish == null ? new EventPublish() : eventPublish;
    }

    public void setAeron(Aeron aeron) { this.aeron = aeron == null ? new Aeron() : aeron; }

    @Getter
    public static class Aeron {
        private java.util.List<String> hostnames = java.util.List.of("localhost", "localhost", "localhost");
        private String egressHostname = "localhost";
        private Duration responseTimeout = Duration.ofSeconds(5);
        private int clientConnections = 4;
        private int nodeId;

        public void setHostnames(java.util.List<String> hostnames) {
            if (hostnames == null || (hostnames.size() != 1 && hostnames.size() != 3)
                    || hostnames.stream().anyMatch(value -> value == null || value.isBlank())) {
                throw new IllegalArgumentException("aeron hostnames must contain one or three non-blank members");
            }
            this.hostnames = java.util.List.copyOf(hostnames);
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
            if (clientConnections < 1 || clientConnections > 64) {
                throw new IllegalArgumentException("aeron client connections must be in [1,64]");
            }
            this.clientConnections = clientConnections;
        }
        public void setNodeId(int nodeId) {
            if (nodeId < 0 || nodeId > 1023) throw new IllegalArgumentException("order nodeId must be in [0,1023]");
            this.nodeId = nodeId;
        }
    }

    @Getter
    public static class Kafka {
        @Setter
        private String bootstrapServers = "localhost:9092";
        /** 结果广播消费组的实例唯一标识；每个订单节点必须使用不同值。 */
        private String clientId = "trading-provider-" + java.util.UUID.randomUUID();
        /** 必须由部署配置显式指定，禁止缺省落到永续产品线。 */
        @Setter
        private ProductLine productLine;
        @Setter
        private String instrumentLifecycleDrainTopic = "surprising.instrument.lifecycle-drain.v1";

        public void setClientId(String clientId) {
            if (clientId == null || clientId.isBlank()) {
                throw new IllegalArgumentException("订单节点 clientId 不能为空");
            }
            this.clientId = clientId.trim();
        }

        public String getFeeScheduleEventsTopic() {
            return productTopics().feeScheduleEventsTopic();
        }
        public String getInstrumentLifecycleGroupId() {
            return productTopics().consumerGroup("order-instrument-lifecycle");
        }
        public String getInstrumentSnapshotGroupId() {
            return productTopics().consumerGroup("order-instrument-snapshot");
        }
        public String getFeeScheduleSnapshotGroupId() {
            return productTopics().consumerGroup("order-fee-snapshot");
        }
        private ProductTopicNames productTopics() {
            return ProductTopicNames.of(productLine);
        }
    }

    /** Kafka 通知发送配置，不承担订单或账户事实持久化。 */
    @Getter
    public static class EventPublish {
        private Duration sendTimeout = Duration.ofSeconds(3);

        public void setSendTimeout(Duration sendTimeout) {
            if (sendTimeout == null || sendTimeout.isZero() || sendTimeout.isNegative()) {
                throw new IllegalArgumentException("事件通知 sendTimeout 必须为正数");
            }
            this.sendTimeout = sendTimeout;
        }
    }

    @Getter
    @Setter
    public static class Risk {
        private long marketMaxSlippagePpm = 10_000L;
        private long marketMaxMarkAgeMs = 5_000L;
        private boolean limitPriceProtectionEnabled;
        private long limitPriceBandPpm = 50_000L;
        private long limitPriceMaxMarkAgeMs = 5_000L;

    }

    @Getter
    public static class Algo {
        @Setter
        private boolean enabled = true;
        @Setter
        private int claimBatchSize = 100;
        @Setter
        private long scanDelayMs = 250L;
        @Setter
        private long minIntervalSeconds = 1L;
        @Setter
        private long maxIntervalSeconds = 86_400L;
        @Setter
        private long minDurationSeconds = 5L;
        @Setter
        private long maxDurationSeconds = 86_400L;
        private Duration claimLease = Duration.ofSeconds(30);

        public void setClaimLease(Duration claimLease) {
            if (claimLease == null || claimLease.isZero() || claimLease.isNegative()) {
                throw new IllegalArgumentException("algo claim lease must be positive");
            }
            this.claimLease = claimLease;
        }
    }
}
