package com.surprising.aeron.service.config;

import com.surprising.aeron.service.cluster.ClusterTopology;
import com.surprising.product.api.ProductLine;
import java.nio.file.Path;
import java.util.Arrays;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Configuration for one Aeron core node. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CoreConfiguration.class)
@ConfigurationProperties(prefix = "surprising.aeron")
public class CoreConfiguration {

    private final CpuAffinity cpuAffinity = new CpuAffinity();

    public CpuAffinity getCpuAffinity() { return cpuAffinity; }

    public static final class CpuAffinity {
        private boolean enabled;
        private int ownerCpu = -1;
        private int matcherCpu = -1;
        private int lane0Cpu = -1;
        private int lane1Cpu = -1;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getOwnerCpu() { return ownerCpu; }
        public void setOwnerCpu(int cpu) { ownerCpu = cpu; }
        public int getMatcherCpu() { return matcherCpu; }
        public void setMatcherCpu(int cpu) { matcherCpu = cpu; }
        public int getLane0Cpu() { return lane0Cpu; }
        public void setLane0Cpu(int cpu) { lane0Cpu = cpu; }
        public int getLane1Cpu() { return lane1Cpu; }
        public void setLane1Cpu(int cpu) { lane1Cpu = cpu; }
    }

    @Bean
    public TradingCpuAffinity tradingCpuAffinity() {
        return new TradingCpuAffinity(cpuAffinity.enabled, cpuAffinity.ownerCpu,
                cpuAffinity.matcherCpu, cpuAffinity.lane0Cpu, cpuAffinity.lane1Cpu);
    }

    private String productLine;
    private int nodeId;
    private String hostnames = "localhost,localhost,localhost";
    private Path dataDir = Path.of("data/aeron");

    public String getProductLine() {
        return productLine;
    }

    public void setProductLine(String productLine) {
        this.productLine = productLine == null ? null : productLine.trim();
    }

    public int getNodeId() {
        return nodeId;
    }

    public void setNodeId(int nodeId) {
        this.nodeId = nodeId;
    }

    public String getHostnames() {
        return hostnames;
    }

    public void setHostnames(String hostnames) {
        if (hostnames != null && !hostnames.isBlank()) {
            this.hostnames = hostnames.trim();
        }
    }

    public Path getDataDir() {
        return dataDir;
    }

    public void setDataDir(Path dataDir) {
        if (dataDir != null) {
            this.dataDir = dataDir;
        }
    }

    @Bean
    public ClusterTopology clusterTopology() {
        if (productLine == null || productLine.isBlank()) {
            throw new IllegalStateException("surprising.aeron.product-line is required");
        }
        return new ClusterTopology(ProductLine.requireExternalCode(productLine), nodeId,
                Arrays.stream(hostnames.split(",")).map(String::trim).toList(), dataDir);
    }

}
