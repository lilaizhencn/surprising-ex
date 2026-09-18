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
