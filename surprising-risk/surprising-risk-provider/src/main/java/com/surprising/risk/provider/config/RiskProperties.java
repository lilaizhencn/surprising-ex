package com.surprising.risk.provider.config;

import com.surprising.product.api.ProductLine;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "surprising.risk")
public class RiskProperties {

    private ProductLine productLine = ProductLine.LINEAR_PERPETUAL;
    private Calculation calculation = new Calculation();
    private Aeron aeron = new Aeron();

    public ProductLine getProductLine() { return productLine; }
    public void setProductLine(ProductLine value) {
        productLine = value == null ? ProductLine.LINEAR_PERPETUAL : value;
    }
    public Calculation getCalculation() { return calculation; }
    public void setCalculation(Calculation value) { calculation = value == null ? new Calculation() : value; }
    public Aeron getAeron() { return aeron; }
    public void setAeron(Aeron value) { aeron = value == null ? new Aeron() : value; }

    public static class Aeron {
        private List<String> hostnames = List.of("localhost", "localhost", "localhost");
        private String egressHostname = "localhost";
        private Duration responseTimeout = Duration.ofSeconds(5);
        private int clientConnections = 2;

        public List<String> getHostnames() { return hostnames; }
        public void setHostnames(List<String> value) {
            if (value == null || value.size() != 3
                    || value.stream().anyMatch(host -> host == null || host.isBlank())) {
                throw new IllegalArgumentException("aeron.hostnames must contain exactly three nonblank hosts");
            }
            hostnames = List.copyOf(value);
        }
        public String getEgressHostname() { return egressHostname; }
        public void setEgressHostname(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("aeron.egress-hostname must be nonblank");
            }
            egressHostname = value.trim();
        }
        public Duration getResponseTimeout() { return responseTimeout; }
        public void setResponseTimeout(Duration value) {
            if (value == null || value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException("aeron.response-timeout must be positive");
            }
            responseTimeout = value;
        }
        public int getClientConnections() { return clientConnections; }
        public void setClientConnections(int value) {
            if (value < 1 || value > 64) {
                throw new IllegalArgumentException("aeron.client-connections must be in [1,64]");
            }
            clientConnections = value;
        }
    }

    public static class Calculation {
        private boolean enabled = true;
        private long scanDelayMs = 1000L;
        private long warningMarginRatioPpm = 800_000L;
        private long liquidationMarginRatioPpm = 1_000_000L;
        private Duration maxMarkAge = Duration.ofSeconds(10);
        private int scanBatchSize = 500;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean value) { enabled = value; }
        public long getScanDelayMs() { return scanDelayMs; }
        public void setScanDelayMs(long value) {
            if (value < 0) throw new IllegalArgumentException("scanDelayMs must be non-negative");
            scanDelayMs = value;
        }
        public long getWarningMarginRatioPpm() { return warningMarginRatioPpm; }
        public void setWarningMarginRatioPpm(long value) {
            if (value < 0) throw new IllegalArgumentException("warningMarginRatioPpm must be non-negative");
            warningMarginRatioPpm = value;
        }
        public long getLiquidationMarginRatioPpm() { return liquidationMarginRatioPpm; }
        public void setLiquidationMarginRatioPpm(long value) {
            if (value < 0) throw new IllegalArgumentException("liquidationMarginRatioPpm must be non-negative");
            liquidationMarginRatioPpm = value;
        }
        public Duration getMaxMarkAge() { return maxMarkAge; }
        public void setMaxMarkAge(Duration value) {
            if (value == null || value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException("maxMarkAge must be positive");
            }
            maxMarkAge = value;
        }
        public int getScanBatchSize() { return scanBatchSize; }
        public void setScanBatchSize(int value) {
            if (value < 1 || value > 10_000) throw new IllegalArgumentException("scanBatchSize must be in [1,10000]");
            scanBatchSize = value;
        }
    }
}
