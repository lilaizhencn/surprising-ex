package com.surprising.trading.order.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "surprising.trading.order")
public class TradingOrderProperties {

    private Kafka kafka = new Kafka();
    private Outbox outbox = new Outbox();
    private Risk risk = new Risk();
    private FeeTier feeTier = new FeeTier();

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

    public static class Kafka {
        private String bootstrapServers = "localhost:9092";
        private String orderCommandsTopic = "surprising.perp.order.commands.v1";
        private String orderEventsTopic = "surprising.perp.order.events.v1";

        public String getBootstrapServers() {
            return bootstrapServers;
        }

        public void setBootstrapServers(String bootstrapServers) {
            this.bootstrapServers = bootstrapServers;
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
}
