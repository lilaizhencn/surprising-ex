package com.surprising.aeron.service.cluster;

import com.surprising.product.api.ProductLine;
import com.surprising.aeron.protocol.ProductLineClusterLayout;
import io.aeron.ChannelUriStringBuilder;
import io.aeron.CommonContext;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 保存一个产品线节点的成员编号、地址和数据目录，并统一计算它要使用的端口与通道。
 * 同一套规则也供客户端和运维工具使用，避免服务端与客户端各自拼出不同的地址。
 */
public final class ClusterTopology {

    /** 归档控制通道使用的 term buffer 大小，单位是字节。 */
    private static final int TERM_LENGTH = 64 * 1024;

    /** 当前节点所属的产品线，决定集群编号、端口段和目录分组。 */
    private final ProductLine productLine;
    /** 当前成员在 hostnames 中的下标。 */
    private final int nodeId;
    /** 按成员编号排列的主机地址，构造后不允许外部修改。 */
    private final List<String> hostnames;
    /** 所有产品线节点数据的根目录。 */
    private final Path dataDirectory;

    /**
     * 校验并固定本节点的地址信息；非法编号或成员数量会在创建时直接报错。
     * @param productLine 当前节点所属的产品线，不能为 null
     * @param nodeId 当前成员在地址列表中的下标
     * @param hostnames 一个或三个成员的主机地址，顺序必须与成员编号一致
     * @param dataDirectory 保存产品线和节点子目录的根目录，不能为 null
     */
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

    /**
     * 为工具等非 Spring 入口读取 JVM 系统属性；这里未配置产品线时使用 SPOT。
     * 正式 Spring 节点走 CoreConfiguration，仍要求显式配置产品线。
     */
    public static ClusterTopology fromSystemProperties() {
        // 确认产品线代码合法，后面的端口和目录都以这个产品线为准。
        ProductLine productLine = ProductLine.requireExternalCode(
                System.getProperty("surprising.aeron.product-line", "SPOT"));
        // 读取本机承担哪个成员，后续构造器会检查编号是否越界。
        int nodeId = Integer.parseInt(System.getProperty("surprising.aeron.node-id", "0"));
        // 把逗号分隔的地址变成固定顺序的成员列表，并去掉每项的首尾空格。
        List<String> hostnames = Arrays.stream(System.getProperty(
                "surprising.aeron.hostnames", "localhost,localhost,localhost").split(","))
                .map(String::trim)
                .toList();
        // 工具与服务端使用同样的目录布局，避免读错节点的日志和快照。
        Path dataDirectory = Path.of(System.getProperty("surprising.aeron.data-dir", "data/aeron"));
        return new ClusterTopology(productLine, nodeId, hostnames, dataDirectory);
    }

    /** 返回当前节点的产品线，不能用其他产品线的账户或订单状态代替。 */
    public ProductLine productLine() {
        return productLine;
    }

    /** 返回本节点在成员列表中的编号。 */
    public int nodeId() {
        return nodeId;
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

    /** 生成复制接收通道；端口 0 表示由操作系统分配本地端口。 */
    public String replicationChannel() {
        return new ChannelUriStringBuilder().media("udp").endpoint(hostname() + ":0").build();
    }

    /**
     * 使用统一端口布局拼接一个 UDP 通道，调用方只需要说明成员和通道用途。
     * @param memberId 需要连接的成员编号
     * @param host 该成员的主机地址
     * @param offset 通道端口在该成员端口段内的偏移，例如 Archive 控制端口偏移
     */
    private String udpChannel(int memberId, String host, int offset) {
        return new ChannelUriStringBuilder()
                .media("udp")
                .termLength(TERM_LENGTH)
                .endpoint(host + ":" + ProductLineClusterLayout.port(productLine, memberId, offset))
                .build();
    }
}
