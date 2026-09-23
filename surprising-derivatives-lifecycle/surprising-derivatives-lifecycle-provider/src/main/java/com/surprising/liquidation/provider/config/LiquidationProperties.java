package com.surprising.liquidation.provider.config;

import lombok.Getter;

import com.surprising.product.api.ProductLine;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@ConfigurationProperties(prefix = "surprising.liquidation")
public class LiquidationProperties {

    private ProductLine productLine = ProductLine.LINEAR_PERPETUAL;
    private Aeron aeron = new Aeron();
    private Coordinator coordinator = new Coordinator();
    private Execution execution = new Execution();

    public void setProductLine(ProductLine value) {
        productLine = value == null ? ProductLine.LINEAR_PERPETUAL : value;
    }
    public void setAeron(Aeron value) { aeron = value == null ? new Aeron() : value; }
    public void setCoordinator(Coordinator value) {
        coordinator = value == null ? new Coordinator() : value;
    }
    public void setExecution(Execution value) { execution = value == null ? new Execution() : value; }

    @Getter
    public static class Aeron {
        // Local fan-out width; the shared connection itself is configured by surprising.risk.aeron.
        private int clientConnections = 2;
        public void setClientConnections(int value) {
            if (value < 1 || value > 64) {
                throw new IllegalArgumentException("aeron.client-connections must be in [1,64]");
            }
            clientConnections = value;
        }
    }

    @Getter
    public static class Coordinator {
        private long delayMs = 25;
        private int workBatchSize = 256;
        private int maxPagesPerRun = 8;
        private int maxWorkBytes = 1_048_576;

        public void setDelayMs(long value) {
            if (value < 1) throw new IllegalArgumentException("coordinator.delay-ms must be positive");
            delayMs = value;
        }
        public void setWorkBatchSize(int value) {
            if (value < 1 || value > 1_000) {
                throw new IllegalArgumentException("coordinator.work-batch-size must be in [1,1000]");
            }
            workBatchSize = value;
        }
        public void setMaxPagesPerRun(int value) {
            if (value < 1 || value > 1_000) {
                throw new IllegalArgumentException("coordinator.max-pages-per-run must be in [1,1000]");
            }
            maxPagesPerRun = value;
        }
        public void setMaxWorkBytes(int value) {
            if (value < 256 || value > 1_048_576) {
                throw new IllegalArgumentException("coordinator.max-work-bytes must be in [256,1048576]");
            }
            maxWorkBytes = value;
        }
    }

    @Getter
    public static class Execution {
        private boolean enabled = true;
        private long liquidationFeeRatePpm = 3_000;

        public void setEnabled(boolean value) { enabled = value; }
        public void setLiquidationFeeRatePpm(long value) {
            if (value < 0 || value > 1_000_000) {
                throw new IllegalArgumentException("liquidation-fee-rate-ppm must be in [0,1000000]");
            }
            liquidationFeeRatePpm = value;
        }
    }
}
