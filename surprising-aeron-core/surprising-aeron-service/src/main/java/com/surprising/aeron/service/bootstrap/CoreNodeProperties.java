package com.surprising.aeron.service.bootstrap;

import com.surprising.aeron.service.cluster.ClusterTopology;
import com.surprising.product.api.ProductLine;
import java.nio.file.Path;
import java.util.Arrays;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Boot-bound node settings; it does not contain trading state. */
@ConfigurationProperties(prefix = "surprising.aeron")
public record CoreNodeProperties(String productLine, int nodeId, String hostnames, Path dataDir) {

    public CoreNodeProperties {
        productLine = productLine == null || productLine.isBlank() ? "SPOT" : productLine.trim();
        hostnames = hostnames == null || hostnames.isBlank()
                ? "localhost,localhost,localhost"
                : hostnames.trim();
        dataDir = dataDir == null ? Path.of("data/aeron") : dataDir;
    }

    public ClusterTopology topology() {
        return new ClusterTopology(ProductLine.requireExternalCode(productLine), nodeId,
                Arrays.stream(hostnames.split(",")).map(String::trim).toList(), dataDir);
    }
}
