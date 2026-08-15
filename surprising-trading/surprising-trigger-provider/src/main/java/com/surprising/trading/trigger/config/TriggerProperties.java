package com.surprising.trading.trigger.config;

import com.surprising.product.api.ProductLine;
import java.time.Duration;
import java.util.List;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "surprising.trading.trigger")
public class TriggerProperties {

    private ProductLine productLine;
    private Execution execution = new Execution();
    private Aeron aeron = new Aeron();

    @PostConstruct
    void validate() {
        if (productLine == null) {
            throw new IllegalStateException("trigger product line is required");
        }
    }

    public ProductLine getProductLine() {
        return productLine;
    }

    public void setProductLine(ProductLine productLine) {
        this.productLine = productLine;
    }

    public Execution getExecution() {
        return execution;
    }

    public void setExecution(Execution execution) {
        this.execution = execution == null ? new Execution() : execution;
    }

    public Aeron getAeron() {
        return aeron;
    }

    public void setAeron(Aeron aeron) {
        this.aeron = aeron == null ? new Aeron() : aeron;
    }

    public static class Execution {
        private int triggerBatchSize = 200;
        private int maxTriggerScanPages = 16;
        private Duration staleTriggeringAfter = Duration.ofSeconds(30);
        private long maintenanceDelayMs = 1000L;

        public int getTriggerBatchSize() {
            return triggerBatchSize;
        }

        public void setTriggerBatchSize(int triggerBatchSize) {
            if (triggerBatchSize <= 0) {
                throw new IllegalArgumentException("trigger batch size must be positive");
            }
            this.triggerBatchSize = triggerBatchSize;
        }

        public int getMaxTriggerScanPages() {
            return maxTriggerScanPages;
        }

        public void setMaxTriggerScanPages(int maxTriggerScanPages) {
            if (maxTriggerScanPages <= 0) {
                throw new IllegalArgumentException("max trigger scan pages must be positive");
            }
            this.maxTriggerScanPages = maxTriggerScanPages;
        }

        public Duration getStaleTriggeringAfter() {
            return staleTriggeringAfter;
        }

        public void setStaleTriggeringAfter(Duration staleTriggeringAfter) {
            if (staleTriggeringAfter == null || staleTriggeringAfter.isNegative() || staleTriggeringAfter.isZero()) {
                throw new IllegalArgumentException("stale triggering duration must be positive");
            }
            this.staleTriggeringAfter = staleTriggeringAfter;
        }

        public long getMaintenanceDelayMs() {
            return maintenanceDelayMs;
        }

        public void setMaintenanceDelayMs(long maintenanceDelayMs) {
            if (maintenanceDelayMs <= 0) {
                throw new IllegalArgumentException("maintenance delay must be positive");
            }
            this.maintenanceDelayMs = maintenanceDelayMs;
        }
    }

    public static class Aeron {
        private List<String> hostnames = List.of("localhost", "localhost", "localhost");
        private String egressHostname = "localhost";
        private Duration responseTimeout = Duration.ofSeconds(5);
        private int clientConnections = 4;
        private int nodeId;

        public List<String> getHostnames() {
            return hostnames;
        }

        public void setHostnames(List<String> hostnames) {
            if (hostnames == null || hostnames.isEmpty()) {
                throw new IllegalArgumentException("Aeron hostnames are required");
            }
            this.hostnames = List.copyOf(hostnames);
        }

        public String getEgressHostname() {
            return egressHostname;
        }

        public void setEgressHostname(String egressHostname) {
            this.egressHostname = egressHostname;
        }

        public Duration getResponseTimeout() {
            return responseTimeout;
        }

        public void setResponseTimeout(Duration responseTimeout) {
            if (responseTimeout == null || responseTimeout.isNegative() || responseTimeout.isZero()) {
                throw new IllegalArgumentException("Aeron response timeout must be positive");
            }
            this.responseTimeout = responseTimeout;
        }

        public int getClientConnections() {
            return clientConnections;
        }

        public void setClientConnections(int clientConnections) {
            if (clientConnections <= 0) {
                throw new IllegalArgumentException("Aeron client connections must be positive");
            }
            this.clientConnections = clientConnections;
        }

        public int getNodeId() {
            return nodeId;
        }

        public void setNodeId(int nodeId) {
            this.nodeId = nodeId;
        }
    }
}
