package com.surprising.account.provider.config;

import com.surprising.product.api.ProductLine;
import com.surprising.product.api.ProductLineConfiguration;
import com.surprising.product.api.ProductTopicNames;
import java.time.Duration;
import java.util.UUID;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "surprising.account")
public class AccountProperties {

    private Kafka kafka = new Kafka();
    private Cache cache = new Cache();
    private PositionCache positionCache = new PositionCache();
    private PositionMargin positionMargin = new PositionMargin();
    private ExpiringSettlement expiringSettlement = new ExpiringSettlement();
    private TradeSettlement tradeSettlement = new TradeSettlement();
    private CommandWait commandWait = new CommandWait();
    private Wal wal = new Wal();
    private Aeron aeron = new Aeron();
    private String internalServiceSecret = "";

    /** 启动时拒绝未隔离的旧版 Topic 配置。 */
    @PostConstruct
    void validateProductLineConfiguration() {
        ProductLineConfiguration.require(kafka.productLine, kafka.productTopicsEnabled, "account");
    }

    public Kafka getKafka() {
        return kafka;
    }

    public void setKafka(Kafka kafka) {
        this.kafka = kafka;
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

    public Wal getWal() {
        return wal;
    }

    public void setWal(Wal wal) {
        this.wal = wal == null ? new Wal() : wal;
    }

    public Aeron getAeron() {
        return aeron;
    }

    public void setAeron(Aeron aeron) {
        this.aeron = aeron == null ? new Aeron() : aeron;
    }

    public String getInternalServiceSecret() {
        return internalServiceSecret;
    }

    public void setInternalServiceSecret(String internalServiceSecret) {
        this.internalServiceSecret = internalServiceSecret;
    }

    public static class Kafka {
        private String bootstrapServers = "localhost:9092";
        /** 必须由部署配置显式指定，禁止缺省落到永续产品线。 */
        private ProductLine productLine;
        private boolean productTopicsEnabled;
        private String groupId = "surprising-account-v1";
        /** 每个账户 JVM 的实例标识；快照广播消费组必须按实例隔离。 */
        private String clientId = "account-provider-" + UUID.randomUUID();
        private String orderCommandsTopic = "surprising.perp.order.commands.v1";
        private String orderEventsTopic = "surprising.perp.order.events.v1";
        private String positionEventsTopic = "surprising.account.position.events.v1";
        private String openInterestEventsTopic = "surprising.account.open-interest.events.v1";
        private String liquidationFeeEventsTopic = "surprising.account.liquidation-fee.events.v1";
        private String accountStateEventsTopic = "surprising.account.state.events.v1";
        private String triggerOrderEventsTopic = "surprising.perp.trigger-order.events.v1";
        private String deliverySettlementsTopic = "surprising.linear-delivery.delivery.settlements.v1";
        private String optionExercisesTopic = "surprising.option.option.exercises.v1";
        private String instrumentLifecycleDrainTopic = "surprising.instrument.lifecycle-drain.v1";
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
            this.productLine = productLine;
        }

        public boolean isProductTopicsEnabled() {
            return productTopicsEnabled;
        }

        public void setProductTopicsEnabled(boolean productTopicsEnabled) {
            this.productTopicsEnabled = productTopicsEnabled;
        }

        public String getGroupId() {
            return productTopics().consumerGroup("account");
        }

        public void setGroupId(String groupId) {
            this.groupId = groupId;
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            if (clientId == null || clientId.isBlank()) {
                throw new IllegalArgumentException("账户节点 clientId 不能为空");
            }
            this.clientId = clientId.trim();
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

        public String getPositionEventsTopic() {
            return productTopics().accountPositionEventsTopic();
        }

        public void setPositionEventsTopic(String positionEventsTopic) {
            this.positionEventsTopic = positionEventsTopic;
        }

        public String getOpenInterestEventsTopic() {
            return productTopics().accountOpenInterestEventsTopic();
        }

        public void setOpenInterestEventsTopic(String openInterestEventsTopic) {
            this.openInterestEventsTopic = openInterestEventsTopic;
        }

        public String getLiquidationFeeEventsTopic() {
            return productTopics().accountLiquidationFeeEventsTopic();
        }

        public void setLiquidationFeeEventsTopic(String liquidationFeeEventsTopic) {
            this.liquidationFeeEventsTopic = liquidationFeeEventsTopic;
        }

        public String getAccountStateEventsTopic() {
            return productTopics().accountStateEventsTopic();
        }

        public void setAccountStateEventsTopic(String accountStateEventsTopic) {
            this.accountStateEventsTopic = accountStateEventsTopic;
        }

        public String getUserCommandsTopic() {
            return productTopics().accountUserCommandsTopic();
        }

        public String getUserMutationsTopic() {
            return productTopics().userMutationsTopic();
        }

        public String getUserStateChangelogTopic() {
            return productTopics().userStateChangelogTopic();
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

        public String getPositionCacheGroupId() {
            return productTopics().consumerGroup("account-position-cache");
        }

        public String getTriggerOrderEventsTopic() {
            return productTopics().triggerOrderEventsTopic();
        }

        public void setTriggerOrderEventsTopic(String triggerOrderEventsTopic) {
            this.triggerOrderEventsTopic = triggerOrderEventsTopic;
        }

        public String getDeliverySettlementsTopic() {
            return productTopics().deliverySettlementsTopic();
        }

        public void setDeliverySettlementsTopic(String deliverySettlementsTopic) {
            this.deliverySettlementsTopic = deliverySettlementsTopic;
        }

        public boolean isDeliverySettlementsTopicEnabled() {
            return productLine == ProductLine.LINEAR_DELIVERY
                    || productLine == ProductLine.INVERSE_DELIVERY;
        }

        public String getOptionExercisesTopic() {
            return productTopics().optionExercisesTopic();
        }

        public void setOptionExercisesTopic(String optionExercisesTopic) {
            this.optionExercisesTopic = optionExercisesTopic;
        }

        public boolean isOptionExercisesTopicEnabled() {
            return productLine.isOptionProduct();
        }

        public String getInstrumentLifecycleDrainTopic() {
            return instrumentLifecycleDrainTopic;
        }

        public void setInstrumentLifecycleDrainTopic(String instrumentLifecycleDrainTopic) {
            this.instrumentLifecycleDrainTopic = instrumentLifecycleDrainTopic;
        }

        public String getInstrumentLifecycleGroupId() {
            return productTopics().consumerGroup("account-instrument-lifecycle");
        }
        public String getInstrumentSnapshotGroupId() {
            return productTopics().consumerGroup("account-instrument-snapshot");
        }

        /** 账户状态快照是每个 JVM 都必须拥有的广播数据，不能与其他实例共享消费组。 */
        public String getAccountStateReducerSnapshotGroupId() {
            return productTopics().consumerGroup("account-reducer-state") + "-" + clientId;
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

    public static class Cache {
        private int contractSpecMaxEntries = 4096;
        private int instrumentTypeMaxEntries = 4096;
        private int spotInstrumentSpecMaxEntries = 4096;

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

    }

    public static class Aeron {
        private java.util.List<String> hostnames = java.util.List.of("localhost", "localhost", "localhost");
        private String egressHostname = "localhost";
        private Duration responseTimeout = Duration.ofSeconds(5);
        private int clientConnections = 4;

        public java.util.List<String> getHostnames() { return hostnames; }
        public void setHostnames(java.util.List<String> hostnames) {
            if (hostnames == null || hostnames.size() != 3 || hostnames.stream().anyMatch(value -> value == null || value.isBlank())) {
                throw new IllegalArgumentException("aeron hostnames must contain three non-blank members");
            }
            this.hostnames = java.util.List.copyOf(hostnames);
        }
        public String getEgressHostname() { return egressHostname; }
        public void setEgressHostname(String egressHostname) {
            if (egressHostname == null || egressHostname.isBlank()) throw new IllegalArgumentException("aeron egress hostname is required");
            this.egressHostname = egressHostname.trim();
        }
        public Duration getResponseTimeout() { return responseTimeout; }
        public void setResponseTimeout(Duration responseTimeout) {
            if (responseTimeout == null || responseTimeout.isZero() || responseTimeout.isNegative()) throw new IllegalArgumentException("aeron response timeout must be positive");
            this.responseTimeout = responseTimeout;
        }
        public int getClientConnections() { return clientConnections; }
        public void setClientConnections(int clientConnections) {
            if (clientConnections < 1 || clientConnections > 64) throw new IllegalArgumentException("aeron client connections must be in [1,64]");
            this.clientConnections = clientConnections;
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
        private Duration completedCacheTtl = Duration.ofMinutes(5);
        private int completedCacheMaxEntries = 10_000;

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

        public Duration getCompletedCacheTtl() {
            return completedCacheTtl;
        }

        public void setCompletedCacheTtl(Duration completedCacheTtl) {
            if (completedCacheTtl == null || completedCacheTtl.isZero() || completedCacheTtl.isNegative()) {
                throw new IllegalArgumentException("command wait completedCacheTtl must be positive");
            }
            this.completedCacheTtl = completedCacheTtl;
        }

        public int getCompletedCacheMaxEntries() {
            return completedCacheMaxEntries;
        }

        public void setCompletedCacheMaxEntries(int completedCacheMaxEntries) {
            if (completedCacheMaxEntries <= 0) {
                throw new IllegalArgumentException("command wait completedCacheMaxEntries must be positive");
            }
            this.completedCacheMaxEntries = completedCacheMaxEntries;
        }
    }

    public static class Wal {
        private String directory = "data/account-wal";
        private long projectionDelayMs = 25L;
        private int projectionBatchSize = 100;

        public String getDirectory() {
            return directory;
        }

        public void setDirectory(String directory) {
            if (directory == null || directory.isBlank()) {
                throw new IllegalArgumentException("account WAL directory is required");
            }
            this.directory = directory.trim();
        }

        public long getProjectionDelayMs() {
            return projectionDelayMs;
        }

        public void setProjectionDelayMs(long projectionDelayMs) {
            if (projectionDelayMs <= 0L) {
                throw new IllegalArgumentException("account WAL projectionDelayMs must be positive");
            }
            this.projectionDelayMs = projectionDelayMs;
        }

        public int getProjectionBatchSize() {
            return projectionBatchSize;
        }

        public void setProjectionBatchSize(int projectionBatchSize) {
            if (projectionBatchSize <= 0) {
                throw new IllegalArgumentException("account WAL projectionBatchSize must be positive");
            }
            this.projectionBatchSize = projectionBatchSize;
        }
    }
}
