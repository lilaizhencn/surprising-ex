package com.surprising.trading.order.config;

import com.surprising.product.api.ProductLine;
import com.surprising.product.api.ProductLineConfiguration;
import com.surprising.product.api.ProductTopicNames;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import jakarta.annotation.PostConstruct;

@ConfigurationProperties(prefix = "surprising.trading.order")
public class TradingOrderProperties {

    private Kafka kafka = new Kafka();
    private EventPublish eventPublish = new EventPublish();
    private Risk risk = new Risk();
    private Algo algo = new Algo();
    private Aeron aeron = new Aeron();

    /** 启动时拒绝未隔离的订单 Topic 配置。 */
    @PostConstruct
    void validateProductLineConfiguration() {
        ProductLineConfiguration.require(kafka.productLine, kafka.productTopicsEnabled, "order");
    }

    public Kafka getKafka() {
        return kafka;
    }

    public void setKafka(Kafka kafka) {
        this.kafka = kafka;
    }

    public EventPublish getEventPublish() {
        return eventPublish;
    }

    public void setEventPublish(EventPublish eventPublish) {
        this.eventPublish = eventPublish == null ? new EventPublish() : eventPublish;
    }

    public Risk getRisk() {
        return risk;
    }

    public void setRisk(Risk risk) {
        this.risk = risk;
    }

    public Algo getAlgo() {
        return algo;
    }

    public void setAlgo(Algo algo) {
        this.algo = algo;
    }

    public Aeron getAeron() { return aeron; }

    public void setAeron(Aeron aeron) { this.aeron = aeron == null ? new Aeron() : aeron; }

    public static class Aeron {
        private java.util.List<String> hostnames = java.util.List.of("localhost", "localhost", "localhost");
        private String egressHostname = "localhost";
        private Duration responseTimeout = Duration.ofSeconds(5);
        private int clientConnections = 4;
        private int nodeId;

        public java.util.List<String> getHostnames() { return hostnames; }
        public void setHostnames(java.util.List<String> hostnames) {
            if (hostnames == null || hostnames.size() != 3
                    || hostnames.stream().anyMatch(value -> value == null || value.isBlank())) {
                throw new IllegalArgumentException("aeron hostnames must contain three non-blank members");
            }
            this.hostnames = java.util.List.copyOf(hostnames);
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
            if (clientConnections < 1 || clientConnections > 64) {
                throw new IllegalArgumentException("aeron client connections must be in [1,64]");
            }
            this.clientConnections = clientConnections;
        }
        public int getNodeId() { return nodeId; }
        public void setNodeId(int nodeId) {
            if (nodeId < 0 || nodeId > 1023) throw new IllegalArgumentException("order nodeId must be in [0,1023]");
            this.nodeId = nodeId;
        }
    }

    public static class Kafka {
        private String bootstrapServers = "localhost:9092";
        /** 结果广播消费组的实例唯一标识；每个订单节点必须使用不同值。 */
        private String clientId = "order-provider-" + java.util.UUID.randomUUID();
        /** 必须由部署配置显式指定，禁止缺省落到永续产品线。 */
        private ProductLine productLine;
        private boolean productTopicsEnabled;
        private String orderCommandsTopic = "surprising.perp.order.commands.v1";
        private String orderEventsTopic = "surprising.perp.order.events.v1";
        private String matchResultsTopic = "surprising.perp.match.results.v1";
        private String positionEventsTopic = "surprising.account.position.events.v1";
        private String accountStateEventsTopic = "surprising.account.state.events.v1";
        private String openInterestEventsTopic = "surprising.account.open-interest.events.v1";
        private String instrumentLifecycleDrainTopic = "surprising.instrument.lifecycle-drain.v1";
        private String feeScheduleEventsTopic = "surprising.perp.fee.schedule.events.v1";
        private String positionMaintenanceGroupId = "surprising-order-position-maintenance-v1";
        private String accountStateSnapshotGroupId = "surprising-order-account-state-v1";
        private int accountCommandResultsConcurrency = 32;
        private int userCommandConcurrency = 32;
        private Duration userCommandTimeout = Duration.ofSeconds(5);
        private Duration resultCacheTtl = Duration.ofMinutes(5);
        private int resultCacheMaxEntries = 10_000;

        public String getBootstrapServers() {
            return bootstrapServers;
        }

        public void setBootstrapServers(String bootstrapServers) {
            this.bootstrapServers = bootstrapServers;
        }
        public String getClientId() { return clientId; }
        public void setClientId(String clientId) {
            if (clientId == null || clientId.isBlank()) {
                throw new IllegalArgumentException("订单节点 clientId 不能为空");
            }
            this.clientId = clientId.trim();
        }

        public ProductLine getProductLine() {
            return productLine;
        }

        public void setProductLine(ProductLine productLine) {
            this.productLine = productLine;
        }

        public boolean isProductTopicsEnabled() {
            return productTopicsEnabled;
        }

        public void setProductTopicsEnabled(boolean productTopicsEnabled) {
            this.productTopicsEnabled = productTopicsEnabled;
        }

        public String getOrderCommandsTopic() {
            return productTopics().orderCommandsTopic();
        }

        public void setOrderCommandsTopic(String orderCommandsTopic) {
            this.orderCommandsTopic = orderCommandsTopic;
        }

        public String getOrderEventsTopic() {
            return productTopics().orderEventsTopic();
        }

        public void setOrderEventsTopic(String orderEventsTopic) {
            this.orderEventsTopic = orderEventsTopic;
        }
        public String getMatchResultsTopic() { return productTopics().matchResultsTopic(); }
        public void setMatchResultsTopic(String matchResultsTopic) { this.matchResultsTopic = matchResultsTopic; }
        public String getAccountUserCommandsTopic() {
            return productTopics().accountUserCommandsTopic();
        }
        public String getAccountCommandResultsTopic() {
            return productTopics().accountCommandResultsTopic();
        }
        public String getOrderUserCommandsTopic() {
            return productTopics().orderUserCommandsTopic();
        }
        public String getOrderUserCommandResultsTopic() {
            return productTopics().orderUserCommandResultsTopic();
        }
        public String getOrderStateEventsTopic() {
            return productTopics().orderStateEventsTopic();
        }
        public String getUserMutationsTopic() {
            return productTopics().userMutationsTopic();
        }
        public String getUserStateChangelogTopic() {
            return productTopics().userStateChangelogTopic();
        }
        public String getOrderUserCommandGroupId() {
            return productTopics().consumerGroup("order-user-state");
        }
        public String getOrderUserCommandResultsGroupId() {
            return productTopics().consumerGroup("order-user-command-results") + "-" + clientId;
        }
        public String getAccountCommandResultsGroupId() {
            return productTopics().consumerGroup("order-account-results");
        }
        public int getAccountCommandResultsConcurrency() { return accountCommandResultsConcurrency; }
        public void setAccountCommandResultsConcurrency(int accountCommandResultsConcurrency) {
            this.accountCommandResultsConcurrency = Math.max(1, accountCommandResultsConcurrency);
        }
        public int getUserCommandConcurrency() { return userCommandConcurrency; }
        public void setUserCommandConcurrency(int userCommandConcurrency) {
            this.userCommandConcurrency = Math.max(1, userCommandConcurrency);
        }
        public Duration getUserCommandTimeout() { return userCommandTimeout; }
        public void setUserCommandTimeout(Duration userCommandTimeout) {
            if (userCommandTimeout == null || userCommandTimeout.isZero() || userCommandTimeout.isNegative()) {
                throw new IllegalArgumentException("订单用户命令等待时间必须为正数");
            }
            this.userCommandTimeout = userCommandTimeout;
        }

        public Duration getResultCacheTtl() {
            return resultCacheTtl;
        }

        public void setResultCacheTtl(Duration resultCacheTtl) {
            if (resultCacheTtl == null || resultCacheTtl.isZero() || resultCacheTtl.isNegative()) {
                throw new IllegalArgumentException("订单结果缓存 TTL 必须为正数");
            }
            this.resultCacheTtl = resultCacheTtl;
        }

        public int getResultCacheMaxEntries() {
            return resultCacheMaxEntries;
        }

        public void setResultCacheMaxEntries(int resultCacheMaxEntries) {
            if (resultCacheMaxEntries <= 0) {
                throw new IllegalArgumentException("订单结果缓存容量必须为正数");
            }
            this.resultCacheMaxEntries = resultCacheMaxEntries;
        }
        public String getPositionEventsTopic() {
            return productTopics().accountPositionEventsTopic();
        }
        public void setPositionEventsTopic(String positionEventsTopic) {
            this.positionEventsTopic = positionEventsTopic;
        }

        public String getAccountStateEventsTopic() {
            return productTopics().accountStateEventsTopic();
        }

        public void setAccountStateEventsTopic(String accountStateEventsTopic) {
            this.accountStateEventsTopic = accountStateEventsTopic;
        }

        public String getOpenInterestEventsTopic() {
            return productTopics().accountOpenInterestEventsTopic();
        }

        public void setOpenInterestEventsTopic(String openInterestEventsTopic) {
            this.openInterestEventsTopic = openInterestEventsTopic;
        }
        public String getPositionMaintenanceGroupId() {
            return productTopics().consumerGroup("order-position-maintenance");
        }

        public String getOpenInterestSnapshotGroupId() {
            return productTopics().consumerGroup("order-open-interest-snapshot");
        }
        public void setPositionMaintenanceGroupId(String positionMaintenanceGroupId) {
            this.positionMaintenanceGroupId = positionMaintenanceGroupId;
        }

        public String getAccountStateSnapshotGroupId() {
            // 账户完整快照是每个订单 JVM 的本地读快照，必须广播到每个实例，不能被共享消费组分摊。
            return productTopics().consumerGroup("order-account-state") + "-" + clientId;
        }

        public String getOrderStateSnapshotGroupId() {
            // 订单完整快照是每个订单 JVM 的本地事实恢复输入，必须广播到每个实例。
            return productTopics().consumerGroup("order-state") + "-" + clientId;
        }
        public String getInstrumentLifecycleDrainTopic() { return instrumentLifecycleDrainTopic; }
        public void setInstrumentLifecycleDrainTopic(String instrumentLifecycleDrainTopic) {
            this.instrumentLifecycleDrainTopic = instrumentLifecycleDrainTopic;
        }
        public String getFeeScheduleEventsTopic() {
            return productTopics().feeScheduleEventsTopic();
        }
        public void setFeeScheduleEventsTopic(String feeScheduleEventsTopic) {
            this.feeScheduleEventsTopic = feeScheduleEventsTopic;
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
    public static class EventPublish {
        private Duration sendTimeout = Duration.ofSeconds(3);

        public Duration getSendTimeout() {
            return sendTimeout;
        }

        public void setSendTimeout(Duration sendTimeout) {
            if (sendTimeout == null || sendTimeout.isZero() || sendTimeout.isNegative()) {
                throw new IllegalArgumentException("事件通知 sendTimeout 必须为正数");
            }
            this.sendTimeout = sendTimeout;
        }
    }

    public static class Risk {
        private long marketMaxSlippagePpm = 10_000L;
        private long marketMaxMarkAgeMs = 5_000L;
        private boolean limitPriceProtectionEnabled;
        private long limitPriceBandPpm = 50_000L;
        private long limitPriceMaxMarkAgeMs = 5_000L;

        public long getMarketMaxSlippagePpm() {
            return marketMaxSlippagePpm;
        }

        public void setMarketMaxSlippagePpm(long marketMaxSlippagePpm) {
            this.marketMaxSlippagePpm = marketMaxSlippagePpm;
        }

        public long getMarketMaxMarkAgeMs() {
            return marketMaxMarkAgeMs;
        }

        public void setMarketMaxMarkAgeMs(long marketMaxMarkAgeMs) {
            this.marketMaxMarkAgeMs = marketMaxMarkAgeMs;
        }

        public boolean isLimitPriceProtectionEnabled() {
            return limitPriceProtectionEnabled;
        }

        public void setLimitPriceProtectionEnabled(boolean limitPriceProtectionEnabled) {
            this.limitPriceProtectionEnabled = limitPriceProtectionEnabled;
        }

        public long getLimitPriceBandPpm() {
            return limitPriceBandPpm;
        }

        public void setLimitPriceBandPpm(long limitPriceBandPpm) {
            this.limitPriceBandPpm = limitPriceBandPpm;
        }

        public long getLimitPriceMaxMarkAgeMs() {
            return limitPriceMaxMarkAgeMs;
        }

        public void setLimitPriceMaxMarkAgeMs(long limitPriceMaxMarkAgeMs) {
            this.limitPriceMaxMarkAgeMs = limitPriceMaxMarkAgeMs;
        }
    }

    public static class Algo {
        private boolean enabled = true;
        private int claimBatchSize = 100;
        private long scanDelayMs = 250L;
        private long minIntervalSeconds = 1L;
        private long maxIntervalSeconds = 86_400L;
        private long minDurationSeconds = 5L;
        private long maxDurationSeconds = 86_400L;
        private Duration claimLease = Duration.ofSeconds(30);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getClaimBatchSize() {
            return claimBatchSize;
        }

        public void setClaimBatchSize(int claimBatchSize) {
            this.claimBatchSize = claimBatchSize;
        }

        public long getScanDelayMs() {
            return scanDelayMs;
        }

        public void setScanDelayMs(long scanDelayMs) {
            this.scanDelayMs = scanDelayMs;
        }

        public long getMinIntervalSeconds() {
            return minIntervalSeconds;
        }

        public void setMinIntervalSeconds(long minIntervalSeconds) {
            this.minIntervalSeconds = minIntervalSeconds;
        }

        public long getMaxIntervalSeconds() {
            return maxIntervalSeconds;
        }

        public void setMaxIntervalSeconds(long maxIntervalSeconds) {
            this.maxIntervalSeconds = maxIntervalSeconds;
        }

        public long getMinDurationSeconds() {
            return minDurationSeconds;
        }

        public void setMinDurationSeconds(long minDurationSeconds) {
            this.minDurationSeconds = minDurationSeconds;
        }

        public long getMaxDurationSeconds() {
            return maxDurationSeconds;
        }

        public void setMaxDurationSeconds(long maxDurationSeconds) {
            this.maxDurationSeconds = maxDurationSeconds;
        }

        public Duration getClaimLease() {
            return claimLease;
        }

        public void setClaimLease(Duration claimLease) {
            if (claimLease == null || claimLease.isZero() || claimLease.isNegative()) {
                throw new IllegalArgumentException("algo claim lease must be positive");
            }
            this.claimLease = claimLease;
        }
    }
}
