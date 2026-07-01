package com.surprising.risk.provider.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "surprising.risk")
public class RiskProperties {

    private Kafka kafka = new Kafka();
    private Calculation calculation = new Calculation();
    private Coordination coordination = new Coordination();
    private Outbox outbox = new Outbox();

    public Kafka getKafka() {
        return kafka;
    }

    public void setKafka(Kafka kafka) {
        this.kafka = kafka;
    }

    public Calculation getCalculation() {
        return calculation;
    }

    public void setCalculation(Calculation calculation) {
        this.calculation = calculation;
    }

    public Coordination getCoordination() {
        return coordination;
    }

    public void setCoordination(Coordination coordination) {
        this.coordination = coordination;
    }

    public Outbox getOutbox() {
        return outbox;
    }

    public void setOutbox(Outbox outbox) {
        this.outbox = outbox;
    }

    public static class Kafka {
        private String bootstrapServers = "localhost:9092";
        private String groupId = "surprising-risk-v1";
        private String positionEventsTopic = "surprising.account.position.events.v1";
        private String liquidationCandidatesTopic = "surprising.perp.liquidation.candidates.v1";
        private int concurrency = 2;

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

        public String getPositionEventsTopic() {
            return positionEventsTopic;
        }

        public void setPositionEventsTopic(String positionEventsTopic) {
            this.positionEventsTopic = positionEventsTopic;
        }

        public String getLiquidationCandidatesTopic() {
            return liquidationCandidatesTopic;
        }

        public void setLiquidationCandidatesTopic(String liquidationCandidatesTopic) {
            this.liquidationCandidatesTopic = liquidationCandidatesTopic;
        }

        public int getConcurrency() {
            return concurrency;
        }

        public void setConcurrency(int concurrency) {
            this.concurrency = concurrency;
        }
    }

    public static class Calculation {
        private boolean enabled = true;
        private long scanDelayMs = 1000L;
        private long warningMarginRatioPpm = 800_000L;
        private long liquidationMarginRatioPpm = 1_000_000L;
        private Duration maxMarkAge = Duration.ofSeconds(10);
        private int scanBatchSize = 500;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getScanDelayMs() {
            return scanDelayMs;
        }

        public void setScanDelayMs(long scanDelayMs) {
            this.scanDelayMs = scanDelayMs;
        }

        public long getWarningMarginRatioPpm() {
            return warningMarginRatioPpm;
        }

        public void setWarningMarginRatioPpm(long warningMarginRatioPpm) {
            this.warningMarginRatioPpm = warningMarginRatioPpm;
        }

        public long getLiquidationMarginRatioPpm() {
            return liquidationMarginRatioPpm;
        }

        public void setLiquidationMarginRatioPpm(long liquidationMarginRatioPpm) {
            this.liquidationMarginRatioPpm = liquidationMarginRatioPpm;
        }

        public Duration getMaxMarkAge() {
            return maxMarkAge;
        }

        public void setMaxMarkAge(Duration maxMarkAge) {
            this.maxMarkAge = maxMarkAge;
        }

        public int getScanBatchSize() {
            return scanBatchSize;
        }

        public void setScanBatchSize(int scanBatchSize) {
            this.scanBatchSize = scanBatchSize;
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

    public static class Coordination {
        private boolean enabled = true;
        private String nodeId;
        private Duration leaseDuration = Duration.ofSeconds(15);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getNodeId() {
            return nodeId;
        }

        public void setNodeId(String nodeId) {
            this.nodeId = nodeId;
        }

        public Duration getLeaseDuration() {
            return leaseDuration;
        }

        public void setLeaseDuration(Duration leaseDuration) {
            this.leaseDuration = leaseDuration;
        }
    }
}
