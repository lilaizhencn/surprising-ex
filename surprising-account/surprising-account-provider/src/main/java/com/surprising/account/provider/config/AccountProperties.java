package com.surprising.account.provider.config;

import com.surprising.product.api.ProductLine;
import com.surprising.product.api.ProductTopicNames;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "surprising.account")
public class AccountProperties {

    private Kafka kafka = new Kafka();
    private Outbox outbox = new Outbox();
    private Settlement settlement = new Settlement();
    private Cache cache = new Cache();
    private PositionMargin positionMargin = new PositionMargin();
    private ExpiringSettlement expiringSettlement = new ExpiringSettlement();

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

    public Settlement getSettlement() {
        return settlement;
    }

    public void setSettlement(Settlement settlement) {
        this.settlement = settlement;
    }

    public Cache getCache() {
        return cache;
    }

    public void setCache(Cache cache) {
        this.cache = cache;
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

    public static class Kafka {
        private String bootstrapServers = "localhost:9092";
        private ProductLine productLine = ProductLine.LINEAR_PERPETUAL;
        private boolean productTopicsEnabled;
        private String groupId = "surprising-account-v1";
        private String clientId = "surprising-account";
        private String matchTradesTopic = "surprising.perp.match.trades.v1";
        private String orderCommandsTopic = "surprising.perp.order.commands.v1";
        private String orderEventsTopic = "surprising.perp.order.events.v1";
        private String positionEventsTopic = "surprising.account.position.events.v1";
        private String liquidationFeeEventsTopic = "surprising.account.liquidation-fee.events.v1";
        private String deliverySettlementsTopic = "surprising.linear-delivery.delivery.settlements.v1";
        private String optionExercisesTopic = "surprising.option.option.exercises.v1";
        private int concurrency = 2;
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

        public String getMatchTradesTopic() {
            return productTopicsEnabled ? productTopics().matchTradesTopic() : matchTradesTopic;
        }

        public void setMatchTradesTopic(String matchTradesTopic) {
            this.matchTradesTopic = matchTradesTopic;
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
        private int batchSize = 200;
        private long publishDelayMs = 200L;
        private Duration sendTimeout = Duration.ofSeconds(3);
        private boolean asyncEnabled = true;
        private int maxInFlight = 32;
        private int maxRowsPerKey = 32;

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
    }

    public static class Settlement {
        private int matchTradeUserLockStripes = 4096;

        public int getMatchTradeUserLockStripes() {
            return matchTradeUserLockStripes;
        }

        public void setMatchTradeUserLockStripes(int matchTradeUserLockStripes) {
            if (matchTradeUserLockStripes <= 0) {
                throw new IllegalArgumentException("matchTradeUserLockStripes must be positive");
            }
            this.matchTradeUserLockStripes = matchTradeUserLockStripes;
        }
    }

    public static class Cache {
        private int contractSpecMaxEntries = 4096;
        private int instrumentTypeMaxEntries = 4096;
        private int spotInstrumentSpecMaxEntries = 4096;
        private int orderFeeSnapshotMaxEntries = 200_000;
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

        public int getOrderFeeSnapshotMaxEntries() {
            return orderFeeSnapshotMaxEntries;
        }

        public void setOrderFeeSnapshotMaxEntries(int orderFeeSnapshotMaxEntries) {
            this.orderFeeSnapshotMaxEntries = orderFeeSnapshotMaxEntries;
        }

        public int getLiquidationFeeContextMaxEntries() {
            return liquidationFeeContextMaxEntries;
        }

        public void setLiquidationFeeContextMaxEntries(int liquidationFeeContextMaxEntries) {
            this.liquidationFeeContextMaxEntries = liquidationFeeContextMaxEntries;
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
}
