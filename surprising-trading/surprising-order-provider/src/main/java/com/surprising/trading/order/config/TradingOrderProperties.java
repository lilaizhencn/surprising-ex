package com.surprising.trading.order.config;

import com.surprising.product.api.ProductLine;
import com.surprising.product.api.ProductTopicNames;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "surprising.trading.order")
public class TradingOrderProperties {

    private Kafka kafka = new Kafka();
    private Outbox outbox = new Outbox();
    private Risk risk = new Risk();
    private FeeTier feeTier = new FeeTier();
    private Algo algo = new Algo();
    private RedisIndex redisIndex = new RedisIndex();

    public Kafka getKafka() {
        return kafka;
    }

    public void setKafka(Kafka kafka) {
        this.kafka = kafka;
    }

    public Outbox getOutbox() {
        return outbox;
    }

    public void setOutbox(Outbox outbox) {
        this.outbox = outbox;
    }

    public Risk getRisk() {
        return risk;
    }

    public void setRisk(Risk risk) {
        this.risk = risk;
    }

    public FeeTier getFeeTier() {
        return feeTier;
    }

    public void setFeeTier(FeeTier feeTier) {
        this.feeTier = feeTier;
    }

    public Algo getAlgo() {
        return algo;
    }

    public void setAlgo(Algo algo) {
        this.algo = algo;
    }

    public RedisIndex getRedisIndex() { return redisIndex; }

    public void setRedisIndex(RedisIndex redisIndex) { this.redisIndex = redisIndex; }

    public static class Kafka {
        private String bootstrapServers = "localhost:9092";
        private ProductLine productLine = ProductLine.LINEAR_PERPETUAL;
        private boolean productTopicsEnabled;
        private String orderCommandsTopic = "surprising.perp.order.commands.v1";
        private String orderEventsTopic = "surprising.perp.order.events.v1";
        private String matchResultsTopic = "surprising.perp.match.results.v1";
        private String positionEventsTopic = "surprising.account.position.events.v1";
        private String openOrderViewGroupId = "surprising-order-open-view-v1";
        private String positionMaintenanceGroupId = "surprising-order-position-maintenance-v1";

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

        public String getOrderCommandsTopic() {
            return productTopicsEnabled ? productTopics().orderCommandsTopic() : orderCommandsTopic;
        }

        public void setOrderCommandsTopic(String orderCommandsTopic) {
            this.orderCommandsTopic = orderCommandsTopic;
        }

        public String getOrderEventsTopic() {
            return productTopicsEnabled ? productTopics().orderEventsTopic() : orderEventsTopic;
        }

        public void setOrderEventsTopic(String orderEventsTopic) {
            this.orderEventsTopic = orderEventsTopic;
        }
        public String getMatchResultsTopic() { return productTopicsEnabled ? productTopics().matchResultsTopic() : matchResultsTopic; }
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
        public String getPositionEventsTopic() {
            return productTopicsEnabled ? productTopics().accountPositionEventsTopic() : positionEventsTopic;
        }
        public void setPositionEventsTopic(String positionEventsTopic) {
            this.positionEventsTopic = positionEventsTopic;
        }
        public String getPositionMaintenanceGroupId() {
            return productTopicsEnabled
                    ? productTopics().consumerGroup("order-position-maintenance")
                    : positionMaintenanceGroupId;
        }
        public void setPositionMaintenanceGroupId(String positionMaintenanceGroupId) {
            this.positionMaintenanceGroupId = positionMaintenanceGroupId;
        }
        public String getOpenOrderViewGroupId() { return openOrderViewGroupId; }
        public void setOpenOrderViewGroupId(String openOrderViewGroupId) { this.openOrderViewGroupId = openOrderViewGroupId; }

        private ProductTopicNames productTopics() {
            return ProductTopicNames.of(productLine);
        }
    }

    public static class Outbox {
        private int batchSize = 1000;
        private long publishDelayMs = 20L;
        private Duration sendTimeout = Duration.ofSeconds(3);
        private boolean asyncEnabled = true;
        private int maxInFlight = 64;
        private Duration retention = Duration.ofDays(7);
        private long cleanupDelayMs = 60_000L;
        private int cleanupBatchSize = 10_000;
        private int cleanupMaxBatches = 10;

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public long getPublishDelayMs() {
            return publishDelayMs;
        }

        public void setPublishDelayMs(long publishDelayMs) {
            this.publishDelayMs = publishDelayMs;
        }

        public Duration getSendTimeout() {
            return sendTimeout;
        }

        public void setSendTimeout(Duration sendTimeout) {
            this.sendTimeout = sendTimeout;
        }

        public boolean isAsyncEnabled() {
            return asyncEnabled;
        }

        public void setAsyncEnabled(boolean asyncEnabled) {
            this.asyncEnabled = asyncEnabled;
        }

        public int getMaxInFlight() {
            return maxInFlight;
        }

        public void setMaxInFlight(int maxInFlight) {
            this.maxInFlight = maxInFlight;
        }

        public Duration getRetention() {
            return retention;
        }

        public void setRetention(Duration retention) {
            if (retention == null || retention.isZero() || retention.isNegative()) {
                throw new IllegalArgumentException("trading order outbox retention must be positive");
            }
            this.retention = retention;
        }

        public long getCleanupDelayMs() {
            return cleanupDelayMs;
        }

        public void setCleanupDelayMs(long cleanupDelayMs) {
            if (cleanupDelayMs <= 0) {
                throw new IllegalArgumentException("trading order outbox cleanupDelayMs must be positive");
            }
            this.cleanupDelayMs = cleanupDelayMs;
        }

        public int getCleanupBatchSize() {
            return cleanupBatchSize;
        }

        public void setCleanupBatchSize(int cleanupBatchSize) {
            if (cleanupBatchSize <= 0) {
                throw new IllegalArgumentException("trading order outbox cleanupBatchSize must be positive");
            }
            this.cleanupBatchSize = cleanupBatchSize;
        }

        public int getCleanupMaxBatches() {
            return cleanupMaxBatches;
        }

        public void setCleanupMaxBatches(int cleanupMaxBatches) {
            if (cleanupMaxBatches <= 0) {
                throw new IllegalArgumentException("trading order outbox cleanupMaxBatches must be positive");
            }
            this.cleanupMaxBatches = cleanupMaxBatches;
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

    public static class FeeTier {
        private boolean enabled = true;
        private long refreshInitialDelayMs = 60_000L;
        private long refreshDelayMs = 3_600_000L;
        private int batchSize = 1_000;
        private long lookbackDays = 30L;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getRefreshInitialDelayMs() {
            return refreshInitialDelayMs;
        }

        public void setRefreshInitialDelayMs(long refreshInitialDelayMs) {
            this.refreshInitialDelayMs = refreshInitialDelayMs;
        }

        public long getRefreshDelayMs() {
            return refreshDelayMs;
        }

        public void setRefreshDelayMs(long refreshDelayMs) {
            this.refreshDelayMs = refreshDelayMs;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public long getLookbackDays() {
            return lookbackDays;
        }

        public void setLookbackDays(long lookbackDays) {
            this.lookbackDays = lookbackDays;
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
