package com.surprising.aeron.service;

import com.surprising.product.api.ProductLine;
import com.surprising.aeron.protocol.ProductLineClusterLayout;
import io.aeron.ChannelUriStringBuilder;
import io.aeron.CommonContext;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class ClusterTopology {

    private static final int TERM_LENGTH = 64 * 1024;

    private final ProductLine productLine;
    private final int nodeId;
    private final List<String> hostnames;
    private final Path dataDirectory;

    public ClusterTopology(ProductLine productLine, int nodeId, List<String> hostnames, Path dataDirectory) {
        this.productLine = Objects.requireNonNull(productLine, "productLine");
        if (nodeId < 0 || nodeId >= ProductLineClusterLayout.MEMBER_COUNT) {
            throw new IllegalArgumentException("nodeId must be between 0 and 2");
        }
        if (hostnames == null || hostnames.size() != ProductLineClusterLayout.MEMBER_COUNT
                || hostnames.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("exactly three non-blank hostnames are required");
        }
        this.nodeId = nodeId;
        this.hostnames = List.copyOf(hostnames);
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
    }

    public static ClusterTopology fromSystemProperties() {
        ProductLine productLine = ProductLine.requireExternalCode(
                System.getProperty("surprising.aeron.product-line", "SPOT"));
        int nodeId = Integer.parseInt(System.getProperty("surprising.aeron.node-id", "0"));
        List<String> hostnames = Arrays.stream(System.getProperty(
                "surprising.aeron.hostnames", "localhost,localhost,localhost").split(","))
                .map(String::trim)
                .toList();
        Path dataDirectory = Path.of(System.getProperty("surprising.aeron.data-dir", "data/aeron"));
        return new ClusterTopology(productLine, nodeId, hostnames, dataDirectory);
    }

    public ProductLine productLine() {
        return productLine;
    }

    public int nodeId() {
        return nodeId;
    }

    public int clusterId() {
        return ProductLineClusterLayout.clusterId(productLine);
    }

    public String hostname() {
        return hostnames.get(nodeId);
    }

    public Path nodeDirectory() {
        return dataDirectory.resolve(productLine.name().toLowerCase()).resolve("node" + nodeId);
    }

    public String aeronDirectoryName() {
        return CommonContext.getAeronDirectoryName() + "-surprising-"
                + productLine.name().toLowerCase() + "-" + nodeId;
    }

    public String archiveControlChannel() {
        return udpChannel(nodeId, hostname(), ProductLineClusterLayout.ARCHIVE_CONTROL_OFFSET);
    }

    public String clusterMembers() {
        return ProductLineClusterLayout.clusterMembers(productLine, hostnames);
    }

    public String ingressEndpoints() {
        return ProductLineClusterLayout.ingressEndpoints(productLine, hostnames);
    }

    public String replicationChannel() {
        return new ChannelUriStringBuilder().media("udp").endpoint(hostname() + ":0").build();
    }

    private String udpChannel(int memberId, String host, int offset) {
        return new ChannelUriStringBuilder()
                .media("udp")
                .termLength(TERM_LENGTH)
                .endpoint(host + ":" + ProductLineClusterLayout.port(productLine, memberId, offset))
                .build();
    }
}
