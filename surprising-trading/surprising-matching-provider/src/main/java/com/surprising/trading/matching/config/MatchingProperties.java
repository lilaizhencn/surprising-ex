package com.surprising.trading.matching.config;

import com.surprising.product.api.ProductLine;
import com.surprising.product.api.ProductTopicNames;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "surprising.trading.matching")
public class MatchingProperties {

    private Kafka kafka = new Kafka();
    private Engine engine = new Engine();
    private Recovery recovery = new Recovery();
    private Protection protection = new Protection();
    private Outbox outbox = new Outbox();

    public Kafka getKafka() {
        return kafka;
    }

    public void setKafka(Kafka kafka) {
        this.kafka = kafka;
    }

    public Engine getEngine() {
        return engine;
    }

    public void setEngine(Engine engine) {
        this.engine = engine;
    }

    public Protection getProtection() {
        return protection;
    }

    public Recovery getRecovery() {
        return recovery;
    }

    public void setRecovery(Recovery recovery) {
        this.recovery = recovery;
    }

    public void setProtection(Protection protection) {
        this.protection = protection;
    }

    public Outbox getOutbox() {
        return outbox;
    }

    public void setOutbox(Outbox outbox) {
        this.outbox = outbox;
    }

    public static class Kafka {
        private String bootstrapServers = "localhost:9092";
        private ProductLine productLine = ProductLine.LINEAR_PERPETUAL;
        private boolean productTopicsEnabled;
        private String groupId = "surprising-matching-v1";
        private String clientId = "surprising-matching";
        private String orderCommandsTopic = "surprising.perp.order.commands.v1";
        private String matchResultsTopic = "surprising.perp.match.results.v1";
        private String matchTradesTopic = "surprising.perp.match.trades.v1";
        private String orderBookDepthTopic = "surprising.perp.orderbook.depth.v1";
        private int concurrency = 4;
        private int maxPollRecords = 500;
        private boolean restartOnPartitionReassignment = true;
        private long partitionAssignmentStartupGraceMs = 30000L;

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
            return productTopicsEnabled ? productTopics().consumerGroup("matching") : groupId;
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

        public String getAccountUserCommandsTopic() {
            return productTopics().accountUserCommandsTopic();
        }

        public String getOrderBookDepthTopic() {
            return productTopicsEnabled ? productTopics().orderBookDepthTopic() : orderBookDepthTopic;
        }

        public void setOrderBookDepthTopic(String orderBookDepthTopic) {
            this.orderBookDepthTopic = orderBookDepthTopic;
        }

        public int getConcurrency() {
            return concurrency;
        }

        public void setConcurrency(int concurrency) {
            if (concurrency < 1 || concurrency > 64) {
                throw new IllegalArgumentException("matching Kafka concurrency must be in [1, 64]");
            }
            this.concurrency = concurrency;
        }

        public int getMaxPollRecords() {
            return maxPollRecords;
        }

        public void setMaxPollRecords(int maxPollRecords) {
            this.maxPollRecords = maxPollRecords;
        }

        public boolean isRestartOnPartitionReassignment() {
            return restartOnPartitionReassignment;
        }

        public void setRestartOnPartitionReassignment(boolean restartOnPartitionReassignment) {
            this.restartOnPartitionReassignment = restartOnPartitionReassignment;
        }

        public long getPartitionAssignmentStartupGraceMs() {
            return partitionAssignmentStartupGraceMs;
        }

        public void setPartitionAssignmentStartupGraceMs(long partitionAssignmentStartupGraceMs) {
            this.partitionAssignmentStartupGraceMs = partitionAssignmentStartupGraceMs;
        }

        private ProductTopicNames productTopics() {
            return ProductTopicNames.of(productLine);
        }
    }

    public static class Engine {
        private String exchangeId = "surprising-perp";
        private int matchingEngines = 4;
        private int riskEngines = 2;
        private int orderBookDepthForPostOnly = 1;
        private int orderBookDepthLevels = 50;
        private long orderBookSnapshotIntervalEvents = 1000L;
        private int initialSymbolRefreshDelayMs = 30000;

        public String getExchangeId() {
            return exchangeId;
        }

        public void setExchangeId(String exchangeId) {
            this.exchangeId = exchangeId;
        }

        public int getMatchingEngines() {
            return matchingEngines;
        }

        public void setMatchingEngines(int matchingEngines) {
            requirePowerOfTwo(matchingEngines, "matchingEngines");
            this.matchingEngines = matchingEngines;
        }

        public int getRiskEngines() {
            return riskEngines;
        }

        public void setRiskEngines(int riskEngines) {
            requirePowerOfTwo(riskEngines, "riskEngines");
            this.riskEngines = riskEngines;
        }

        private void requirePowerOfTwo(int value, String name) {
            if (value < 1 || value > 64 || (value & (value - 1)) != 0) {
                throw new IllegalArgumentException(name + " must be a power of two in [1, 64]");
            }
        }

        public int getOrderBookDepthForPostOnly() {
            return orderBookDepthForPostOnly;
        }

        public void setOrderBookDepthForPostOnly(int orderBookDepthForPostOnly) {
            this.orderBookDepthForPostOnly = orderBookDepthForPostOnly;
        }

        public int getOrderBookDepthLevels() {
            return orderBookDepthLevels;
        }

        public void setOrderBookDepthLevels(int orderBookDepthLevels) {
            this.orderBookDepthLevels = orderBookDepthLevels;
        }

        public long getOrderBookSnapshotIntervalEvents() {
            return orderBookSnapshotIntervalEvents;
        }

        public void setOrderBookSnapshotIntervalEvents(long orderBookSnapshotIntervalEvents) {
            this.orderBookSnapshotIntervalEvents = orderBookSnapshotIntervalEvents;
        }

        public int getInitialSymbolRefreshDelayMs() {
            return initialSymbolRefreshDelayMs;
        }

        public void setInitialSymbolRefreshDelayMs(int initialSymbolRefreshDelayMs) {
            this.initialSymbolRefreshDelayMs = initialSymbolRefreshDelayMs;
        }
    }

    public static class Recovery {
        private boolean openOrderBookRestoreEnabled = true;
        private int openOrderBatchSize = 10000;

        public boolean isOpenOrderBookRestoreEnabled() {
            return openOrderBookRestoreEnabled;
        }

        public void setOpenOrderBookRestoreEnabled(boolean openOrderBookRestoreEnabled) {
            this.openOrderBookRestoreEnabled = openOrderBookRestoreEnabled;
        }

        public int getOpenOrderBatchSize() {
            return openOrderBatchSize;
        }

        public void setOpenOrderBatchSize(int openOrderBatchSize) {
            this.openOrderBatchSize = openOrderBatchSize;
        }
    }

    public static class Protection {
        private boolean selfTradePreventionEnabled = true;
        private List<Long> selfTradePreventionBypassUserIds = new ArrayList<>();
        private long marketMaxSlippagePpm = 10_000L;
        private long marketMaxMarkAgeMs = 5_000L;

        public boolean isSelfTradePreventionEnabled() {
            return selfTradePreventionEnabled;
        }

        public void setSelfTradePreventionEnabled(boolean selfTradePreventionEnabled) {
            this.selfTradePreventionEnabled = selfTradePreventionEnabled;
        }

        public List<Long> getSelfTradePreventionBypassUserIds() {
            return selfTradePreventionBypassUserIds;
        }

        public void setSelfTradePreventionBypassUserIds(List<Long> selfTradePreventionBypassUserIds) {
            this.selfTradePreventionBypassUserIds = selfTradePreventionBypassUserIds == null
                    ? new ArrayList<>()
                    : new ArrayList<>(selfTradePreventionBypassUserIds);
        }

        public boolean isSelfTradePreventionBypassed(long userId) {
            return selfTradePreventionBypassUserIds.contains(userId);
        }

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
                throw new IllegalArgumentException("matching outbox retention must be positive");
            }
            this.retention = retention;
        }

        public long getCleanupDelayMs() {
            return cleanupDelayMs;
        }

        public void setCleanupDelayMs(long cleanupDelayMs) {
            if (cleanupDelayMs <= 0) {
                throw new IllegalArgumentException("matching outbox cleanupDelayMs must be positive");
            }
            this.cleanupDelayMs = cleanupDelayMs;
        }

        public int getCleanupBatchSize() {
            return cleanupBatchSize;
        }

        public void setCleanupBatchSize(int cleanupBatchSize) {
            if (cleanupBatchSize <= 0) {
                throw new IllegalArgumentException("matching outbox cleanupBatchSize must be positive");
            }
            this.cleanupBatchSize = cleanupBatchSize;
        }

        public int getCleanupMaxBatches() {
            return cleanupMaxBatches;
        }

        public void setCleanupMaxBatches(int cleanupMaxBatches) {
            if (cleanupMaxBatches <= 0) {
                throw new IllegalArgumentException("matching outbox cleanupMaxBatches must be positive");
            }
            this.cleanupMaxBatches = cleanupMaxBatches;
        }
    }
}
