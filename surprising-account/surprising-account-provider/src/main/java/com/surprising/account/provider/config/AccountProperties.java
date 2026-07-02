package com.surprising.account.provider.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "surprising.account")
public class AccountProperties {

    private Kafka kafka = new Kafka();
    private Outbox outbox = new Outbox();
    private Cache cache = new Cache();
    private PositionMargin positionMargin = new PositionMargin();

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

    public PositionMargin getPositionMargin() {
        return positionMargin;
    }

    public void setPositionMargin(PositionMargin positionMargin) {
        this.positionMargin = positionMargin;
    }

    public static class Kafka {
        private String bootstrapServers = "localhost:9092";
        private String groupId = "surprising-account-v1";
        private String clientId = "surprising-account";
        private String matchTradesTopic = "surprising.perp.match.trades.v1";
        private String orderCommandsTopic = "surprising.perp.order.commands.v1";
        private String orderEventsTopic = "surprising.perp.order.events.v1";
        private String positionEventsTopic = "surprising.account.position.events.v1";
        private String liquidationFeeEventsTopic = "surprising.account.liquidation-fee.events.v1";
        private int concurrency = 2;
        private int maxPollRecords = 500;

        public String getBootstrapServers() {
            return bootstrapServers;
        }

        public void setBootstrapServers(String bootstrapServers) {
            this.bootstrapServers = bootstrapServers;
        }

        public String getGroupId() {
            return groupId;
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
            return matchTradesTopic;
        }

        public void setMatchTradesTopic(String matchTradesTopic) {
            this.matchTradesTopic = matchTradesTopic;
        }

        public String getOrderCommandsTopic() {
            return orderCommandsTopic;
        }

        public void setOrderCommandsTopic(String orderCommandsTopic) {
            this.orderCommandsTopic = orderCommandsTopic;
        }

        public String getOrderEventsTopic() {
            return orderEventsTopic;
        }

        public void setOrderEventsTopic(String orderEventsTopic) {
            this.orderEventsTopic = orderEventsTopic;
        }

        public String getPositionEventsTopic() {
            return positionEventsTopic;
        }

        public void setPositionEventsTopic(String positionEventsTopic) {
            this.positionEventsTopic = positionEventsTopic;
        }

        public String getLiquidationFeeEventsTopic() {
            return liquidationFeeEventsTopic;
        }

        public void setLiquidationFeeEventsTopic(String liquidationFeeEventsTopic) {
            this.liquidationFeeEventsTopic = liquidationFeeEventsTopic;
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
    }

    public static class Outbox {
        private int batchSize = 200;
        private long publishDelayMs = 200L;
        private Duration sendTimeout = Duration.ofSeconds(3);

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
    }

    public static class Cache {
        private int contractSpecMaxEntries = 4096;
        private int orderFeeSnapshotMaxEntries = 200_000;

        public int getContractSpecMaxEntries() {
            return contractSpecMaxEntries;
        }

        public void setContractSpecMaxEntries(int contractSpecMaxEntries) {
            this.contractSpecMaxEntries = contractSpecMaxEntries;
        }

        public int getOrderFeeSnapshotMaxEntries() {
            return orderFeeSnapshotMaxEntries;
        }

        public void setOrderFeeSnapshotMaxEntries(int orderFeeSnapshotMaxEntries) {
            this.orderFeeSnapshotMaxEntries = orderFeeSnapshotMaxEntries;
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
