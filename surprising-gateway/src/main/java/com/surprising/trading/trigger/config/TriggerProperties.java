package com.surprising.trading.trigger.config;

import lombok.Getter;
import lombok.Setter;

import com.surprising.product.api.ProductLine;
import java.time.Duration;
import java.util.List;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@ConfigurationProperties(prefix = "surprising.trading.trigger")
public class TriggerProperties {

    @Setter
    private ProductLine productLine;
    private Execution execution = new Execution();
    private Aeron aeron = new Aeron();

    @PostConstruct
    void validate() {
        if (productLine == null) {
            throw new IllegalStateException("trigger product line is required");
        }
    }

    public void setExecution(Execution execution) {
        this.execution = execution == null ? new Execution() : execution;
    }

    public void setAeron(Aeron aeron) {
        this.aeron = aeron == null ? new Aeron() : aeron;
    }

    @Getter
    public static class Execution {
        private int triggerBatchSize = 200;
        private int maxTriggerScanPages = 16;
        private Duration staleTriggeringAfter = Duration.ofSeconds(30);
        private long maintenanceDelayMs = 1000L;

        public void setTriggerBatchSize(int triggerBatchSize) {
            if (triggerBatchSize <= 0) {
                throw new IllegalArgumentException("trigger batch size must be positive");
            }
            this.triggerBatchSize = triggerBatchSize;
        }

        public void setMaxTriggerScanPages(int maxTriggerScanPages) {
            if (maxTriggerScanPages <= 0) {
                throw new IllegalArgumentException("max trigger scan pages must be positive");
            }
            this.maxTriggerScanPages = maxTriggerScanPages;
        }

        public void setStaleTriggeringAfter(Duration staleTriggeringAfter) {
            if (staleTriggeringAfter == null || staleTriggeringAfter.isNegative() || staleTriggeringAfter.isZero()) {
                throw new IllegalArgumentException("stale triggering duration must be positive");
            }
            this.staleTriggeringAfter = staleTriggeringAfter;
        }

        public void setMaintenanceDelayMs(long maintenanceDelayMs) {
            if (maintenanceDelayMs <= 0) {
                throw new IllegalArgumentException("maintenance delay must be positive");
            }
            this.maintenanceDelayMs = maintenanceDelayMs;
        }
    }

    @Getter
    public static class Aeron {
        private List<String> hostnames = List.of("localhost", "localhost", "localhost");
        @Setter
        private String egressHostname = "localhost";
        private Duration responseTimeout = Duration.ofSeconds(5);
        private int clientConnections = 4;
        @Setter
        private int nodeId;

        public void setHostnames(List<String> hostnames) {
            if (hostnames == null || hostnames.isEmpty()) {
                throw new IllegalArgumentException("Aeron hostnames are required");
            }
            this.hostnames = List.copyOf(hostnames);
        }

        public void setResponseTimeout(Duration responseTimeout) {
            if (responseTimeout == null || responseTimeout.isNegative() || responseTimeout.isZero()) {
                throw new IllegalArgumentException("Aeron response timeout must be positive");
            }
            this.responseTimeout = responseTimeout;
        }

        public void setClientConnections(int clientConnections) {
            if (clientConnections <= 0) {
                throw new IllegalArgumentException("Aeron client connections must be positive");
            }
            this.clientConnections = clientConnections;
        }

    }
}
