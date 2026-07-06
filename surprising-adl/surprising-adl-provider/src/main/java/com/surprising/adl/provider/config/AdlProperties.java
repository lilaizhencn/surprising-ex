package com.surprising.adl.provider.config;

import com.surprising.product.api.ProductLine;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "surprising.adl")
public class AdlProperties {

    private Kafka kafka = new Kafka();
    private Scanner scanner = new Scanner();

    public Kafka getKafka() {
        return kafka;
    }

    public void setKafka(Kafka kafka) {
        this.kafka = kafka == null ? new Kafka() : kafka;
    }

    public Scanner getScanner() {
        return scanner;
    }

    public void setScanner(Scanner scanner) {
        this.scanner = scanner;
    }

    public static class Kafka {
        private ProductLine productLine = ProductLine.LINEAR_PERPETUAL;
        private boolean productTopicsEnabled;

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

        public String getAccountType() {
            return productLine.accountTypeCode();
        }
    }

    public static class Scanner {
        private boolean enabled = true;
        private long scanDelayMs = 1000L;
        private long minDeficitAgeMs = 10_000L;
        private long maxMarkAgeMs = 5_000L;
        private int batchSize = 50;
        private int maxDeleveragesPerDeficit = 20;
        private int candidateMultiplier = 5;

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

        public long getMinDeficitAgeMs() {
            return minDeficitAgeMs;
        }

        public void setMinDeficitAgeMs(long minDeficitAgeMs) {
            this.minDeficitAgeMs = minDeficitAgeMs;
        }

        public long getMaxMarkAgeMs() {
            return maxMarkAgeMs;
        }

        public void setMaxMarkAgeMs(long maxMarkAgeMs) {
            this.maxMarkAgeMs = maxMarkAgeMs;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public int getMaxDeleveragesPerDeficit() {
            return maxDeleveragesPerDeficit;
        }

        public void setMaxDeleveragesPerDeficit(int maxDeleveragesPerDeficit) {
            this.maxDeleveragesPerDeficit = maxDeleveragesPerDeficit;
        }

        public int getCandidateMultiplier() {
            return candidateMultiplier;
        }

        public void setCandidateMultiplier(int candidateMultiplier) {
            this.candidateMultiplier = candidateMultiplier;
        }
    }
}
