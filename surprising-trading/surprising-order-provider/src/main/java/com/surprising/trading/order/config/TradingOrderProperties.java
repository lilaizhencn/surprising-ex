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
    private RedisIndex redisIndex = new RedisIndex();
    private Wal wal = new Wal();

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

    public RedisIndex getRedisIndex() { return redisIndex; }

    public void setRedisIndex(RedisIndex redisIndex) { this.redisIndex = redisIndex; }

    public Wal getWal() {
        return wal;
    }

    public void setWal(Wal wal) {
        this.wal = wal == null ? new Wal() : wal;
    }

    public static class Kafka {
        private String bootstrapServers = "localhost:9092";
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
        private String leverageSettingEventsTopic = "surprising.perp.leverage.setting.events.v1";
        private String positionMaintenanceGroupId = "surprising-order-position-maintenance-v1";
        private String accountStateSnapshotGroupId = "surprising-order-account-state-v1";
        private int accountCommandResultsConcurrency = 32;

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
        public String getAccountCommandResultsGroupId() {
            return productTopics().consumerGroup("order-account-results");
        }
        public int getAccountCommandResultsConcurrency() { return accountCommandResultsConcurrency; }
        public void setAccountCommandResultsConcurrency(int accountCommandResultsConcurrency) {
            this.accountCommandResultsConcurrency = Math.max(1, accountCommandResultsConcurrency);
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
            return productTopics().consumerGroup("order-account-state");
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
        public String getLeverageSettingEventsTopic() {
            return productTopics().leverageSettingEventsTopic();
        }
        public void setLeverageSettingEventsTopic(String leverageSettingEventsTopic) {
            this.leverageSettingEventsTopic = leverageSettingEventsTopic;
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
        public String getLeverageSettingSnapshotGroupId() {
            return productTopics().consumerGroup("order-leverage-snapshot");
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

    /** 订单用户分区事实流的本地存储配置。 */
    public static class Wal {
        private String directory = "data/order-wal";
        private int nodeId = 1;
        private long workerDelayMs = 25L;

        public String getDirectory() {
            return directory;
        }

        public void setDirectory(String directory) {
            if (directory == null || directory.isBlank()) {
                throw new IllegalArgumentException("订单 WAL 目录不能为空");
            }
            this.directory = directory.trim();
        }

        public int getNodeId() {
            return nodeId;
        }

        public void setNodeId(int nodeId) {
            if (nodeId < 0 || nodeId > 1023) {
                throw new IllegalArgumentException("订单 WAL nodeId 必须在 0 到 1023 之间");
            }
            this.nodeId = nodeId;
        }

        public long getWorkerDelayMs() {
            return workerDelayMs;
        }

        public void setWorkerDelayMs(long workerDelayMs) {
            if (workerDelayMs <= 0L) {
                throw new IllegalArgumentException("订单 WAL workerDelayMs 必须为正数");
            }
            this.workerDelayMs = workerDelayMs;
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
    }

    public static class RedisIndex {
        private String keyPrefix = "surprising:order:v1";
        private long reconcileDelayMs = 10_000L;
        private int rebuildBatchSize = 1_000;
        private Duration rebuildMaxAge = Duration.ofMinutes(5);
        private Duration readyTtl = Duration.ofSeconds(30);
        private Duration lockTtl = Duration.ofSeconds(30);
        private Duration algoClaimLease = Duration.ofSeconds(30);
        public String getKeyPrefix() { return keyPrefix; }
        public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }
        public long getReconcileDelayMs() { return reconcileDelayMs; }
        public void setReconcileDelayMs(long reconcileDelayMs) { this.reconcileDelayMs = reconcileDelayMs; }
        public int getRebuildBatchSize() { return rebuildBatchSize; }
        public void setRebuildBatchSize(int rebuildBatchSize) { this.rebuildBatchSize = rebuildBatchSize; }
        public Duration getRebuildMaxAge() { return rebuildMaxAge; }
        public void setRebuildMaxAge(Duration rebuildMaxAge) {
            this.rebuildMaxAge = rebuildMaxAge == null || rebuildMaxAge.isNegative() || rebuildMaxAge.isZero()
                    ? Duration.ofMinutes(5) : rebuildMaxAge;
        }
        public Duration getReadyTtl() { return readyTtl; }
        public void setReadyTtl(Duration readyTtl) { this.readyTtl = readyTtl; }
        public Duration getLockTtl() { return lockTtl; }
        public void setLockTtl(Duration lockTtl) { this.lockTtl = lockTtl; }
        public Duration getAlgoClaimLease() { return algoClaimLease; }
        public void setAlgoClaimLease(Duration algoClaimLease) { this.algoClaimLease = algoClaimLease; }
    }
}
