package com.surprising.account.provider.config;

import com.surprising.product.api.ProductLine;
import com.surprising.product.api.ProductTopicNames;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "surprising.account")
public class AccountProperties {

    private Kafka kafka = new Kafka();
    private Outbox outbox = new Outbox();
    private Cache cache = new Cache();
    private PositionCache positionCache = new PositionCache();
    private PositionMargin positionMargin = new PositionMargin();
    private ExpiringSettlement expiringSettlement = new ExpiringSettlement();
    private TradeSettlement tradeSettlement = new TradeSettlement();
    private CommandWait commandWait = new CommandWait();

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

    public Cache getCache() {
        return cache;
    }

    public void setCache(Cache cache) {
        this.cache = cache;
    }

    public PositionCache getPositionCache() {
        return positionCache;
    }

    public void setPositionCache(PositionCache positionCache) {
        this.positionCache = positionCache;
    }

    public PositionMargin getPositionMargin() {
        return positionMargin;
    }

    public void setPositionMargin(PositionMargin positionMargin) {
        this.positionMargin = positionMargin;
    }

    public ExpiringSettlement getExpiringSettlement() {
        return expiringSettlement;
    }

    public void setExpiringSettlement(ExpiringSettlement expiringSettlement) {
        this.expiringSettlement = expiringSettlement;
    }

    public TradeSettlement getTradeSettlement() {
        return tradeSettlement;
    }

    public void setTradeSettlement(TradeSettlement tradeSettlement) {
        this.tradeSettlement = tradeSettlement == null ? new TradeSettlement() : tradeSettlement;
    }

    public CommandWait getCommandWait() {
        return commandWait;
    }

    public void setCommandWait(CommandWait commandWait) {
        this.commandWait = commandWait == null ? new CommandWait() : commandWait;
    }

    public static class Kafka {
        private String bootstrapServers = "localhost:9092";
        private ProductLine productLine = ProductLine.LINEAR_PERPETUAL;
        private boolean productTopicsEnabled;
        private String groupId = "surprising-account-v1";
        private String clientId = "surprising-account";
        private String orderCommandsTopic = "surprising.perp.order.commands.v1";
        private String orderEventsTopic = "surprising.perp.order.events.v1";
        private String positionEventsTopic = "surprising.account.position.events.v1";
        private String liquidationFeeEventsTopic = "surprising.account.liquidation-fee.events.v1";
        private String triggerOrderEventsTopic = "surprising.perp.trigger-order.events.v1";
        private String deliverySettlementsTopic = "surprising.linear-delivery.delivery.settlements.v1";
        private String optionExercisesTopic = "surprising.option.option.exercises.v1";
        private int concurrency = 2;
        private int userCommandConcurrency = 32;
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
            return productTopicsEnabled ? productTopics().consumerGroup("account") : groupId;
        }

        public void setGroupId(String groupId) {
            this.groupId = groupId;
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
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

        public String getPositionEventsTopic() {
            return productTopicsEnabled ? productTopics().accountPositionEventsTopic() : positionEventsTopic;
        }

        public void setPositionEventsTopic(String positionEventsTopic) {
            this.positionEventsTopic = positionEventsTopic;
        }

        public String getLiquidationFeeEventsTopic() {
            return productTopicsEnabled ? productTopics().accountLiquidationFeeEventsTopic() : liquidationFeeEventsTopic;
        }

        public void setLiquidationFeeEventsTopic(String liquidationFeeEventsTopic) {
            this.liquidationFeeEventsTopic = liquidationFeeEventsTopic;
        }

        public String getUserCommandsTopic() {
            return productTopics().accountUserCommandsTopic();
        }

        public String getUserCommandsDltTopic() {
            return productTopics().accountUserCommandsDltTopic();
        }

        public String getCommandResultsTopic() {
            return productTopics().accountCommandResultsTopic();
        }

        public String getUserCommandGroupId() {
            return productTopics().consumerGroup("account-user-command");
        }

        public String getTriggerOrderEventsTopic() {
            return productTopicsEnabled ? productTopics().triggerOrderEventsTopic() : triggerOrderEventsTopic;
        }

        public void setTriggerOrderEventsTopic(String triggerOrderEventsTopic) {
            this.triggerOrderEventsTopic = triggerOrderEventsTopic;
        }

        public String getDeliverySettlementsTopic() {
            return isDeliverySettlementsTopicEnabled() && productTopicsEnabled
                    ? productTopics().deliverySettlementsTopic()
                    : deliverySettlementsTopic;
        }

        public void setDeliverySettlementsTopic(String deliverySettlementsTopic) {
            this.deliverySettlementsTopic = deliverySettlementsTopic;
        }

        public boolean isDeliverySettlementsTopicEnabled() {
            return !productTopicsEnabled
                    || productLine == ProductLine.LINEAR_DELIVERY
                    || productLine == ProductLine.INVERSE_DELIVERY;
        }

        public String getOptionExercisesTopic() {
            return isOptionExercisesTopicEnabled() && productTopicsEnabled
                    ? productTopics().optionExercisesTopic()
                    : optionExercisesTopic;
        }

        public void setOptionExercisesTopic(String optionExercisesTopic) {
            this.optionExercisesTopic = optionExercisesTopic;
        }

        public boolean isOptionExercisesTopicEnabled() {
            return !productTopicsEnabled || productLine.isOptionProduct();
        }

        public int getConcurrency() {
            return concurrency;
        }

        public void setConcurrency(int concurrency) {
            this.concurrency = concurrency;
        }

        public int getUserCommandConcurrency() {
            return userCommandConcurrency;
        }

        public void setUserCommandConcurrency(int userCommandConcurrency) {
            if (userCommandConcurrency <= 0) {
                throw new IllegalArgumentException("userCommandConcurrency must be positive");
            }
            this.userCommandConcurrency = userCommandConcurrency;
        }

        public int getMaxPollRecords() {
            return maxPollRecords;
        }

        public void setMaxPollRecords(int maxPollRecords) {
            this.maxPollRecords = maxPollRecords;
        }

        private ProductTopicNames productTopics() {
            return ProductTopicNames.of(productLine);
        }
    }

    public static class Outbox {
        private int batchSize = 1_000;
        private long publishDelayMs = 20L;
        private Duration sendTimeout = Duration.ofSeconds(3);
        private boolean asyncEnabled = true;
        private int maxInFlight = 32;
        private int maxRowsPerKey = 32;
        private int sendWindowSize = 5;
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

        public int getMaxRowsPerKey() {
            return maxRowsPerKey;
        }

        public void setMaxRowsPerKey(int maxRowsPerKey) {
            this.maxRowsPerKey = maxRowsPerKey;
        }

        public int getSendWindowSize() {
            return sendWindowSize;
        }

        public void setSendWindowSize(int sendWindowSize) {
            if (sendWindowSize < 1 || sendWindowSize > 5) {
                throw new IllegalArgumentException("account outbox sendWindowSize must be in [1, 5]");
            }
            this.sendWindowSize = sendWindowSize;
        }

        public Duration getRetention() {
            return retention;
        }

        public void setRetention(Duration retention) {
            if (retention == null || retention.isZero() || retention.isNegative()) {
                throw new IllegalArgumentException("account outbox retention must be positive");
            }
            this.retention = retention;
        }

        public long getCleanupDelayMs() {
            return cleanupDelayMs;
        }

        public void setCleanupDelayMs(long cleanupDelayMs) {
            if (cleanupDelayMs <= 0) {
                throw new IllegalArgumentException("account outbox cleanupDelayMs must be positive");
            }
            this.cleanupDelayMs = cleanupDelayMs;
        }

        public int getCleanupBatchSize() {
            return cleanupBatchSize;
        }

        public void setCleanupBatchSize(int cleanupBatchSize) {
            if (cleanupBatchSize <= 0) {
                throw new IllegalArgumentException("account outbox cleanupBatchSize must be positive");
            }
            this.cleanupBatchSize = cleanupBatchSize;
        }

        public int getCleanupMaxBatches() {
            return cleanupMaxBatches;
        }

        public void setCleanupMaxBatches(int cleanupMaxBatches) {
            if (cleanupMaxBatches <= 0) {
                throw new IllegalArgumentException("account outbox cleanupMaxBatches must be positive");
            }
            this.cleanupMaxBatches = cleanupMaxBatches;
        }
    }

    public static class Cache {
        private int contractSpecMaxEntries = 4096;
        private int instrumentTypeMaxEntries = 4096;
        private int spotInstrumentSpecMaxEntries = 4096;
        private int liquidationFeeContextMaxEntries = 200_000;

        public int getContractSpecMaxEntries() {
            return contractSpecMaxEntries;
        }

        public void setContractSpecMaxEntries(int contractSpecMaxEntries) {
            this.contractSpecMaxEntries = contractSpecMaxEntries;
        }

        public int getInstrumentTypeMaxEntries() {
            return instrumentTypeMaxEntries;
        }

        public void setInstrumentTypeMaxEntries(int instrumentTypeMaxEntries) {
            this.instrumentTypeMaxEntries = instrumentTypeMaxEntries;
        }

        public int getSpotInstrumentSpecMaxEntries() {
            return spotInstrumentSpecMaxEntries;
        }

        public void setSpotInstrumentSpecMaxEntries(int spotInstrumentSpecMaxEntries) {
            this.spotInstrumentSpecMaxEntries = spotInstrumentSpecMaxEntries;
        }

        public int getLiquidationFeeContextMaxEntries() {
            return liquidationFeeContextMaxEntries;
        }

        public void setLiquidationFeeContextMaxEntries(int liquidationFeeContextMaxEntries) {
            this.liquidationFeeContextMaxEntries = liquidationFeeContextMaxEntries;
        }
    }

    public static class PositionCache {
        private String keyPrefix = "surprising:position:v1";
        private int rebuildBatchSize = 1_000;
        private long reconcileDelayMs = 10_000L;
        private Duration readyTtl = Duration.ofSeconds(30);
        private Duration lockTtl = Duration.ofSeconds(30);
        private int acceleratorThreads = 4;
        private int acceleratorQueueCapacity = 10_000;

        public String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }

        public int getRebuildBatchSize() {
            return rebuildBatchSize;
        }

        public void setRebuildBatchSize(int rebuildBatchSize) {
            if (rebuildBatchSize <= 0) {
                throw new IllegalArgumentException("position cache rebuildBatchSize must be positive");
            }
            this.rebuildBatchSize = rebuildBatchSize;
        }

        public long getReconcileDelayMs() {
            return reconcileDelayMs;
        }

        public void setReconcileDelayMs(long reconcileDelayMs) {
            if (reconcileDelayMs <= 0L) {
                throw new IllegalArgumentException("position cache reconcileDelayMs must be positive");
            }
            this.reconcileDelayMs = reconcileDelayMs;
        }

        public Duration getReadyTtl() {
            return readyTtl;
        }

        public void setReadyTtl(Duration readyTtl) {
            this.readyTtl = requirePositive(readyTtl, "readyTtl");
        }

        public Duration getLockTtl() {
            return lockTtl;
        }

        public void setLockTtl(Duration lockTtl) {
            this.lockTtl = requirePositive(lockTtl, "lockTtl");
        }

        public int getAcceleratorThreads() {
            return acceleratorThreads;
        }

        public void setAcceleratorThreads(int acceleratorThreads) {
            if (acceleratorThreads <= 0 || acceleratorThreads > 64) {
                throw new IllegalArgumentException("position cache acceleratorThreads must be in [1, 64]");
            }
            this.acceleratorThreads = acceleratorThreads;
        }

        public int getAcceleratorQueueCapacity() {
            return acceleratorQueueCapacity;
        }

        public void setAcceleratorQueueCapacity(int acceleratorQueueCapacity) {
            if (acceleratorQueueCapacity <= 0 || acceleratorQueueCapacity > 1_000_000) {
                throw new IllegalArgumentException(
                        "position cache acceleratorQueueCapacity must be in [1, 1000000]");
            }
            this.acceleratorQueueCapacity = acceleratorQueueCapacity;
        }

        private Duration requirePositive(Duration value, String name) {
            if (value == null || value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException("position cache " + name + " must be positive");
            }
            return value;
        }
    }

    public static class ExpiringSettlement {
        private Duration settlementPriceWindow = Duration.ofMinutes(30);

        public Duration getSettlementPriceWindow() {
            return settlementPriceWindow;
        }

        public void setSettlementPriceWindow(Duration settlementPriceWindow) {
            this.settlementPriceWindow = settlementPriceWindow == null
                    ? Duration.ZERO
                    : settlementPriceWindow;
        }
    }

    public static class PositionMargin {
        private Duration maxRiskSnapshotAge = Duration.ofSeconds(10);
        private long removalBufferPpm = 50_000L;

        public Duration getMaxRiskSnapshotAge() {
            return maxRiskSnapshotAge;
        }

        public void setMaxRiskSnapshotAge(Duration maxRiskSnapshotAge) {
            this.maxRiskSnapshotAge = maxRiskSnapshotAge;
        }

        public long getRemovalBufferPpm() {
            return removalBufferPpm;
        }

        public void setRemovalBufferPpm(long removalBufferPpm) {
            this.removalBufferPpm = removalBufferPpm;
        }
    }

    public static class TradeSettlement {
        private Duration staleAfter = Duration.ofMinutes(1);

        public Duration getStaleAfter() {
            return staleAfter;
        }

        public void setStaleAfter(Duration staleAfter) {
            if (staleAfter == null || staleAfter.isZero() || staleAfter.isNegative()) {
                throw new IllegalArgumentException("trade settlement staleAfter must be positive");
            }
            this.staleAfter = staleAfter;
        }
    }

    public static class CommandWait {
        private Duration timeout = Duration.ofSeconds(10);
        private long pollDelayMs = 20L;

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            if (timeout == null || timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("command wait timeout must be positive");
            }
            this.timeout = timeout;
        }

        public long getPollDelayMs() {
            return pollDelayMs;
        }

        public void setPollDelayMs(long pollDelayMs) {
            if (pollDelayMs <= 0L) {
                throw new IllegalArgumentException("command wait pollDelayMs must be positive");
            }
            this.pollDelayMs = pollDelayMs;
        }
    }
}
