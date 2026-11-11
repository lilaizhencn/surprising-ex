package com.surprising.aeron.config;

import com.surprising.aeron.protocol.ProductLineClusterLayout;
import com.surprising.product.api.ProductLine;
import io.aeron.ChannelUriStringBuilder;
import io.aeron.CommonContext;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import lombok.Getter;
import lombok.experimental.Accessors;

/** 保存一个产品线节点的成员编号、地址和数据目录，并统一计算它要使用的端口与通道。同一套规则也供客户端和运维工具使用，避免服务端与客户端各自拼出不同的地址。 */
public final class ClusterTopology {

    /** 归档控制通道使用的 term buffer 大小，单位是字节。 */
    private static final int TERM_LENGTH = 64 * 1024;

    /** 返回当前节点的产品线，不能用其他产品线的账户或订单状态代替。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final ProductLine productLine;
    @Accessors(fluent = true, chain = false)
    @Getter
    private final int nodeId;
    /** 按成员编号排列的主机地址，构造后不允许外部修改。 */
    private final List<String> hostnames;
    private final Path dataDirectory;

    /** 校验并固定本节点的地址信息。 */
    public ClusterTopology(ProductLine productLine, int nodeId, List<String> hostnames, Path dataDirectory) {
        this.productLine = Objects.requireNonNull(productLine, "productLine");
        ProductLineClusterLayout.requireHostnames(hostnames);
        if (nodeId < 0 || nodeId >= hostnames.size()) {
            throw new IllegalArgumentException("nodeId must identify a configured cluster member");
        }
        this.nodeId = nodeId;
        this.hostnames = List.copyOf(hostnames);
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
    }

    /** 为工具等非 Spring 入口读取 JVM 系统属性。 */
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

    /** 按产品线生成 Aeron 集群编号，让不同产品线的集群互相隔离。 */
    public int clusterId() {
        return ProductLineClusterLayout.clusterId(productLine);
    }

    /** 返回本成员对应的主机地址，不是整个成员列表。 */
    public String hostname() {
        return hostnames.get(nodeId);
    }

    /** 生成本节点的持久化目录：根目录 / 产品线 / node加成员编号。 */
    public Path nodeDirectory() {
        return dataDirectory.resolve(productLine.name().toLowerCase()).resolve("node" + nodeId);
    }

    /** 生成本节点 MediaDriver 的共享目录名，用产品线和成员编号避免本机节点互相覆盖。 */
    public String aeronDirectoryName() {
        return CommonContext.getAeronDirectoryName() + "-surprising-"
                + productLine.name().toLowerCase() + "-" + nodeId;
    }

    /** 返回客户端访问本节点 Archive 的 UDP 控制通道。 */
    public String archiveControlChannel() {
        return udpChannel(nodeId, hostname(), ProductLineClusterLayout.ARCHIVE_CONTROL_OFFSET);
    }

    /** 生成 ConsensusModule 需要的完整成员列表，包含各成员的多种通信端口。 */
    public String clusterMembers() {
        return ProductLineClusterLayout.clusterMembers(productLine, hostnames);
    }

    /** 生成客户端提交命令使用的成员地址列表，端口与产品线布局保持一致。 */
    public String ingressEndpoints() {
        return ProductLineClusterLayout.ingressEndpoints(productLine, hostnames);
    }

    /** 生成复制接收通道。 */
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
