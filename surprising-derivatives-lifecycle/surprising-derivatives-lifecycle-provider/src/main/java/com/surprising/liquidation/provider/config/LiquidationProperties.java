package com.surprising.liquidation.provider.config;

import com.surprising.product.api.ProductLine;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "surprising.liquidation")
public class LiquidationProperties {

    private ProductLine productLine = ProductLine.LINEAR_PERPETUAL;
    private Aeron aeron = new Aeron();
    private Coordinator coordinator = new Coordinator();
    private Execution execution = new Execution();

    public ProductLine getProductLine() { return productLine; }
    public void setProductLine(ProductLine value) {
        productLine = value == null ? ProductLine.LINEAR_PERPETUAL : value;
    }
    public Aeron getAeron() { return aeron; }
    public void setAeron(Aeron value) { aeron = value == null ? new Aeron() : value; }
    public Coordinator getCoordinator() { return coordinator; }
    public void setCoordinator(Coordinator value) {
        coordinator = value == null ? new Coordinator() : value;
    }
    public Execution getExecution() { return execution; }
    public void setExecution(Execution value) { execution = value == null ? new Execution() : value; }

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

    public static class Coordinator {
        private long delayMs = 25;
        private int workBatchSize = 256;
        private int maxPagesPerRun = 8;
        private int maxWorkBytes = 1_048_576;

        public long getDelayMs() { return delayMs; }
        public void setDelayMs(long value) {
            if (value < 1) throw new IllegalArgumentException("coordinator.delay-ms must be positive");
            delayMs = value;
        }
        public int getWorkBatchSize() { return workBatchSize; }
        public void setWorkBatchSize(int value) {
            if (value < 1 || value > 1_000) {
                throw new IllegalArgumentException("coordinator.work-batch-size must be in [1,1000]");
            }
            workBatchSize = value;
        }
        public int getMaxPagesPerRun() { return maxPagesPerRun; }
        public void setMaxPagesPerRun(int value) {
            if (value < 1 || value > 1_000) {
                throw new IllegalArgumentException("coordinator.max-pages-per-run must be in [1,1000]");
            }
            maxPagesPerRun = value;
        }
        public int getMaxWorkBytes() { return maxWorkBytes; }
        public void setMaxWorkBytes(int value) {
            if (value < 256 || value > 1_048_576) {
                throw new IllegalArgumentException("coordinator.max-work-bytes must be in [256,1048576]");
            }
            maxWorkBytes = value;
        }
    }

    public static class Execution {
        private boolean enabled = true;
        private long liquidationFeeRatePpm = 3_000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean value) { enabled = value; }
        public long getLiquidationFeeRatePpm() { return liquidationFeeRatePpm; }
        public void setLiquidationFeeRatePpm(long value) {
            if (value < 0 || value > 1_000_000) {
                throw new IllegalArgumentException("liquidation-fee-rate-ppm must be in [0,1000000]");
            }
            liquidationFeeRatePpm = value;
        }
    }
}
